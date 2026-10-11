package dev.lowcore.threadgovernor;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Deliberately contains no mixins: everything is done through Fabric API events and
 * plain JDK calls, so it never touches the classes Sodium replaces.
 */
public final class ThreadGovernorClient implements ClientModInitializer {
	public static final Logger LOG = LoggerFactory.getLogger("ThreadGovernor");

	@Override
	public void onInitializeClient() {
		GovernorConfig cfg = GovernorConfig.load();
		ThreadController controller = new ThreadController(cfg);
		LoadMonitor monitor = new LoadMonitor(cfg, controller);
		RegionPrefetcher prefetcher = new RegionPrefetcher(cfg);

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (cfg.governorEnabled) monitor.onTick(mc);
			else monitor.whileDisabled();
			if (cfg.prefetchEnabled) prefetcher.onTick(mc);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			monitor.onJoin();
			if (cfg.logThreadNamesOnce) controller.scheduleThreadDumpOnce(15);
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			monitor.reset();
			prefetcher.reset();
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			controller.shutdown();
			prefetcher.shutdown();
		});

		LOG.info("Thread Governor loaded (governor={}, prefetch={})", cfg.governorEnabled, cfg.prefetchEnabled);
	}
}
