# Slippi for Android

An unofficial Android port of [Project Slippi](https://slippi.gg) (the
Ishiiruka fork of Dolphin), built and tuned for the
[Ayn Thor](https://www.ayntec.com/) handheld but expected to run on most
recent aarch64 Android devices with Adreno or comparable GPUs.

> **Status:** Plays Melee online via Slippi netplay (direct-connect, ranked,
> unranked) with the official GameCube USB adapter (WUP-028) at full speed
> on the Ayn Thor. Both Vulkan and OpenGL backends work and can be toggled
> from the launcher. Player names render correctly. This is a personal /
> hobbyist port — no warranty, no auto-update channel; treat it as such.

This is **not** affiliated with Project Slippi; it builds on top of their
work. For the official desktop launcher, go to https://slippi.gg.

## Install

1. Download `slippi-android-vX.Y.Z.apk` from the
   [Releases](../../releases) page.
2. On the Android device, enable installs from the file manager / browser
   you'll use ("Install unknown apps" → that app → toggle on).
3. Open the APK and install.
4. First-launch flow:
   - **Pick Melee 1.02 NTSC ISO** (the launcher will copy it into
     app-private storage so the C++ side gets a real `fopen`-able path).
   - **Import `user.json`** copied from a desktop Slippi launcher install.
     On macOS it lives at `~/Library/Application Support/com.project-slippi.dolphin/Slippi/user.json`.
     The Android equivalent lands at `<app-files>/dolphin/Slippi/user.json`.
   - **Pick a graphics backend** — Vulkan (default) or OpenGL. OpenGL
     tends to feel marginally tighter on Adreno; Vulkan has lower CPU
     overhead. Try both.
   - Tap **PLAY**.

## What works

- Slippi netplay: direct-connect, unranked, ranked, teams.
- GameCube USB adapter (WUP-028 / WUP-028-NA), including hot-plug.
- Bluetooth controllers, on-device gamepads (e.g. Ayn Thor controls).
- JIT (JitArm64) — the only realistic option for full-speed Melee on
  ARM.
- Both Vulkan and OpenGL ES 3.2 backends, runtime-toggle from the
  launcher.
- Player names, connect codes, chat messages.
- Slippi user-account login from a desktop-exported `user.json`.

## What doesn't work / known issues

- No on-screen touch overlay yet — you need a physical or Bluetooth
  controller.
- Slippi Jukebox (in-game music) is force-disabled because the Rust
  `cpal` Android backend SIGABRTs on init; in-engine music still plays.
- Replay viewer / playback UI hasn't been wired through the launcher.
- First boot of any new game compiles a few hundred shaders (~5–10s on
  Adreno 740) before the first frame renders.
- macOS hosts only for builds: the gradle config currently looks up the
  rustup toolchain under `${HOME}/.rustup/toolchains/...-aarch64-apple-darwin/`.
  Linux build hosts work with a one-line tweak.

## Build from source

See [Source/Android/README.md](Source/Android/README.md) for the full
build / install / debug workflow.

The short version:

```sh
git submodule update --init --recursive

export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$HOME/.cargo/bin:/opt/homebrew/bin:$PATH

# Debug APK (signed with your debug keystore, faster build):
./Source/Android/gradlew -p Source/Android :app:assembleDebug

# Release APK (signed with your debug keystore by default; see README
# for setting up a stable release key):
./Source/Android/gradlew -p Source/Android :app:assembleRelease
```

Output APKs land at `Source/Android/app/build/outputs/apk/{debug,release}/`.

## Credits

- [Project Slippi](https://github.com/project-slippi) — the netplay,
  matchmaking, replay, and ranked stack this port depends on.
- [connoranastasio/Ishiiruka-rocknix](https://github.com/connoranastasio/Ishiiruka-rocknix) —
  the ROCKNIX port that was a useful reference for the JitArm64 emitter
  patches and several CMake-side build fixes.
- [Dolphin Emulator](https://dolphin-emu.org/) — the original GameCube
  / Wii emulator everything else builds on. The upstream README is
  preserved at [`Readme.upstream.md`](Readme.upstream.md).

## License

GPLv2+, same as upstream Dolphin and Slippi.
