package dev.lowcore.threadgovernor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plain-JSON config at config/threadgovernor.json. Edit it, then restart the game. */
public final class GovernorConfig {

	// ---- Module toggles ----
	/** Log every mode change and every pre-read at INFO so you can confirm it works. Turn off once happy. */
	public boolean verbose = true;
	public boolean governorEnabled = true;
	public boolean prefetchEnabled = true;

	// ---- Thread governor ----
	/** Thread-name prefixes treated as "background work" that may be deprioritized. */
	public List<String> backgroundThreadPrefixes = new ArrayList<>(List.of(
		"Chunk Render Task Executor", // Sodium / vanilla chunk builders (verify with the thread dump in latest.log)
		"Worker-Main"                 // Minecraft's shared background pool (world gen, light, etc.)
	));
	/** Threads that are never touched, even if they match a prefix above. */
	public List<String> neverTouchPrefixes = new ArrayList<>(List.of(
		"Render thread", "Server thread", "main", "ThreadGovernor"
	));
	/** Horizontal speed (blocks/second) above which background work is choked. Sprinting is about 5.6. */
	public double chokeSpeed = 5.0;
	/** How long to stay choked after taking damage, in ms. */
	public long combatHoldMs = 3000;
	/** A client tick that takes longer than this (ms) counts as a stall. Normal is 50. */
	public long stallMs = 120;
	/** Keep background threads choked for joinGraceMs after joining a world (loading is when input lag and server hitches happen). */
	public boolean chokeWhileJoining = true;
	/** Ignore stalls for this long after joining a world (loading naturally causes slow ticks). */
	public long joinGraceMs = 15000;
	/** How long to stay choked after a stall, in ms. */
	public long stallHoldMs = 3000;
	/** How long to stay choked after the last "busy" signal, in ms (prevents flip-flopping). */
	public long relaxDelayMs = 2500;
	/** Minimum time between mode changes, in ms. */
	public long minDwellMs = 1500;
	/** Java thread priority while choked (1-10). Only a hint on Linux. */
	public int chokedJavaPriority = 1;
	/** Linux only: use chrt to really change scheduling. "idle" = only runs on spare CPU, "batch" = milder. */
	public boolean useLinuxScheduling = true;
	public String chokePolicy = "idle";
	/** Log all thread names once, ~15s after joining a world, so you can fix the prefixes above. */
	public boolean logThreadNamesOnce = true;

	// ---- Region read-ahead (singleplayer only) ----
	/** Seconds ahead to project your movement when picking region files to pre-read. */
	public int lookAheadSeconds = 12;
	/** Pre-read the neighbouring region file when you are this close to its edge (blocks). */
	public int edgeMarginBlocks = 96;
	/** Do not pre-read the same region file again within this many seconds. */
	public int cooldownSeconds = 300;
	/** Skip region files bigger than this (MB). */
	public int maxFileMb = 48;
	/** Read throttle (MB/s) so read-ahead never saturates the disk. */
	public int maxReadMbPerSecond = 40;
	/** Hard cap on total read-ahead per minute (MB). */
	public int budgetMbPerMinute = 256;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** The live config. Edited in place by the config screen, so most options apply immediately. */
	public static GovernorConfig INSTANCE = new GovernorConfig();

	public static GovernorConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve("threadgovernor.json");
		GovernorConfig cfg = null;
		if (Files.exists(path)) {
			try (Reader r = Files.newBufferedReader(path)) {
				cfg = GSON.fromJson(r, GovernorConfig.class);
			} catch (Exception e) {
				ThreadGovernorClient.LOG.warn("Could not read {}, using defaults", path, e);
			}
		}
		if (cfg == null) cfg = new GovernorConfig();
		INSTANCE = cfg;
		cfg.save(); // rewrite so new options appear in the file
		return cfg;
	}

	public void save() {
		sanitize();
		Path path = FabricLoader.getInstance().getConfigDir().resolve("threadgovernor.json");
		try (Writer w = Files.newBufferedWriter(path)) {
			GSON.toJson(this, w);
		} catch (IOException e) {
			ThreadGovernorClient.LOG.warn("Could not write {}", path, e);
		}
	}

	private void sanitize() {
		if (backgroundThreadPrefixes == null) backgroundThreadPrefixes = new ArrayList<>();
		if (neverTouchPrefixes == null) neverTouchPrefixes = new ArrayList<>();
		chokedJavaPriority = Math.max(Thread.MIN_PRIORITY, Math.min(Thread.MAX_PRIORITY, chokedJavaPriority));
		maxReadMbPerSecond = Math.max(1, maxReadMbPerSecond);
		maxFileMb = Math.max(1, maxFileMb);
		budgetMbPerMinute = Math.max(1, budgetMbPerMinute);
		if (chokePolicy == null) chokePolicy = "idle";
	}
}
