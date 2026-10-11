# Thread Governor (Fabric, Minecraft 26.3, client-side)

Two modules, no mixins (so it never touches anything Sodium replaces):

1. **Thread governor** - when you move fast, take damage, or the client stalls, background threads
   (chunk builders, Minecraft's worker pool) are pushed to the lowest priority so the render thread
   and main loop get the CPU. When things calm down they are restored.
   - Linux: uses `chrt` (SCHED_IDLE <-> SCHED_OTHER) per thread. Needs `chrt` (util-linux), no root.
   - Other OSes: only Java thread priorities (weak hint).
2. **Region read-ahead** (singleplayer) - predicts which region files you will need from your
   movement and reads them sequentially, throttled, so they are already in the OS page cache.

## Install into a project
Easiest and safest: generate a fresh project at https://fabricmc.net/develop/template/
(Minecraft 26.3, mod id `threadgovernor`, no split sources, no data generation), then:
- copy `src/main/java/dev/lowcore/threadgovernor/` into it
- replace its `src/main/resources/fabric.mod.json` with this one (or merge the entrypoint + environment)
- delete the template's example mod class and mixin files/config references
- `./gradlew build` -> jar in `build/libs/`

The `build.gradle`, `gradle.properties` and `settings.gradle` here are my best reconstruction from the
Fabric 26.3 notes (Loom 1.17, Gradle 9.6.0, Loader 0.19.5, Fabric API 0.161.0+26.3). The template's
versions win if they differ. Requires Java 25.

## Config: config/threadgovernor.json (created on first launch)
After joining a world, wait ~15 s, then check `logs/latest.log` for the thread list and make sure
`backgroundThreadPrefixes` match your real Sodium/vanilla thread names.

## Notes from the first real log (Windows, Sodium 0.9.2, 26.3)
- Sodium's builder threads are named `Chunk Render Task Executor #N`, so the default prefix is correct.
- On Windows only the Java-priority layer applies (priority 1 maps to Win32 THREAD_PRIORITY_LOWEST).
  The Linux `chrt` layer is skipped automatically.
- `verbose` (default true) logs each mode change and each region pre-read at INFO.

## v1.2
- 15 s grace period after joining a world before stalls can trigger the choke (`joinGraceMs`).
- Default `stallMs` raised 90 -> 120. An existing config keeps the old value: delete
  `config/threadgovernor.json` once so it regenerates, or edit the number.

## v1.3
- Mode-change log lines now say why (tick stall / speed / damage) with the current speed and tick gap.

## v1.4
- Author set to AverageCritter.
- Read-ahead no longer re-reads the same files every few minutes while you stay in one area.

## v1.5
- `chokeWhileJoining` (default true): background threads stay choked for the first `joinGraceMs` after joining a world. v1.2 removed this by accident and the 2 s server hitch at join came back.

## 0.9.1.5 (versions now go 0.9.1.5, 0.9.1.6, ...)
- In-game config screen via Mod Menu + Cloth Config (both optional; the mod still loads without them).
  Changes apply immediately, except the graphics device.
- Windows graphics device picker (Let Windows decide / Integrated / Dedicated). It writes the same
  per-program setting as Windows Settings > Display > Graphics for javaw.exe. After saving a change,
  a warning asks to close Minecraft, because it only applies on the next launch.
- Build now needs the Mod Menu and Cloth Config maven repositories (added to build.gradle).

## 0.9.1.6
- Fix: Minecraft.setScreen no longer exists in 26.2+, now uses Minecraft.gui.setScreen (build failure in 0.9.1.5).

## 0.9.1.7
- Graphics device tab lists the detected GPUs (e.g. "NVIDIA GeForce GTX 1080 (dedicated)", "Intel(R) UHD Graphics 620 (integrated)") and the Integrated/Dedicated choices show which device each maps to.

## 0.9.1.8
- GPU detection now asks the Vulkan driver through LWJGL (the VulkanMod method) for exact names and iGPU/dGPU type; registry is the fallback. What it found (or why not) is written to latest.log ("Graphics devices (...)").
- Renamed Integrated/Dedicated to iGPU/dGPU.

## 0.9.1.9
- Fix: switching the governor on in the config mid-session no longer counts as "joining world" (it now only triggers on a real join).
- Fix: switching the governor off while background threads are choked now restores them to normal priority immediately.
