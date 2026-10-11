package dev.lowcore.threadgovernor;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Read-ahead for region (.mca) files in singleplayer. It never writes and never hands data to the
 * game: it only reads files sequentially so the OS page cache already holds them when the game asks.
 * Reads are throttled and budgeted so they cannot saturate a mechanical drive.
 */
final class RegionPrefetcher {
	private static final int BLOCKS_PER_REGION = 512;

	private final GovernorConfig cfg;
	private final ThreadPoolExecutor io;
	private final Map<Path, Long> recent = new LinkedHashMap<>(); // client thread only

	private boolean havePos;
	private double lastX, lastZ;
	private int ticks;
	private long lastPlanKey = Long.MIN_VALUE; // skip work while the plan is unchanged (standing in one area)

	private Path cachedRoot;
	private String cachedDim;
	private Path cachedRegionDir;
	private long nextResolveMs;

	// budget (touched only from the io thread)
	private long budgetWindowStartMs;
	private long budgetBytesUsed;
	private ByteBuffer buf;

	RegionPrefetcher(GovernorConfig cfg) {
		this.cfg = cfg;
		this.io = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), r -> {
			Thread t = new Thread(r, "ThreadGovernor-Prefetch");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		}, new ThreadPoolExecutor.DiscardPolicy());
	}

	void onTick(Minecraft mc) {
		if (mc.player == null) return;
		if (++ticks % 20 != 0) return; // once per second

		var server = mc.getSingleplayerServer();
		if (server == null) { // multiplayer: nothing to read locally
			havePos = false;
			return;
		}

		double x = mc.player.getX();
		double z = mc.player.getZ();
		double vx = havePos ? x - lastX : 0; // blocks per second
		double vz = havePos ? z - lastZ : 0;
		lastX = x;
		lastZ = z;
		havePos = true;
		if (Math.hypot(vx, vz) > 200) return; // teleport, skip this sample

		Path root;
		try {
			root = server.getWorldPath(LevelResource.ROOT);
		} catch (RuntimeException e) {
			return;
		}
		Path regionDir = resolveRegionDir(root, mc.player.level().dimension().toString());
		if (regionDir == null) return;

		long nowMs = System.currentTimeMillis();
		List<int[]> regions = plan(x, z, vx, vz);
		long key = regionDir.hashCode();
		for (int[] r : regions) key = key * 31 + (((long) r[0] << 32) ^ (r[1] & 0xffffffffL));
		if (key == lastPlanKey) return; // same regions as last second: nothing new to read
		lastPlanKey = key;
		for (int[] r : regions) {
			Path file = regionDir.resolve("r." + r[0] + "." + r[1] + ".mca");
			Long last = recent.get(file);
			if (last != null && nowMs - last < cfg.cooldownSeconds * 1000L) continue;
			recent.put(file, nowMs);
			if (recent.size() > 256) {
				var it = recent.entrySet().iterator();
				it.next();
				it.remove();
			}
			io.execute(() -> prefetch(file));
		}
	}

	/** Region coordinates worth reading: the ones along the movement vector, plus edge neighbours. */
	private List<int[]> plan(double x, double z, double vx, double vz) {
		Set<Long> seen = new LinkedHashSet<>();
		List<int[]> out = new ArrayList<>();
		double speed = Math.hypot(vx, vz);

		if (speed >= 2.0) {
			int[] steps = {0, cfg.lookAheadSeconds / 3, (2 * cfg.lookAheadSeconds) / 3, cfg.lookAheadSeconds};
			for (int t : steps) addRegion(out, seen, x + vx * t, z + vz * t);
		} else {
			addRegion(out, seen, x, z);
		}

		// Near a region edge: also read the neighbour on that side, even if standing still.
		int m = cfg.edgeMarginBlocks;
		double rx = Math.floorMod((long) Math.floor(x), BLOCKS_PER_REGION);
		double rz = Math.floorMod((long) Math.floor(z), BLOCKS_PER_REGION);
		if (rx < m) addRegion(out, seen, x - m, z);
		if (rx > BLOCKS_PER_REGION - m) addRegion(out, seen, x + m, z);
		if (rz < m) addRegion(out, seen, x, z - m);
		if (rz > BLOCKS_PER_REGION - m) addRegion(out, seen, x, z + m);
		return out;
	}

	private static void addRegion(List<int[]> out, Set<Long> seen, double bx, double bz) {
		int rx = Math.floorDiv((int) Math.floor(bx), BLOCKS_PER_REGION);
		int rz = Math.floorDiv((int) Math.floor(bz), BLOCKS_PER_REGION);
		long key = ((long) rx << 32) ^ (rz & 0xffffffffL);
		if (seen.add(key)) out.add(new int[]{rx, rz});
	}

	// ---- world layout discovery ----

	/**
	 * Finds the "region" folder for the player's current dimension without depending on the save layout,
	 * which Mojang has changed over time (root/region, DIM-1/region, dimensions/minecraft/the_nether/region ...).
	 */
	private Path resolveRegionDir(Path root, String dimensionKey) {
		long nowMs = System.currentTimeMillis();
		if (root.equals(cachedRoot) && dimensionKey.equals(cachedDim)) {
			if (cachedRegionDir != null || nowMs < nextResolveMs) return cachedRegionDir;
		}
		cachedRoot = root;
		cachedDim = dimensionKey;
		nextResolveMs = nowMs + 30_000;
		cachedRegionDir = null;

		// dimensionKey looks like "ResourceKey[minecraft:dimension / minecraft:the_nether]"
		String dimPath = dimensionKey;
		int slash = dimPath.lastIndexOf('/');
		if (slash >= 0) dimPath = dimPath.substring(slash + 1);
		dimPath = dimPath.replace("]", "").strip();
		int colon = dimPath.indexOf(':');
		if (colon >= 0) dimPath = dimPath.substring(colon + 1);

		List<Path> candidates = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(root, 6)) {
			walk.filter(p -> p.getFileName() != null && p.getFileName().toString().equals("region") && Files.isDirectory(p))
				.forEach(candidates::add);
		} catch (IOException | RuntimeException e) {
			return null;
		}

		if (cfg.verbose) {
			ThreadGovernorClient.LOG.info("Region read-ahead: dimension '{}', found {} region folder(s) under {}", dimPath, candidates.size(), root);
		}
		for (Path c : candidates) { // new layout: the dimension name is a path segment
			if (hasSegment(root.relativize(c), dimPath)) return cachedRegionDir = c;
		}
		for (Path c : candidates) { // legacy layout
			Path rel = root.relativize(c);
			boolean nether = hasSegment(rel, "DIM-1");
			boolean end = hasSegment(rel, "DIM1");
			if (dimPath.equals("the_nether") && nether) return cachedRegionDir = c;
			if (dimPath.equals("the_end") && end) return cachedRegionDir = c;
			if (dimPath.equals("overworld") && !nether && !end && rel.getNameCount() == 1) return cachedRegionDir = c;
		}
		return null;
	}

	private static boolean hasSegment(Path p, String segment) {
		for (Path part : p) {
			if (part.toString().equals(segment)) return true;
		}
		return false;
	}

	// ---- the actual read (runs on the io thread) ----

	private void prefetch(Path file) {
		try {
			if (!Files.isRegularFile(file)) return;
			long maxBytes = cfg.maxFileMb * 1024L * 1024L;
			long size = Files.size(file);
			if (size <= 0 || size > maxBytes) return;

			long now = System.currentTimeMillis();
			if (now - budgetWindowStartMs > 60_000) {
				budgetWindowStartMs = now;
				budgetBytesUsed = 0;
			}
			long budget = cfg.budgetMbPerMinute * 1024L * 1024L;
			if (budgetBytesUsed + size > budget) return;
			budgetBytesUsed += size;

			if (buf == null) buf = ByteBuffer.allocateDirect(1 << 20);
			long nanosPerChunk = (1L << 20) * 1_000_000_000L / (cfg.maxReadMbPerSecond * 1024L * 1024L);

			if (cfg.verbose) {
				ThreadGovernorClient.LOG.info("Pre-reading {} ({} MB)", file.getFileName(), size / (1024 * 1024));
			}
			try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
				long pos = 0;
				while (pos < size) {
					long t0 = System.nanoTime();
					buf.clear();
					int n = ch.read(buf, pos);
					if (n < 0) break;
					pos += n;
					long spare = nanosPerChunk - (System.nanoTime() - t0);
					if (spare > 0) Thread.sleep(spare / 1_000_000L, (int) (spare % 1_000_000L));
				}
			}
		} catch (IOException e) {
			// File being rewritten by the game, or gone: ignore.
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (RuntimeException e) {
			ThreadGovernorClient.LOG.debug("Prefetch failed for {}", file, e);
		}
	}

	void reset() {
		havePos = false;
		lastPlanKey = Long.MIN_VALUE;
		cachedRoot = null;
		cachedDim = null;
		cachedRegionDir = null;
		recent.clear();
		io.getQueue().clear();
	}

	void shutdown() {
		io.shutdownNow();
	}
}
