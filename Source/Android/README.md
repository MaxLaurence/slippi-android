# Slippi for Android — build & dev notes

Aarch64-Android build of Slippi Ishiiruka with Slippi netplay /
matchmaking via the cross-compiled Rust extensions
(`Externals/SlippiRustExtensions/`).

For a user-facing overview see the [top-level Readme](../../Readme.md).
This document is the build/dev/debug reference.

## Prereqs (build host)

- macOS or Linux (build instructions below assume macOS — tweak rustup
  triple for Linux).
- OpenJDK 17: `brew install openjdk@17`
- Android cmdline-tools + NDK 27.1.12297006 + build-tools 34 + platform 34:
  `brew install --cask android-commandlinetools` then
  `sdkmanager 'ndk;27.1.12297006' 'build-tools;34.0.0' 'platforms;android-34'`
- rustup with the toolchain pinned in
  `Externals/SlippiRustExtensions/rust-toolchain.toml` (currently
  `1.88.0`) and the `aarch64-linux-android` target:
  ```
  rustup toolchain install 1.88.0
  rustup target add --toolchain 1.88.0 aarch64-linux-android
  ```
- The submodules: `git submodule update --init --recursive` from the repo
  root.

## Build

From the repo root:

```sh
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$HOME/.cargo/bin:/opt/homebrew/bin:$PATH

# Iteration build:
./Source/Android/gradlew -p Source/Android :app:assembleDebug

# Distributable build (signed):
./Source/Android/gradlew -p Source/Android :app:assembleRelease
```

Output APKs:

- `Source/Android/app/build/outputs/apk/debug/app-debug.apk`
- `Source/Android/app/build/outputs/apk/release/app-release.apk`

A clean build is ~8 minutes (Rust crates + boost dominate). Incremental
C++ rebuilds are ~10s; Java/AGP-only changes are sub-second.

## Release signing

`assembleRelease` produces a signed APK using one of two configurations:

1. **Default**: signs with your local `~/.android/debug.keystore`. The
   resulting APK installs cleanly on any device, but the key is unique
   to your machine — if you re-install Android Studio or build on a
   different host, future APKs will fail to upgrade existing installs
   (signature mismatch).
2. **Stable release key** (recommended for distribution): generate a
   keystore once, then point the build at it via env vars:
   ```sh
   keytool -genkeypair -v \
     -keystore ~/keys/slippi-android-release.jks \
     -alias slippi-android \
     -keyalg RSA -keysize 4096 -validity 36500
   export RELEASE_KEYSTORE_PATH="$HOME/keys/slippi-android-release.jks"
   export RELEASE_KEYSTORE_PASSWORD="..."
   export RELEASE_KEY_ALIAS="slippi-android"
   export RELEASE_KEY_PASSWORD="..."
   ```
   Keep the `.jks` and password offline; never commit them. If you
   lose either, you cannot publish updates that upgrade existing
   installs in-place.

## Install + run

```sh
ADB=$ANDROID_HOME/platform-tools/adb
$ADB install -r Source/Android/app/build/outputs/apk/debug/app-debug.apk
$ADB shell am start -n org.dolphinemu.dolphinemu.debug/.MainActivity
```

