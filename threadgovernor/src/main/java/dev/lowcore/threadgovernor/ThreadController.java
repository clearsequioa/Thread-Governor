package dev.lowcore.threadgovernor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Applies the chosen mode to background threads.
 *
 * Two layers:
 *  - Thread.setPriority(): works everywhere, but on Linux the JVM ignores it by default.
 *  - Linux only: chrt on /proc/self/task/<tid>. SCHED_IDLE makes a thread run only on CPU time nobody
 *    else wants, and an unprivileged thread can switch back to SCHED_OTHER afterwards. This is the part
 *    that actually does something on Linux. Failures disable it and we fall back to Java priorities.
 *
 * All state below is touched only from the single worker thread, so no locking is needed.
 */
final class ThreadController {
	enum Mode { NORMAL, CHOKED }

	private final GovernorConfig cfg;
	private final boolean linux = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
	private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "ThreadGovernor-Worker");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private Mode lastMode;
	private Set<String> lastTids = Set.of();
	private int chrtFailures;
	private boolean chrtDisabled;
	private boolean dumpScheduled;

	ThreadController(GovernorConfig cfg) {
		this.cfg = cfg;
	}

	/** Mode change requested by the monitor. */
	void apply(Mode mode, String why) {
		submit(() -> applyNow(mode, why));
	}

	/** Periodic re-apply; only does work if the set of matching threads changed. */
	void refresh(Mode mode) {
		submit(() -> applyNow(mode, null));
	}

	void scheduleThreadDumpOnce(int delaySeconds) {
		if (dumpScheduled) return;
		dumpScheduled = true;
		try {
			exec.schedule(this::dumpThreads, delaySeconds, TimeUnit.SECONDS);
		} catch (RejectedExecutionException ignored) {
		}
	}

	void shutdown() {
		exec.shutdownNow();
	}

	private void submit(Runnable r) {
		try {
			exec.execute(r);
		} catch (RejectedExecutionException ignored) {
		}
	}

	private void applyNow(Mode mode, String why) {
		try {
			// Layer 1: Java priorities.
			int prio = mode == Mode.CHOKED ? cfg.chokedJavaPriority : Thread.NORM_PRIORITY;
			int adjusted = 0;
			for (Thread t : javaThreads()) {
				if (!wanted(t.getName(), false)) continue;
				try {
					t.setPriority(prio);
					adjusted++;
				} catch (RuntimeException ignored) {
				}
			}
			if (cfg.verbose && mode != lastMode) {
				ThreadGovernorClient.LOG.info("Background threads -> {} ({} Java thread(s) set to priority {}){}",
					mode, adjusted, prio, why == null ? "" : " because: " + why);
			}

			// Layer 2: Linux scheduling policy.
			if (linux && cfg.useLinuxScheduling && !chrtDisabled) {
				List<String> tids = linuxTids();
				Set<String> tidSet = new TreeSet<>(tids);
				boolean changed = mode != lastMode || !tidSet.equals(lastTids);
				if (changed && !tids.isEmpty()) {
					boolean ok = runChrt(mode, tids);
					if (ok) {
						chrtFailures = 0;
					} else if (++chrtFailures >= 3) {
						chrtDisabled = true;
						ThreadGovernorClient.LOG.warn("chrt is unavailable or denied; using Java thread priorities only");
					}
				}
				lastTids = tidSet;
			}
			if (mode != lastMode) {
				ThreadGovernorClient.LOG.debug("Background threads -> {}", mode);
			}
			lastMode = mode;
		} catch (Throwable t) {
			ThreadGovernorClient.LOG.warn("Thread governor step failed", t);
		}
	}

	private boolean runChrt(Mode mode, List<String> tids) throws IOException, InterruptedException {
		String flag;
		if (mode == Mode.CHOKED) {
			flag = "batch".equalsIgnoreCase(cfg.chokePolicy) ? "-b" : "-i";
		} else {
			flag = "-o"; // back to SCHED_OTHER
		}
		// One process for all threads. Prints "ok"/"fail" per thread.
		List<String> cmd = new ArrayList<>(List.of(
			"sh", "-c",
			"f=\"$1\"; shift; for t in \"$@\"; do if chrt \"$f\" -p 0 \"$t\" 2>/dev/null; then echo ok; else echo fail; fi; done",
			"sh", flag));
		cmd.addAll(tids);

		Process p = new ProcessBuilder(cmd)
			.redirectError(ProcessBuilder.Redirect.DISCARD)
			.start();
		if (!p.waitFor(5, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return false;
		}
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		int ok = 0, fail = 0;
		for (String line : out.split("\\R")) {
			if (line.equals("ok")) ok++;
			else if (line.equals("fail")) fail++;
		}
		if (fail > 0) {
			ThreadGovernorClient.LOG.debug("chrt {}: {} ok, {} failed", flag, ok, fail);
		}
		return ok > 0;
	}

	// ---- thread discovery ----

	private static List<Thread> javaThreads() {
		ThreadGroup root = Thread.currentThread().getThreadGroup();
		while (root.getParent() != null) root = root.getParent();
		Thread[] buf = new Thread[root.activeCount() + 64];
		int n = root.enumerate(buf, true);
		List<Thread> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(buf[i]);
		return out;
	}

	/** Linux thread ids whose kernel name (max 15 chars) matches a wanted prefix. */
	private List<String> linuxTids() {
		List<String> out = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of("/proc/self/task"))) {
			for (Path dir : ds) {
				try {
					String comm = Files.readString(dir.resolve("comm")).strip();
					if (wanted(comm, true)) out.add(dir.getFileName().toString());
				} catch (IOException ignored) {
				}
			}
		} catch (IOException e) {
			chrtDisabled = true;
			ThreadGovernorClient.LOG.warn("Cannot read /proc/self/task; Linux scheduling disabled");
		}
		Collections.sort(out);
		return out;
	}

	private boolean wanted(String name, boolean kernelName) {
		for (String never : cfg.neverTouchPrefixes) {
			if (prefixMatch(name, never, kernelName)) return false;
		}
		for (String p : cfg.backgroundThreadPrefixes) {
			if (prefixMatch(name, p, kernelName)) return true;
		}
		return false;
	}

	/** The kernel truncates thread names to 15 characters, so truncate the prefix the same way. */
	private static boolean prefixMatch(String name, String prefix, boolean kernelName) {
		String p = kernelName && prefix.length() > 15 ? prefix.substring(0, 15) : prefix;
		return !p.isEmpty() && name.startsWith(p);
	}

	// ---- one-time diagnostics ----

	private void dumpThreads() {
		try {
			Set<String> names = new TreeSet<>();
			for (Thread t : javaThreads()) names.add(t.getName());
			StringBuilder sb = new StringBuilder("Java threads (use these to tune backgroundThreadPrefixes):\n");
			for (String n : names) sb.append("  ").append(n).append('\n');
			ThreadGovernorClient.LOG.info(sb.toString());

			List<String> matchedJava = new ArrayList<>();
			for (String n : names) if (wanted(n, false)) matchedJava.add(n);
			ThreadGovernorClient.LOG.info("Currently matched as background: {} Java thread(s), {} Linux task(s)",
				matchedJava.size(), linux ? linuxTids().size() : 0);
		} catch (Throwable t) {
			ThreadGovernorClient.LOG.warn("Thread dump failed", t);
		}
	}
}
