package dev.lowcore.threadgovernor;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Builds the Cloth Config screen. Only loaded when Cloth Config is present. */
final class ConfigScreenBuilder {
	private ConfigScreenBuilder() {
	}

	static Screen create(Screen parent) {
		GovernorConfig cfg = GovernorConfig.INSTANCE;
		GovernorConfig def = new GovernorConfig();
		GpuPreference.Choice currentGpu = GpuPreference.read();
		boolean[] restartNeeded = {false};

		ConfigBuilder b = ConfigBuilder.create()
			.setParentScreen(new RestartGateScreen(parent, restartNeeded))
			.setTitle(Component.literal("Thread Governor"))
			.setSavingRunnable(cfg::save);
		ConfigEntryBuilder eb = b.entryBuilder();

		// ---- General ----
		ConfigCategory general = b.getOrCreateCategory(Component.literal("General"));
		bool(general, eb, "Thread governor", "Lowers the priority of background threads while you are busy.",
			cfg.governorEnabled, def.governorEnabled, v -> cfg.governorEnabled = v);
		bool(general, eb, "Region read-ahead", "Pre-reads nearby world files so a slow drive has them ready (singleplayer).",
			cfg.prefetchEnabled, def.prefetchEnabled, v -> cfg.prefetchEnabled = v);
		bool(general, eb, "Choke while joining a world", "Keeps background threads low-priority for the first seconds after joining.",
			cfg.chokeWhileJoining, def.chokeWhileJoining, v -> cfg.chokeWhileJoining = v);
		bool(general, eb, "Verbose log", "Log every mode change and every pre-read in latest.log.",
			cfg.verbose, def.verbose, v -> cfg.verbose = v);

		// ---- Governor tuning ----
		ConfigCategory gov = b.getOrCreateCategory(Component.literal("Governor tuning"));
		dbl(gov, eb, "Choke speed (blocks/s)", "Moving faster than this counts as busy. Sprinting is about 5.6.",
			cfg.chokeSpeed, def.chokeSpeed, v -> cfg.chokeSpeed = v);
		lng(gov, eb, "Stall limit (ms)", "A client tick longer than this counts as a stall. Normal is 50.",
			cfg.stallMs, def.stallMs, v -> cfg.stallMs = v);
		lng(gov, eb, "Hold after stall (ms)", "How long to stay choked after a stall.",
			cfg.stallHoldMs, def.stallHoldMs, v -> cfg.stallHoldMs = v);
		lng(gov, eb, "Hold after damage (ms)", "How long to stay choked after taking damage.",
			cfg.combatHoldMs, def.combatHoldMs, v -> cfg.combatHoldMs = v);
		lng(gov, eb, "Relax delay (ms)", "How long to stay choked after the last busy signal.",
			cfg.relaxDelayMs, def.relaxDelayMs, v -> cfg.relaxDelayMs = v);
		lng(gov, eb, "Minimum time between changes (ms)", "Prevents rapid flip-flopping.",
			cfg.minDwellMs, def.minDwellMs, v -> cfg.minDwellMs = v);
		lng(gov, eb, "Join period (ms)", "How long after joining a world the join rules apply.",
			cfg.joinGraceMs, def.joinGraceMs, v -> cfg.joinGraceMs = v);
		gov.addEntry(eb.startIntSlider(Component.literal("Choked Java priority"), cfg.chokedJavaPriority, Thread.MIN_PRIORITY, Thread.MAX_PRIORITY)
			.setDefaultValue(def.chokedJavaPriority)
			.setTooltip(Component.literal("Java thread priority while choked (1 = lowest). Only a hint on Linux."))
			.setSaveConsumer(v -> cfg.chokedJavaPriority = v)
			.build());
		strList(gov, eb, "Background thread prefixes", "Threads whose names start with these are lowered while busy.",
			cfg.backgroundThreadPrefixes, def.backgroundThreadPrefixes, v -> cfg.backgroundThreadPrefixes = v);
		strList(gov, eb, "Never touch prefixes", "Threads with these prefixes are never changed.",
			cfg.neverTouchPrefixes, def.neverTouchPrefixes, v -> cfg.neverTouchPrefixes = v);

		// ---- Read-ahead ----
		ConfigCategory ra = b.getOrCreateCategory(Component.literal("Read-ahead"));
		integer(ra, eb, "Look-ahead (seconds)", "How far ahead to project your movement.",
			cfg.lookAheadSeconds, def.lookAheadSeconds, v -> cfg.lookAheadSeconds = v);
		integer(ra, eb, "Edge margin (blocks)", "Also read the next region file when this close to its edge.",
			cfg.edgeMarginBlocks, def.edgeMarginBlocks, v -> cfg.edgeMarginBlocks = v);
		integer(ra, eb, "Cooldown (seconds)", "Do not re-read the same file within this time.",
			cfg.cooldownSeconds, def.cooldownSeconds, v -> cfg.cooldownSeconds = v);
		integer(ra, eb, "Max file size (MB)", "Skip region files bigger than this.",
			cfg.maxFileMb, def.maxFileMb, v -> cfg.maxFileMb = v);
		integer(ra, eb, "Read speed limit (MB/s)", "Keeps read-ahead from saturating the disk.",
			cfg.maxReadMbPerSecond, def.maxReadMbPerSecond, v -> cfg.maxReadMbPerSecond = v);
		integer(ra, eb, "Budget (MB per minute)", "Hard cap on total read-ahead per minute.",
			cfg.budgetMbPerMinute, def.budgetMbPerMinute, v -> cfg.budgetMbPerMinute = v);

		// ---- Graphics device ----
		ConfigCategory gpu = b.getOrCreateCategory(Component.literal("Graphics device"));
		if (GpuPreference.supported()) {
			List<GpuDetector.Gpu> gpus = GpuDetector.detect();
			String integratedName = gpus.stream().filter(GpuDetector.Gpu::integrated).map(GpuDetector.Gpu::name).findFirst().orElse(null);
			String dedicatedName = gpus.stream().filter(g -> !g.integrated()).map(GpuDetector.Gpu::name).findFirst().orElse(null);

			gpu.addEntry(eb.startTextDescription(Component.literal(gpus.isEmpty()
				? "No graphics devices could be detected (" + GpuDetector.problem() + "). Check latest.log for details."
				: "Detected graphics devices (via " + GpuDetector.source() + "):")).build());
			for (GpuDetector.Gpu g : gpus) {
				gpu.addEntry(eb.startTextDescription(Component.literal(
					"  - " + g.name() + (g.integrated() ? "  (iGPU)" : "  (dGPU)"))).build());
			}
			if (gpus.size() < 2) {
				gpu.addEntry(eb.startTextDescription(Component.literal(
					"Only one graphics device found, so this setting will not change anything on this computer.")).build());
			}
			gpu.addEntry(eb.startTextDescription(Component.literal(
				"Windows picks the actual GPU: iGPU means Windows' power-saving GPU, dGPU means its high-performance GPU. "
					+ "The names above are what was detected and whether each is an iGPU or a dGPU. "
					+ "This is the same setting as Windows Settings > Display > Graphics, stored for javaw.exe, so it applies to every "
					+ "instance using this Java, and it only takes effect after Minecraft restarts.")).build());
			gpu.addEntry(eb.startEnumSelector(Component.literal("Graphics device"), GpuPreference.Choice.class, currentGpu)
				.setDefaultValue(GpuPreference.Choice.AUTO)
				.setEnumNameProvider(e -> Component.literal(gpuLabel(e, integratedName, dedicatedName)))
				.setSaveConsumer(choice -> {
					if (choice != currentGpu && GpuPreference.write(choice)) restartNeeded[0] = true;
				})
				.build());
		} else {
			gpu.addEntry(eb.startTextDescription(Component.literal("Graphics device selection is only available on Windows.")).build());
		}

		return b.build();
	}

