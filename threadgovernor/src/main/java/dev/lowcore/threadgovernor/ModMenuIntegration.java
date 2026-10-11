package dev.lowcore.threadgovernor;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** Loaded only by Mod Menu. The Cloth Config screen is only touched when Cloth Config is installed. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return ModMenuApi.super.getModConfigScreenFactory();
		}
		return ConfigScreenBuilder::create;
	}
}
