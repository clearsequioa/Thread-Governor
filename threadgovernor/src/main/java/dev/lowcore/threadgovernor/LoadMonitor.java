package dev.lowcore.threadgovernor;

import net.minecraft.client.Minecraft;

/**
 * Watches the player and the client tick rate and decides whether background threads should be
 * choked (player is busy) or normal (player is idle). Runs on the client thread; does almost no work.
 */
final class LoadMonitor {
	private final GovernorConfig cfg;
	private final ThreadController controller;

	private boolean havePos;
	private double lastX, lastZ;
	private double speed; // blocks/second, smoothed
	private long lastTickNanos;
	private long chokeUntilMs;
	private long lastChangeMs;
	private long graceUntilMs;
	private long lastGapMs;
	private boolean joinPending;
	private String reason = "";
	private int ticks;
	private ThreadController.Mode mode = ThreadController.Mode.NORMAL;

	LoadMonitor(GovernorConfig cfg, ThreadController controller) {
		this.cfg = cfg;
		this.controller = controller;
	}

	void onTick(Minecraft mc) {
		if (mc.player == null) {
			reset();
			return;
		}
		long nowNanos = System.nanoTime();
		long nowMs = nowNanos / 1_000_000L;
		if (joinPending) { // a real join, announced by the connection event (not just "governor switched on")
			joinPending = false;
			graceUntilMs = nowMs + cfg.joinGraceMs;
			if (cfg.chokeWhileJoining) hold(graceUntilMs, "joining world");
		}

		// 1) Stall detection: client ticks should be ~50 ms apart. Ignore huge gaps (loading screens, alt-tab).
		if (lastTickNanos != 0) {
			long gapMs = (nowNanos - lastTickNanos) / 1_000_000L;
			lastGapMs = gapMs;
			if (nowMs >= graceUntilMs && gapMs > cfg.stallMs && gapMs < 5000) hold(nowMs + cfg.stallHoldMs, "tick stall");
		}
		lastTickNanos = nowNanos;

		// 2) Speed from position delta (ignores teleports / portals).
		double x = mc.player.getX();
		double z = mc.player.getZ();
		if (havePos) {
			double d = Math.hypot(x - lastX, z - lastZ);
			if (d < 20.0) {
				speed = speed * 0.8 + (d * 20.0) * 0.2;
			}
		}
		lastX = x;
		lastZ = z;
		havePos = true;
		if (speed > cfg.chokeSpeed) hold(nowMs + cfg.relaxDelayMs, "speed");

		// 3) Combat: recently damaged.
		if (mc.player.hurtTime > 0) hold(nowMs + cfg.combatHoldMs, "damage");

		// 4) Decide, with a minimum dwell time so we never flip-flop.
		ThreadController.Mode desired = nowMs < chokeUntilMs ? ThreadController.Mode.CHOKED : ThreadController.Mode.NORMAL;
		if (desired != mode && nowMs - lastChangeMs >= cfg.minDwellMs) {
			mode = desired;
			lastChangeMs = nowMs;
			String why = mode == ThreadController.Mode.CHOKED
				? String.format("%s [speed=%.1f b/s, last tick gap=%d ms]", reason, speed, lastGapMs)
				: "calm";
			controller.apply(mode, why);
		}

		// 5) Every ~10 s, re-apply so newly spawned worker threads get the current mode.
		if (++ticks % 200 == 0) controller.refresh(mode);
	}

	private void hold(long untilMs, String why) {
		if (untilMs > chokeUntilMs) chokeUntilMs = untilMs;
		reason = why;
	}

	/** Called when the player joins a world. */
	void onJoin() {
		joinPending = true;
	}

	/** Called every tick while the governor is switched off in the config: undo any choke and forget state. */
	void whileDisabled() {
		joinPending = false;
		havePos = false;
		speed = 0;
		lastTickNanos = 0;
		chokeUntilMs = 0;
		if (mode != ThreadController.Mode.NORMAL) {
			mode = ThreadController.Mode.NORMAL;
			controller.apply(mode, "governor disabled");
		}
	}

	void reset() {
		havePos = false;
		speed = 0;
		lastTickNanos = 0;
		chokeUntilMs = 0;
		if (mode != ThreadController.Mode.NORMAL) {
			mode = ThreadController.Mode.NORMAL;
			controller.apply(mode, "left world");
		}
	}
}
