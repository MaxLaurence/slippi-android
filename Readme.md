# Slippi for Android

An unofficial Android port of [Project Slippi](https://slippi.gg) (the
Ishiiruka fork of Dolphin), built and tuned for the
[Ayn Thor](https://www.ayntec.com/) handheld but expected to run on most
recent aarch64 Android devices with Adreno or comparable GPUs.

> **Status:** Plays Melee online via Slippi netplay (direct-connect,
> ranked, unranked, teams) at full speed on the Thor, with both the
> official GameCube USB adapter (WUP-028) and the device's built-in
> controls. Sign-in is handled by the official slippi.gg flow embedded
> in the launcher; the calibration wizard lets you tune stick deadzone
> and response curve per-stick and per-controller-port; on-device
> button remap is in. Vulkan is the default graphics backend.
>
> This is a personal / hobbyist port — no warranty, no auto-update
> channel; treat it as such.

Not affiliated with Project Slippi; it builds on top of their work.
For the official desktop launcher, go to https://slippi.gg.

## Install

1. Download `slippi-android-vX.Y.Z.apk` from the
   [Releases](../../releases) page.
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
   - Tap **PLAY**.

## What works

- Slippi netplay: direct-connect, unranked, ranked, teams.
- Official GameCube USB adapter (WUP-028 / WUP-028-NA), including
  hot-plug and 4-port local versus.
- Bluetooth controllers and on-device gamepads.
- JitArm64 — full-speed Melee on ARM64.
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
- Vulkan swap chain depth tuned for low latency on Adreno; emulator
  thread pinned to performance cores; `PerformanceHintManager`
  session hints the SoC scheduler to 60Hz cadence.

## What doesn't work / known limitations

- On-screen touch overlay exists but hasn't had wide device QA. It's
  available behind the **Touch controls** link on the launcher; the
  default is to hide it whenever a physical / built-in controller is
  detected.
- Slippi Jukebox (in-game music) is force-disabled because the Rust
  `cpal` Android backend SIGABRTs on init; in-engine music still
  plays.
- Replay viewer / playback UI is not wired through the launcher.
- First boot of a new game compiles ~hundreds of shaders (~5–10s on
  Adreno 740) before the first frame.
- macOS hosts assumed for builds; Linux works with a one-line tweak
  to `Source/Android/app/build.gradle` (the rustup path is
  hard-coded to `aarch64-apple-darwin`).
- The Thor's onboard stick reports saturated raw values past ~1/3 of
  physical travel for some firmware revisions. Calibration helps
  inside the unsaturated portion; for the strongest input precision,
  use the GC adapter.

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

# Distributable / release APK (signed):
./Source/Android/gradlew -p Source/Android :app:assembleRelease
```

Output APKs land at `Source/Android/app/build/outputs/apk/{debug,release}/`.

## Credits

- [Project Slippi](https://github.com/project-slippi) — the netplay,
  matchmaking, replay, and ranked stack this port depends on.
- [connoranastasio/Ishiiruka-rocknix](https://github.com/connoranastasio/Ishiiruka-rocknix) —
  the ROCKNIX port that was a useful reference for the JitArm64
  emitter patches and several CMake-side build fixes.
- [Dolphin Emulator](https://dolphin-emu.org/) — the original
  GameCube / Wii emulator everything else builds on. The upstream
  README is preserved at [`Readme.upstream.md`](Readme.upstream.md).

## License

GPLv2+, same as upstream Dolphin and Slippi.