	private static String gpuLabel(Enum<?> e, String integratedName, String dedicatedName) {
		return switch ((GpuPreference.Choice) e) {
			case AUTO -> "Let Windows decide";
			case INTEGRATED -> "iGPU: " + (integratedName != null ? integratedName : "none detected");
			case DEDICATED -> "dGPU: " + (dedicatedName != null ? dedicatedName : "none detected");
		};
	}

	private static void bool(ConfigCategory c, ConfigEntryBuilder eb, String name, String tip, boolean value, boolean def, Consumer<Boolean> save) {
		c.addEntry(eb.startBooleanToggle(Component.literal(name), value)
			.setDefaultValue(def).setTooltip(Component.literal(tip)).setSaveConsumer(save).build());
	}

	private static void dbl(ConfigCategory c, ConfigEntryBuilder eb, String name, String tip, double value, double def, Consumer<Double> save) {
		c.addEntry(eb.startDoubleField(Component.literal(name), value)
			.setDefaultValue(def).setMin(0D).setTooltip(Component.literal(tip)).setSaveConsumer(save).build());
	}

	private static void lng(ConfigCategory c, ConfigEntryBuilder eb, String name, String tip, long value, long def, Consumer<Long> save) {
		c.addEntry(eb.startLongField(Component.literal(name), value)
			.setDefaultValue(def).setMin(0L).setTooltip(Component.literal(tip)).setSaveConsumer(save).build());
	}

	private static void integer(ConfigCategory c, ConfigEntryBuilder eb, String name, String tip, int value, int def, Consumer<Integer> save) {
		c.addEntry(eb.startIntField(Component.literal(name), value)
			.setDefaultValue(def).setMin(0).setTooltip(Component.literal(tip)).setSaveConsumer(save).build());
	}

	private static void strList(ConfigCategory c, ConfigEntryBuilder eb, String name, String tip, List<String> value, List<String> def, Consumer<List<String>> save) {
		c.addEntry(eb.startStrList(Component.literal(name), new ArrayList<>(value))
			.setDefaultValue(new ArrayList<>(def)).setTooltip(Component.literal(tip)).setSaveConsumer(save).build());
	}
}
