package dev.lowcore.threadgovernor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Invisible screen the config screen returns to after Save/Cancel. If a change needs a restart
 * (the graphics device), it shows a warning first; otherwise it passes straight through to the real parent.
 * It draws nothing itself, so it does not depend on the GUI rendering API.
 */
final class RestartGateScreen extends Screen {
	private final Screen next;
	private final boolean[] restartNeeded;
	private boolean done;

	RestartGateScreen(Screen next, boolean[] restartNeeded) {
		super(Component.empty());
		this.next = next;
		this.restartNeeded = restartNeeded;
	}

	@Override
	public void tick() {
		if (done) return;
		done = true;
		Minecraft mc = Minecraft.getInstance();
		if (!restartNeeded[0]) {
			mc.gui.setScreen(next);
			return;
		}
		restartNeeded[0] = false;
		mc.gui.setScreen(new ConfirmScreen(
			closeNow -> {
				if (closeNow) mc.stop();
				else mc.gui.setScreen(next);
			},
			Component.literal("Restart required"),
			Component.literal("The graphics device you selected only takes effect after Minecraft is restarted. Close Minecraft now?")));
	}
}
