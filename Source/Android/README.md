# Slippi Dolphin for Android (experimental Ayn Thor port)

Aarch64-Android build of Slippi Ishiiruka with Slippi netplay/matchmaking via
the cross-compiled Rust extensions.

> Status: builds an APK that contains the C++ core, JitArm64, Vulkan backend,
> OpenSL ES audio, and `libslippi_rust_extensions.so`. Not yet tested on a
> physical device. Treat as a rough prototype.

## Prereqs (build host)

- macOS or Linux
- OpenJDK 17 (`brew install openjdk@17`)
- Android cmdline-tools + NDK 27 + build-tools 34 + platform 34
  (`brew install --cask android-commandlinetools`, then `sdkmanager` the rest)
- rustup with the `1.88.0` toolchain and the `aarch64-linux-android` target

## Build

From the repo root:

```sh
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH=$HOME/.cargo/bin:/opt/homebrew/bin:$PATH

./Source/Android/gradlew -p Source/Android :app:assembleDebug
```

APK lands at `Source/Android/app/build/outputs/apk/debug/app-debug.apk`.

A clean build takes ~8 minutes (Rust crates dominate). Incremental builds are
~10 seconds.

## Install

```sh
adb install -r Source/Android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.dolphinemu.dolphinemu.debug/org.dolphinemu.dolphinemu.activities.MainActivity
```

## First-run flow

1. **Pick Melee ISO** — `ACTION_OPEN_DOCUMENT`. The picked file is copied to
   app-private storage so the C++ side gets a real `fopen()`-able path.
2. **Import user.json** — copy from a desktop Slippi launcher install. Lands
   at `<files_dir>/dolphin/Slippi/user.json`, which is where the Rust EXI
   device looks for it.
3. **Play** — launches `EmulationActivity`, which gives a `SurfaceView` to
   the Vulkan backend and starts the emulator thread.

Default configs (`Dolphin.ini`, `GFX.ini`, `WiimoteNew.ini`, `Logger.ini`)
are written to `<files_dir>/dolphin/Config/` on first launch — see
`SlippiDefaults.java`. They lock the backend to Vulkan, audio to OpenSL ES,
SI ports to standard pad, EFBAccessEnable=False, EnableGPUTextureDecoding=True
(Adreno R/B-channel + CMPR fixes).

## Known untested / probably-broken bits

- JIT memory allocation under Android's W^X — `MemoryUtil.cpp` still uses
  `PROT_READ|PROT_WRITE|PROT_EXEC` mmap. Should be fine on app-private
  anonymous maps up to current Android versions but unverified.
- Game runs with no on-screen overlay yet — only physical/Bluetooth pads
  work. Touchscreen control is not wired in.
- No GC USB adapter support (`Java_GCAdapter` is a stub).
- The first-run shader compile blocks the boot for ~10s on Adreno (per the
  ROCKNIX port's experience).
- The audio backend hasn't been latency-tuned; underruns possible.

## What changed vs upstream Slippi/Ishiiruka

See git diff for the full list. Highlights:

- `Source/Android/` — completely rewritten: AGP 8.3.2, AndroidX, minimal
  Kotlin-free Java launcher (MainActivity + EmulationActivity).
- `CMakeLists.txt` — Slippi Rust extensions imported once at the top so the
  Android JNI build can link them (previously only DolphinWX did).
- `Source/Core/VideoCommon/TextureDecoder_Generic.cpp` — added; gates the
  SSE-only decoder paths so ARM64 builds aren't missing a translation unit.
- `Source/Core/VideoBackends/Vulkan/VulkanContext.cpp` — gate BC/DXT texture
  format support on `features.textureCompressionBC` (fixes CMPR colored
  squares on Adreno).
- `Source/Core/VideoBackends/OGL/Render.cpp` — `s_*` → `m_*` for the Android
  surface-change members that moved to the base Renderer.
- `Source/Core/Common/PoolAlloc.h` — no-op `setAllocator()` so newer clang
  doesn't complain about private `operator=` access.
- `Source/Core/InputCommon/GCAdapter_Android.cpp` — add `IsReadingAtReducedRate()`,
  `ReadRate()`, and the `time_point*` out-param to `Input()` so the
  Slippi-modified header links.
- `Source/Core/Common/FileUtil.cpp` — `GetSysDirectory()` on Android returns
  `<user_dir>/Sys/` instead of the hardcoded `/sdcard/dolphin-emu/`.
- `Source/Core/UICommon/UICommon.cpp` — split the `__APPLE__||ANDROID` branch;
  Android now stubs the fallback path (Java always passes a real path).
- `Source/Core/Core/HW/EXI_DeviceSlippi.cpp` — guard the DolphinWX includes
  and the `main_frame->LowerRenderWindow()` call behind `!ANDROID`.
- `Source/Core/PowerPC/JitArm64/*.cpp`, `Source/Core/Common/Arm64Emitter.cpp` —
  ARM64 emitter patches from connoranastasio/Ishiiruka-rocknix.
- `Externals/libpng/CMakeLists.txt` — `PNG_ARM_NEON_OPT=0` (no arm/*.c sources
  in this vendored copy of libpng).
- `Externals/glslang/glslang/Include/PoolAlloc.h` — no-op `setAllocator()`.
- `Externals/semver/src/Semver200_parser.cpp` — drop unused `res` variable
  that fails `-Werror=unused-but-set-variable`.
