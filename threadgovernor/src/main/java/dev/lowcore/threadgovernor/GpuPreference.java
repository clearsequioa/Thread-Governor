package dev.lowcore.threadgovernor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Windows only. Reads/writes the same per-program setting as Settings > System > Display > Graphics,
 * stored under HKCU (no admin rights needed). It applies the next time the program starts.
 * Note: it is stored per executable (javaw.exe), so it affects every instance using this Java.
 */
final class GpuPreference {
	enum Choice { AUTO, INTEGRATED, DEDICATED }

	private static final String KEY = "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences";

	private GpuPreference() {
	}

	static boolean supported() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
	}

	/** Full path of the Java executable that is running Minecraft right now. */
	static String exePath() {
		String cmd = ProcessHandle.current().info().command().orElse(null);
		if (cmd != null && !cmd.isBlank()) return cmd.replace('/', '\\');
		return (System.getProperty("java.home", "") + "\\bin\\javaw.exe").replace('/', '\\');
	}

	static Choice read() {
		if (!supported()) return Choice.AUTO;
		try {
			String out = run(List.of("reg", "query", KEY, "/v", exePath()));
			if (out == null) return Choice.AUTO;
			if (out.contains("GpuPreference=2")) return Choice.DEDICATED;
			if (out.contains("GpuPreference=1")) return Choice.INTEGRATED;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			ThreadGovernorClient.LOG.warn("Could not read GPU preference", e);
		}
		return Choice.AUTO;
	}

	/** @return true if the registry now holds the requested choice. */
	static boolean write(Choice choice) {
		if (!supported()) return false;
		try {
			if (choice == Choice.AUTO) {
				run(List.of("reg", "delete", KEY, "/v", exePath(), "/f")); // fails harmlessly if not set
			} else {
				int n = choice == Choice.DEDICATED ? 2 : 1;
				run(List.of("reg", "add", KEY, "/v", exePath(), "/t", "REG_SZ", "/d", "GpuPreference=" + n + ";", "/f"));
			}
			return read() == choice;
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) Thread.currentThread().interrupt();
			ThreadGovernorClient.LOG.warn("Could not write GPU preference", e);
			return false;
		}
	}

	/** @return command output, or null if it exited with an error. */
	private static String run(List<String> cmd) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		if (!p.waitFor(5, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			return null;
		}
		String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		return p.exitValue() == 0 ? out : null;
	}
}
