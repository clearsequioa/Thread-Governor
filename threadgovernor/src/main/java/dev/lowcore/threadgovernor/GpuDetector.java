package dev.lowcore.threadgovernor;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lists the graphics devices Windows knows about, read from the display-adapter registry class
 * (no admin rights, no native code). "Integrated vs dedicated" is a best guess from the name; Windows
 * makes the real decision when it applies the preference.
 */
final class GpuDetector {
	record Gpu(String name, boolean integrated) {
	}

	private static final String CLASS_KEY =
		"HKLM\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11cd-bfc1-08002be10318}";
	private static final Pattern DESC = Pattern.compile("^\\s*DriverDesc\\s+REG_SZ\\s+(.+?)\\s*$");

	private static List<Gpu> cached;
	private static String source = "none";
	private static String problem = "";

	private GpuDetector() {
	}

	/** Where the list came from: "Vulkan", "Windows registry" or "none". */
	static synchronized String source() {
		return source;
	}

	/** Why detection found nothing, if it did not. */
	static synchronized String problem() {
		return problem;
	}

	static synchronized List<Gpu> detect() {
		if (cached != null) return cached;
		List<Gpu> out = new ArrayList<>();

		// 1) Ask the Vulkan driver (the method VulkanMod uses): exact names and a real iGPU/dGPU flag.
		try {
			out = VulkanProbe.list();
			if (!out.isEmpty()) source = "Vulkan";
			else problem = "Vulkan reported no devices";
		} catch (Throwable t) { // missing Vulkan library, driver error, etc.
			problem = "Vulkan: " + t;
		}

		// 2) Fallback: Windows registry.
		if (out.isEmpty() && GpuPreference.supported()) {
			out = detectFromRegistry();
			if (!out.isEmpty()) source = "Windows registry";
		}

		ThreadGovernorClient.LOG.info("Graphics devices ({}): {}{}", source, out,
			out.isEmpty() ? " | problem: " + problem : "");
		cached = out;
		return out;
	}

	private static List<Gpu> detectFromRegistry() {
		List<Gpu> out = new ArrayList<>();
		try {
			Process p = new ProcessBuilder("reg", "query", CLASS_KEY, "/s", "/v", "DriverDesc")
				.redirectErrorStream(true).start();
			if (p.waitFor(8, TimeUnit.SECONDS)) {
				Charset cs = Charset.forName(System.getProperty("native.encoding", "UTF-8"));
				String text = new String(p.getInputStream().readAllBytes(), cs);
				for (String line : text.split("\\R")) {
					Matcher m = DESC.matcher(line);
					if (!m.matches()) continue;
					String name = m.group(1);
					String lower = name.toLowerCase(Locale.ROOT);
					if (lower.contains("basic render") || lower.contains("remote") || lower.contains("virtual")) continue;
					if (out.stream().noneMatch(g -> g.name().equals(name))) out.add(new Gpu(name, isIntegrated(lower)));
				}
				if (out.isEmpty()) problem += "; registry gave no DriverDesc lines (" + text.length() + " chars of output)";
			} else {
				p.destroyForcibly();
				problem += "; registry query timed out";
			}
		} catch (IOException | RuntimeException e) {
			problem += "; registry: " + e;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return out;
	}

	/** Best-guess classification from the adapter name. */
	static boolean isIntegrated(String lowerName) {
		if (lowerName.contains("nvidia") || lowerName.contains("geforce") || lowerName.contains("quadro")) return false;
		if (lowerName.contains("intel")) return !lowerName.contains("arc"); // Intel Arc cards are discrete
		if (lowerName.contains("radeon") || lowerName.contains("amd")) {
			// APU graphics: "Radeon(TM) Graphics", "Radeon Vega ...", "Radeon 780M" style names
			return lowerName.matches(".*radeon(\\(tm\\))? graphics.*")
				|| lowerName.contains("vega")
				|| lowerName.matches(".*radeon \\d{3,4}m.*");
		}
		return false;
	}
}
