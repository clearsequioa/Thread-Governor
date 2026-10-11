# Thread Governor

**For Fabric, Minecraft 26.3 only (for now).**

Two modules, no mixins (so it never touches anything Sodium replaces):

1. **Thread governor** - when you move fast, take damage, join a world, or the client stalls, background threads (chunk builders, Minecraft's worker pool) are pushed to the lowest priority so the render thread and main loop get the CPU. When things calm down they are restored.
   - Windows and other OSes: Java thread priorities (a hint to the OS, but it does work on Windows).
   - Linux: also uses `chrt` (SCHED_IDLE <-> SCHED_OTHER) per thread. Needs `chrt` (util-linux), no root.
   - Joining a world: background threads stay lowered for the first 15 seconds (configurable), since loading is when hitches and input lag happen.
2. **Region read-ahead (singleplayer)** - predicts which region files you will need from your movement and reads them sequentially, throttled, so they are already in the OS page cache. It only reads, never writes, and doesn't store or change any world data. It only helps on slow drives and only for terrain that already exists.

## In-game config screen
Open it from **Mod Menu** (needs **Cloth Config**). Changes apply immediately, except the graphics device.
- **General** - toggle each module, join-time choke, verbose log.
- **Governor tuning** - speed threshold, stall limit, hold times, Java priority, and the lists of thread-name prefixes to lower or never touch.
- **Read-ahead** - look-ahead time, edge margin, cooldown, file size limit, read speed limit, per-minute budget.
- **Graphics device (Windows)** - see below.

The settings are also saved in `config/threadgovernor.json`. Without Mod Menu or Cloth Config the mod still works, you just edit that file instead.

## Graphics device picker (Windows)
Lists the graphics devices found by asking the Vulkan driver (the same method VulkanMod uses), e.g. `NVIDIA GeForce GTX 1080ti (dGPU)` and `Intel(R) UHD Graphics 730 (iGPU)`, and lets you choose **iGPU**, **dGPU**, or **let Windows decide**. This writes the same per-program setting as Windows Settings > Display > Graphics, for `javaw.exe`, so it affects every instance that uses the same Java. It only takes effect after Minecraft restarts, and a warning appears when you save a change. Windows makes the final decision, and it may not control which GPU the Vulkan backend picks, so check the "Using graphics device" line in the log.

## Notes
- **Verbose log** (on by default) writes every mode change, with the reason, and every pre-read to `latest.log`. Turn it off once you're happy.
- A **thread list** is written to `latest.log` about 15 seconds after you join a world. Use it to add other mods' background threads (for example `c2me-worker` if you use C2ME) in the config. The default prefixes are `Chunk Render Task Executor` and `Worker-Main`.
- Detected graphics devices are also written to the log the first time you open the config screen.

## Honest expectations
- It reduces hitches. It does **not** raise FPS and can't speed up chunk generation.
- It helps most on CPUs with few cores, and possibly at very high view distances. On a fast many-core PC it may do nothing (emphasis on may), so switch it off if you see no difference. Read-ahead probably does nothing useful on an SSD or NVMe.
- It has been tested on a small number of computers, including a dual-core laptop, so treat results as unproven.
- Most of the code was written with AI assistance.

## Requirements
- Minecraft 26.3, Fabric Loader 0.19.5 or newer, Fabric API, Java 25
- Optional: Mod Menu and Cloth Config (for the config screen)

## License
MIT 