Wireless ADB (so you can keep the GameCube adapter plugged into the
device's only USB-C port):

```sh
$ADB pair <thor-ip>:<port>     # one-time
$ADB connect <thor-ip>:<port>
$ADB devices                   # confirm
```

## First-run flow (in the launcher)

1. **Pick Melee ISO** — `ACTION_OPEN_DOCUMENT`. The picked file is copied
   into app-private storage so the C++ side gets a real `fopen`-able
   path.
2. **Import user.json** — copy from a desktop Slippi launcher install.
   Lands at `<files>/dolphin/Slippi/user.json`, which is where the Rust
   EXI device looks for it.
3. **Pick backend** — Vulkan (default) or OpenGL ES.
4. **Play** — launches `EmulationActivity`, which gives a `SurfaceView`
   to the chosen backend and starts the emu thread.

## Defaults written on first launch

`SlippiDefaults.java` (DEFAULTS_VERSION=3, auto-rewrites on bump) writes
opinionated `Dolphin.ini` / `GFX.ini` / `WiimoteNew.ini` / `Logger.ini`
into `<files>/dolphin/Config/`:

- `GFXBackend=Vulkan` (overridden by launcher pick)
- `BackendMultithreading=False` — lower input latency on Adreno
- `EnableGPUTextureDecoding=True` — Adreno R/B-channel + CMPR fix
- `SlotA=255`, `SerialPort1=255` — disable to keep Slippi EXI on SlotB
- `SIDevice0=6` (standard pad) — flipped to `12` (GC adapter) at
  runtime when an adapter is detected.
- `SlippiJukeboxEnabled=False` — `cpal` SIGABRTs on Android.
- `TimingVariance=8` — slightly looser frame pacing for the Thor's
  scheduler.

## Known untested / probably-broken bits

- JIT memory allocation under Android's W^X — `MemoryUtil.cpp` still
  uses `PROT_READ|PROT_WRITE|PROT_EXEC` mmap. Works on app-private
  anonymous maps through Android 14; future versions may restrict.
- No on-screen touch overlay — only physical / Bluetooth pads work.
- First boot of a new game compiles a few hundred shaders (~5–10s on
  Adreno 740) before the first frame.
- Audio backend hasn't been latency-tuned.
- Linux build hosts: the gradle config hard-codes `aarch64-apple-darwin`
  in the rustup path resolution. Edit `Source/Android/app/build.gradle`
  line ~32 if you're on Linux.

## What changed vs upstream Slippi/Ishiiruka

See `git log android-port` for the full series. Highlights:

- `Source/Android/` — completely rewritten: AGP 8.3.2, AndroidX,
  Kotlin-free Java launcher (MainActivity + EmulationActivity + a
  one-screen Material 3 setup card flow).
- `CMakeLists.txt` — Slippi Rust extensions imported once at the top so
  the Android JNI build can link them (previously only DolphinWX did).
- `Source/Core/VideoCommon/PostProcessing.cpp` — Adreno GLSL ES
  parser-quirk workarounds (function-vs-macro for parameterless
  helpers, hoisted bicubic forward-decl, float literals).
- `Source/Core/VideoCommon/TextureDecoder_Generic.cpp` — added; gates
  the SSE-only decoder paths so ARM64 builds aren't missing a TU.
- `Source/Core/VideoBackends/Vulkan/VulkanContext.cpp` — gate BC/DXT
  texture format support on `features.textureCompressionBC` (fixes
  CMPR colored squares on Adreno).
- `Source/Core/VideoBackends/Vulkan/SwapChain.cpp` — drop swapchain
  depth to `minImageCount` for IMMEDIATE mode latency.
- `Source/Core/Common/StringUtil.cpp` — Android iconv can't open
  CP1252 / SJIS converters; `MinimalSJISToUTF8` and
  `MinimalUTF8ToSJIS` fallbacks keep game IDs and player names from
  going blank.
- `Source/Core/Common/FileUtil.cpp` — `GetSysDirectory()` on Android
  returns `<files>/Sys/` instead of the hardcoded
  `/sdcard/dolphin-emu/`.
- `Source/Core/InputCommon/GCAdapter_Android.cpp` — async-`UsbRequest`
  read pipeline, big-core `sched_setaffinity`, fixed memcpy buffer
  bound that was overflowing the 37-byte payload.
- `Source/Android/app/src/main/java/.../utils/Java_GCAdapter.java` —
  pre-queued `UsbRequest`s, hot-plug intent filter, faster polling.
- `Source/Android/jni/MainAndroid.cpp` — `PinEmuThreadToPerformanceCores()`
  + JNI hook for `PerformanceHintManager` session.
- `Source/Core/PowerPC/JitArm64/*.cpp`, `Arm64Emitter.cpp` — emitter
  patches from `connoranastasio/Ishiiruka-rocknix` to make JitArm64
  build under modern Clang.
- `Externals/libpng/CMakeLists.txt` — `PNG_ARM_NEON_OPT=0` (no arm/*.c
  sources in this vendored copy of libpng).
- `Externals/SlippiRustExtensions` (submodule) — Android logger that
  installs a panic hook routing `__android_log_write` to the
  `SlippiRust` tag.
