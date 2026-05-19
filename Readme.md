# Slippi for Android

An unofficial Android port of [Project Slippi](https://slippi.gg) (the
Ishiiruka fork of Dolphin), built and tuned for the
[Ayn Thor](https://www.ayntec.com/) handheld but expected to run on most
recent ARM64 Android devices with Adreno or comparable GPUs. A separate
`x86_64` build is available for Intel/AMD Chromebooks that run Android apps.

> **Status:** Plays Melee online via Slippi netplay (direct-connect,
> unranked, teams) at full speed on the Thor, with ranked queues
> disabled in this Android build, with both the
> official GameCube USB adapter (WUP-028) and the device's built-in
> controls. Sign-in is handled by the official slippi.gg flow embedded
> in the launcher; the calibration wizard lets you tune stick deadzone
> and response curve per-stick and per-controller-port; on-device
> button remap is in. Vulkan is the default graphics backend. Full
> Slippi replay support too — netplay matches auto-save to the device,
> and an in-app browser lets you watch them with a touch-friendly
> seek / pause / fast-forward HUD. **Training Mode (Community
> Edition)** is built right into the launcher — tap a button and the
> app downloads the latest version and sets it up for you. Low-latency
> audio (Oboe / AAudio with selectable buffer presets) and a
> refresh-rate-cap-lift during emulation round out the latency story.
>
> This is a personal / hobbyist port — no warranty; treat it as such.

Not affiliated with Project Slippi; it builds on top of their work.
For the official desktop launcher, go to https://slippi.gg.

## Install

1. Download the right APK from the
   [Releases](../../releases) page.
   - Android phones, tablets, handhelds, and ARM Chromebooks:
     `slippi-android-vX.Y.Z-android-rN.apk`
   - Intel/AMD Chromebooks: `slippi-android-vX.Y.Z-android-rN-x86_64.apk`
2. On the Android device, enable installs from the file manager /
   browser you'll use ("Install unknown apps" → that app → toggle on).
3. Open the APK and install.
4. First-launch flow:
   - **Pick Melee 1.02 NTSC ISO** (the launcher copies it into
     app-private storage so the C++ side gets a real `fopen`-able
     path).
   - **Sign in via slippi.gg** — tap the green sign-in button. An
     in-app browser opens to `slippi.gg/online/enable`; log in as you
     would on the desktop. When you hit **Download** on that page the
     launcher captures the resulting `user.json` directly into
     `<app-files>/dolphin/Slippi/user.json` and the account card
     updates to show your display name + connect code.
     - Power users who already have a `user.json` on disk can use the
       small "Already have a user.json? Import it" link instead.
   - **Pick a graphics backend** — Vulkan (default) or OpenGL. Vulkan
     has lower CPU overhead and is recommended; OpenGL is available
     as a fallback.
   - (Optional) **⚙ Calibrate sticks** — see the live preview, set
     deadzone + response curve per stick. Defaults (2% / curve 1.5
     for main, 10% / curve 2.0 for C-stick) work fine without running
     the wizard.
   - (Optional) **🎮 Remap buttons** — per-device and per-adapter-port
     button mapping with a color-coded GC layout.
   - Tap **PLAY** for a live match, **▶ REPLAYS** to browse + watch
     saved `.slp` files (importer + bulk-delete + share are in the
     browser; netplay matches auto-save into it), or **TRAINING MODE**
     to set up [Training Mode Community Edition](https://github.com/AlexanderHarrison/TrainingMode-CommunityEdition).
     The first time you tap it the app downloads the latest version,
     patches your vanilla ISO on-device, and tells you how much extra
     storage it'll use before committing. After that, "Play" boots
     straight into training. When a new Training Mode version drops,
     tap "Check updates" → "Update". Tap "Remove" to free the storage
     back up.

> **Important:** If you are creating a new Slippi account, create it
> and log in once through the official PC / Mac / Linux launcher before
> trying to log in on Android. New accounts cannot be used in the
> mobile app until that desktop launcher step is complete.

### Install with Obtainium

1. Install [Obtainium](https://obtainium.imranr.dev/).
2. Add this app with the GitHub source URL:
   `https://github.com/MaxLaurence/slippi-android`
3. If Obtainium asks for an APK filter, use this for normal ARM64 Android
   devices:
   `^slippi-android-v.*-android-r[0-9]+\.apk$`
   For Intel/AMD Chromebooks, use:
   `^slippi-android-v.*-android-r[0-9]+-x86_64\.apk$`
4. Install the latest release.

The release package ID is `org.ishiiruka.slippidolphin`. In-place
updates require every published APK to be signed with the same release
key, so builds from other sources may need to be uninstalled before
switching to this update channel.

## What works

- Slippi netplay: direct-connect, unranked, teams. Ranked queues are
  disabled in this Android build.
- Official GameCube USB adapter (WUP-028 / WUP-028-NA), including
  hot-plug and 4-port local versus.
- Bluetooth controllers and on-device gamepads.
- JIT-backed native cores for ARM64 Android and x86_64 Chromebook builds.
  ARM64 is the primary tested path; x86_64 is intended for Intel/AMD
  ChromeOS devices and depends more heavily on the Chromebook's Android
  container and GPU driver behavior.
- Both Vulkan (default) and OpenGL ES 3.2 backends, runtime-toggle
  from the launcher.
- Player names, connect codes, chat messages.
- Slippi sign-in via embedded slippi.gg WebView (no Firebase key
  shipped in the binary).
- Per-stick calibration: deadzone + power-curve response, with a
  single-screen wizard that shows a live stick visualization.
  Defaults are sane; calibration is **optional**.
- Per-device + per-adapter-port button remap with conflict detection.
- Per-port stick calibration for each adapter slot (i.e. each
  player in a 4-controller local match can tune their own stick).
- Raw evdev stick read on supported devices (bypasses
  MotionEvent saturation when the Android driver clips early).
- **Replay browser + playback** — every netplay match auto-saves to
  the replay folder shown in the replay browser. Users can choose a
  folder; the app uses a staging path only while native Slippi is
  writing the file.
  In-app browser parses each `.slp` for stage, characters, duration,
  date and shows them in a sortable list. Tap a row to watch; the HUD
  gives you play / pause, ±5s jump, scrubbable seek bar, and a
  fast-forward toggle. Bulk delete (older than 7 / 30 days, or all),
  per-row delete, and Android-share-sheet export for sending `.slp`
  files to other apps.
- **Training Mode (Community Edition)** built into the launcher —
  tap the **TRAINING MODE** button and the app fetches the latest
  release of [TrainingMode-CommunityEdition](https://github.com/AlexanderHarrison/TrainingMode-CommunityEdition)
  from GitHub, patches it into your vanilla ISO on-device, and lets
  you launch into it with one tap. Storage cost is shown up front
  (peak during build + final size). "Check updates" surfaces new
  versions whenever they drop; "Remove" cleans the patched ISO + any
  cached files back up. No need to track down xdelta tools or move
  files between a desktop and the device.
- **Low-latency audio** — Oboe (default), AAudio, and OpenSLES
  backends with a launcher-side preset picker (Low / Balanced /
  Stable burst counts). Tap the green "Audio: …" status text on the
  launcher to switch presets without restarting.
- **Lifted refresh-rate caps** — the launcher temporarily raises
  the system's `peak_refresh_rate` / `min_refresh_rate` settings
  while emulation is running (so battery-saver caps don't pin you
  to 60Hz mid-match) and restores them on exit. Requires
  `WRITE_SETTINGS` granted manually on sideloaded builds.
- Vulkan swap chain prefers `MAILBOX` over `IMMEDIATE` on Android
  (avoids the BLAST compositor's deferred-frame queueing) at
  `minImageCount`; emulator thread pinned to performance cores;
  multi-threaded `PerformanceHintManager` session targets the
  emu + GPU + audio TIDs (auto-discovered) at 60Hz cadence;
  netplay client thread runs at -8 priority.

## What doesn't work / known limitations

- On-screen touch overlay exists but hasn't had wide device QA. It's
  available behind the **Touch controls** link on the launcher; the
  default is to hide it whenever a physical / built-in controller is
  detected.
- Slippi Jukebox (in-game music) is force-disabled because the Rust
  `cpal` Android backend SIGABRTs on init; in-engine music still
  plays.
- First boot of a new game compiles ~hundreds of shaders (~5–10s on
  Adreno 740) before the first frame.
- The Thor's onboard stick reports saturated raw values past ~1/3 of
  physical travel for some firmware revisions. Calibration helps
  inside the unsaturated portion; for the strongest input precision,
  use the GC adapter.
- ChromeOS support is ABI/package-level and still needs per-device QA.
  Use the `x86_64` APK only on Intel/AMD Chromebooks; ARM Chromebooks
  should use the normal APK. The custom Qualcomm/Turnip driver path is
  ARM/Adreno-specific, so x86_64 Chromebooks use the system ChromeOS
  Android graphics stack. Direct USB GameCube adapter passthrough can
  vary by Chromebook model and policy.

## Build from source

See [Source/Android/README.md](Source/Android/README.md) for the full
build / install / debug workflow.

The short version:

```sh
git submodule update --init --recursive

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$HOME/.cargo/bin:/opt/homebrew/bin:$PATH

# Debug APK (signed with your debug keystore, fast):
./Source/Android/gradlew -p Source/Android :app:assembleDebug

# Chromebook / Intel-AMD ChromeOS debug APK:
./Source/Android/gradlew -p Source/Android :app:assembleDebug -PandroidAbi=x86_64

# Distributable / release APK (signed):
./Source/Android/gradlew -p Source/Android :app:assembleRelease

# Chromebook / Intel-AMD ChromeOS release APK:
./Source/Android/gradlew -p Source/Android :app:assembleRelease -PandroidAbi=x86_64
```

Output APKs land at `Source/Android/app/build/outputs/apk/{debug,release}/`.
The default ARM64 build keeps the standard `app-debug.apk` /
`app-release.apk` names. The x86_64 build gets an ABI-specific filename
such as `slippi-android-3.6.0-android-r7-x86_64-release.apk`.

## Credits

- [Project Slippi](https://github.com/project-slippi) — the netplay,
  matchmaking and replay stack this port depends on.
- [Training Mode Community Edition](https://github.com/AlexanderHarrison/TrainingMode-CommunityEdition) —
  the Melee training-mode patchset this Android port can download,
  patch and launch on-device.
- [connoranastasio/Ishiiruka-rocknix](https://github.com/connoranastasio/Ishiiruka-rocknix) —
  the ROCKNIX port that was a useful reference for the JitArm64
  emitter patches and several CMake-side build fixes.
- [Dolphin Emulator](https://dolphin-emu.org/) — the original
  GameCube / Wii emulator everything else builds on. The upstream
  README is preserved at [`Readme.upstream.md`](Readme.upstream.md).

## License

GPLv2+, same as upstream Dolphin and Slippi.
