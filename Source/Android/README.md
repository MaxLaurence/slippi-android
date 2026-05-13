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

# Force the onscreen GameCube touch controls even when a built-in or
# physical controller is detected:
./Source/Android/gradlew -p Source/Android :app:assembleDebug -PforceTouchControls=true

# Distributable build (signed):
./Source/Android/gradlew -p Source/Android :app:assembleRelease
```

Output APKs:

- `Source/Android/app/build/outputs/apk/debug/app-debug.apk`
- `Source/Android/app/build/outputs/apk/release/app-release.apk`

A clean build is ~8 minutes (Rust crates + boost dominate). Incremental
C++ rebuilds are ~10s; Java/AGP-only changes are sub-second.

## Slippi sign-in

The launcher signs users in by embedding `https://slippi.gg/online/enable`
inside a `WebView` (`SlippiLoginActivity`). The user logs in on that
page as they normally would in any browser; when they tap the
**Download** button the launcher intercepts the request — including
`blob:` URLs, via a JS bridge that captures the `Blob` payload — and
writes the response to `<app-files>/dolphin/Slippi/user.json`. From
the user's perspective it's a single in-app sign-in step, with no
external credentials in the binary.

A manual `user.json` import is still available behind a small link on
the launcher's account card for users who already have one on disk.

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

1. **Pick Melee ISO** — `ACTION_OPEN_DOCUMENT`. The picked file is
   copied into app-private storage so the C++ side gets a real
   `fopen`-able path.
2. **Sign in via slippi.gg** — opens `SlippiLoginActivity`, an
   in-app `WebView` pointed at `https://slippi.gg/online/enable`.
   The user logs in there; on tapping **Download**, our blob
   interceptor captures the response and writes it to
   `<files>/dolphin/Slippi/user.json` directly. (Manual file-picker
   import is still available as a link.)
3. **Pick backend** — Vulkan (default) or OpenGL ES.
4. (Optional) **Calibrate sticks** + **Remap buttons** — both have
   their own activities reached from the launcher. Defaults are
   tuned to work without ever running the wizards.
5. **Play** — launches `EmulationActivity`, which gives a
   `SurfaceView` to the chosen backend and starts the emu thread.
6. **Replays** — `▶ REPLAYS` opens `ReplayListActivity`. Netplay
   matches auto-save to `<files>/dolphin/Slippi/Replays/`; the same
   folder is the importer destination and the browser's source of
   truth. See **Replay playback** below.

## Defaults written on first launch

`SlippiDefaults.java` (auto-rewrites on bump of `DEFAULTS_VERSION`,
currently `7`) writes opinionated `Dolphin.ini` / `GFX.ini` /
`WiimoteNew.ini` / `Logger.ini` into `<files>/dolphin/Config/`:

- `GFXBackend=Vulkan` — Vulkan is now the default; launcher toggle
  flips to OGL if needed.
- `BackendMultithreading=False` — lower input latency on Adreno.
- `EnableGPUTextureDecoding=True` — Adreno R/B-channel + CMPR fix.
- `SlotA=255`, `SerialPort1=255` — disable to keep Slippi EXI on SlotB.
- `SIDevice0..3` — flipped to `12` (GC adapter) at runtime when an
  adapter is detected, so all four ports route through the WUP-028
  for local 4-player matches.
- `SlippiJukeboxEnabled=False` — `cpal` SIGABRTs on Android.
- `SlippiOnlineDelay=2` — explicit; matches PC netplay default.
- `TimingVariance=8` — slightly looser frame pacing for the Thor's
  scheduler.
- `DSP.Backend=Oboe` + `AndroidAudioBufferBursts=4` — see **Audio
  backends** below. Launcher's audio status link lets users override
  to AAudio / OpenSLES or a different burst count.
- `EFBScale=3` (SCALE_1X, was `2`/SCALE_AUTO_INTEGRAL) +
  `InternalResolution=1` — keeps EFB and main resolution at native
  on high-density Android displays. Auto-integral was silently
  raising GPU cost on the Thor.
- `ShowFPS=False`, `ShowNetPlayPing=False` — cleaner display by
  default.
- `SlippiSaveReplays=True` + `SlippiReplayDir=<files>/dolphin/Slippi/Replays`
  + `SlippiReplayMonthFolders=False` — auto-save netplay `.slp` files
  into the directory the in-app browser scans. `MonthFolders=False`
  keeps the dir flat so listing is one `File.listFiles` call. The
  `SlippiReplayDir` value is the only Dolphin.ini key that's not
  canned — it gets the absolute path substituted at write time in
  `SlippiDefaults.writeIfMissing(File, Context)`.

Calibration defaults (per-stick, applied even with no saved profile):
- Main stick: deadzone 2%, curve 1.5
- C-stick: deadzone 10%, curve 2.0

## Input pipeline (where the code lives)

- **Built-in / Bluetooth pad**: Android `MotionEvent` →
  `EmulationActivity.dispatchGenericMotionEvent` →
  `StickCalibration.apply` → `NativeLibrary.SetPadOverride` →
  `SI_PadOverride::Get` (called from
  `SI_DeviceGCController::GetPadStatus` and
  `NetPlayClient::GetNetPads`). Bypasses Dolphin's
  ControllerEmu/AnalogStick deadzone math.
- **Built-in pad with raw provider**: when a `RawStickInputProvider`
  is available for the device, `EmulationActivity` polls it each
  frame and substitutes its `RawStickState` for the corresponding
  `MotionEvent` axes before calibration. See **Raw stick input
  providers** below.
- **GC adapter (WUP-028)**: USB → `Java_GCAdapter.java` (async
  `UsbRequest` pipeline) → `GCAdapter_Android.cpp::Input` →
  raw GameCube stick bytes + `ApplyButtonRemap` (per-port atomic
  button table) → emulator. App-level stick calibration is not applied
  to GC adapter controllers.

## Audio backends

Three backends are wired through `Source/Core/AudioCommon/`:

- `Oboe` (default) — `OboeStream.{h,cpp}`. Native Oboe library
  selecting the LowLatency performance mode + Exclusive sharing when
  available. Configurable burst count.
- `AAudio` — `AAudioStream.{h,cpp}`. Direct AAudio. Bypasses the
  Oboe shim; useful as an A/B against Oboe on devices where Oboe's
  fallback heuristics pick the wrong path.
- `OpenSLES` — `OpenSLESStream.cpp`. Kept as a fallback for OEMs
  where Oboe/AAudio underrun. Reworked alongside the new backends to
  share the burst-count plumbing.

INI keys (all live in `[DSP]`):
- `Backend = Oboe | AAudio | OpenSLES`
- `AndroidAudioBufferBursts = 2 | 4 | 8` — number of audio bursts
  queued. Lower = lower latency, higher = fewer underruns. The
  launcher exposes six named presets backing these two settings.

The launcher status link "Audio: <backend> / <bursts> bursts" tells
the user what's active. Tapping it opens an `AlertDialog` with the
preset list (`AudioPreset[]` in `MainActivity`). Selection writes both
INI keys via `NativeLibrary.SetConfig` and updates the link in place.

If `Start()` on the configured backend fails, `AudioCommon.cpp` now
tries OpenSLES as a fallback before falling back to `NullSound` —
previous behavior was to drop straight to silence.

## Latency tuning (everything that's not audio)

The latency story across the build, top-down:

- **Vulkan swap chain** (`Source/Core/VideoBackends/Vulkan/SwapChain.cpp`):
  on Android, when vsync is off, prefer `VK_PRESENT_MODE_MAILBOX_KHR`
  over `VK_PRESENT_MODE_IMMEDIATE_KHR`. Reason: Android's BLAST
  buffering can keep older queued frames in flight under IMMEDIATE;
  MAILBOX forces SurfaceFlinger/HWC to scan out the latest completed
  frame. When MAILBOX is selected, `image_count = minImageCount`
  (matching the IMMEDIATE path) instead of `+1`.
- **Refresh-rate caps** (`EmulationActivity`): on `onCreate`, the
  activity reads the system `peak_refresh_rate` / `min_refresh_rate`
  via `Settings.System.getString`, stashes them, and writes higher
  values (so battery-saver caps don't pin the display at 60Hz when
  the panel can do 120Hz). Restored in `onDestroy`. Requires
  `WRITE_SETTINGS` granted manually on sideloaded builds — silently
  noop if not granted.
- **PerformanceHintManager** (`EmulationActivity.discoverPerfHintThreadTids`):
  creates a multi-thread hint session at 60Hz cadence, targeting the
  TIDs of the named native worker threads (emu, GPU, audio). Auto-
  discovers them by walking `/proc/<pid>/task` and matching against
  a known name list, with a fallback path that just uses the emu TID
  if discovery fails. Updates on resume via
  `hintSession.setThreads(...)` (UPSIDE_DOWN_CAKE+).
- **NetPlay client thread** (`NetPlayClient.cpp::ThreadFunc`): named
  `NetPlay Client` for `top`/profiler readability and given
  `setpriority(PRIO_PROCESS, 0, -8)` on Android — nudges the
  scheduler to favor it under load. Also adds a poll-stabilizer
  loop in `SendNetPad` so the local pad sample lands on a
  predictable cadence relative to the netplay tick.
- **Raw input blocking wait** (`RawStickInputProvider` +
  `NativeEvdevStickInputProvider` + JNI `WaitRawGamepadAxes`): the
  interface now exposes a `default RawStickState waitForSnapshot(int
  timeoutMs)` and `default boolean supportsBlockingWait()`. Native
  evdev opts in: the JNI side does a `poll(2)` (capped at 100ms)
  on the evdev fd before reading samples. Old behavior was busy
  polling at 8ms intervals — the new path wakes immediately when
  the kernel posts an `EV_ABS` event and sleeps otherwise. See
  **Raw stick input providers** below for the rest of the
  contract.

## Replay playback

PC Slippi drives replay playback through a JSON config polled by
`SlippiReplayComm`, whose path lives in `SConfig::m_strSlippiInput`.
The C++ machinery (`Source/Core/Core/Slippi/SlippiPlayback.{h,cpp}` +
`SlippiReplayComm.{h,cpp}` + `SlippiGameFileLoader.{h,cpp}` +
`SlippiLib`) is reused as-is — we only added the Android wiring.

**Where it lives** (`Source/Android/app/src/main/java/.../replay/`):

- `ReplayConfig` — knows the on-device paths (`Slippi/playback.json`,
  `Slippi/Replays/`) and writes the playback JSON. Per-launch
  `commandId` is the `isNewReplay()` discriminator — without it,
  replaying the same `.slp` twice would be a no-op.
- `ReplayMetadata` — pure-Java `.slp` header parser using
  `RandomAccessFile`. Reads the first ~600 bytes (UBJSON preamble +
  `EVENT_GAME_INIT` 0x36, 320 bytes per `SlippiLib/SlippiGame.h`
  `asmEvents` map → stage id, per-port `characterId/characterColor/
  playerType/displayName/connectCode`) and the tail metadata block
  (`startAt` ISO 8601, `lastFrame`). Cached by `(file, mtime, size)`.
- `ReplayStore` — owns the replay directory: `list()`, `delete`,
  `deleteOlderThan`, `deleteAll`, `totalSize`, `count`. All
  filesystem mutation goes through here so the activity has no
  scattered `File.delete` calls.
- `GeckoOverride` — swaps the netplay codeset for the playback
  codeset at launch. Slippi's gecko codes differ between live and
  replay modes (playback needs the frame-walk + savestate codes that
  netplay specifically *omits*); on PC this is handled by the
  separate Playback Dolphin binary, but we share one binary so the
  swap is done in Java before `Run()`.

**Activities:**

- `ReplayListActivity` — `RecyclerView` of rows from `ReplayStore`.
  Top-bar overflow has `Import .slp`, `Delete older than 7/30 days`,
  `Delete all` (all gated behind Material confirm dialogs).
  Per-row 3-dot menu has `Play`, `Delete`, `Share`. Long-press
  enters multi-select ActionMode. The `Share` action returns a
  `content://` URI through the FileProvider declared in
  `AndroidManifest.xml` (rooted at `<files>/dolphin/Slippi/Replays/`,
  paths file in `res/xml/file_provider_paths.xml`).
- `EmulationActivity` accepts `EXTRA_REPLAY_PATH`. When present, it
  writes `playback.json` via `ReplayConfig.writeNormal`, calls
  `NativeLibrary.SetSlippiInputPath(...)` with the absolute JSON
  path, and toggles the `ReplayHudView` visible. When absent it
  writes an empty JSON to neutralize any stale file from the prior
  session — belt-and-suspenders against the polling loop
  re-triggering playback in live mode.

**HUD** (`ReplayHudView`, layout `view_replay_hud.xml`):
Play/pause, ±5s jump, scrubbable `SeekBar` from `GAME_FIRST_FRAME`
(-123) to `latestFrame`, FFW toggle, "current / latest" readout.
30Hz polling of `NativeLibrary.GetReplayCurrentFrame` /
`GetReplayLatestFrame`. Shows "Loading…" while
`currentPlaybackFrame == INT_MIN` — that's the gap between
`Run()` and `g_playbackStatus->startThreads()`, which only fires
after Melee's Slippi mod issues `CMD_PREPARE_REPLAY` (a few
seconds in). `TouchControlOverlayView` is force-hidden in replay
mode — no virtual pad needed when you're just watching.

**JNI surface** (`Source/Android/jni/MainAndroid.cpp`):

- `SetSlippiInputPath(jstring)` / `ClearSlippiInputPath()` — assign
  `SConfig::m_strSlippiInput` under `s_host_identity_lock`. This is
  the *only* trigger needed; the C++ side polls the file thereafter.
- `GetReplayLatestFrame()` / `GetReplayCurrentFrame()` — read
  `g_playbackStatus->{latestFrame, currentPlaybackFrame}`. Return
  `INT_MIN` when `g_playbackStatus` is null (i.e. pre-BootCore).
- `SetReplayTargetFrame(jint)` — write `g_playbackStatus->targetFrameNum`;
  the seek thread polls it (~8ms) and resets after acting.
- `SetReplayJump(jboolean forward)` — sets `shouldJumpForward` or
  `shouldJumpBack`; same fire-and-forget contract.
- `SetReplaySpeedMode(jint)` — 0 = normal, 1 = hard FFW. Wraps
  `setHardFFW()`, which snapshots `SConfig::m_OCEnable/m_OCFactor`
  and toggles them to `(true, 4.0f)`. `EmulationActivity.onDestroy`
  force-clears speed mode 0 in replay mode so a force-kill mid-FFW
  doesn't persist OC settings to disk.

**C++ side delta** (deliberately minimal):

- `SlippiPlayback.cpp` constructor: the `#ifdef IS_PLAYBACK` gating
  `generateDenylist()` + `generateLegacyCodelist()` is now
  `#if defined(IS_PLAYBACK) || defined(ANDROID)`. Android compiles
  the netplay binary (no `IS_PLAYBACK`) but enters playback at
  runtime, so it needs the pre-3.0 codelist data too. Adds a tiny
  cold-init cost (~50–200ms) when `CEXISlippi` is first
  constructed; trivial vs the savestate thread spin-up that follows.

**Pitfalls (real ones we hit):**

- `g_playbackStatus` is constructed in `CEXISlippi::CEXISlippi()` and
  destroyed in `~CEXISlippi`. So between two replay sessions the
  state resets, but JNI getters must null-check — calling them
  before BootCore returns nonsense otherwise.
- `m_strSlippiInput` is in-memory only (not INI-backed), so we
  re-set it every `EmulationActivity.onCreate` rather than assume
  process state.
- `commandId` must change per launch. `ReplayConfig.writeNormal`
  uses `System.currentTimeMillis()` — sufficient at the human
  re-tap rate.
- The seek thread's pause/resume can cause brief OpenSLES audio
  underruns. Documented; tolerable on Thor.

## Raw stick input providers

Android's public `MotionEvent` axes are already normalized and, on
many handhelds, **saturated** before they reach the app — the device
firmware may clip past ~1/3 of physical stick travel. We work around
this with a small pluggable provider abstraction that lets each
device family expose its own unsaturated samples.

**Where it lives:**
`Source/Android/app/src/main/java/org/dolphinemu/dolphinemu/utils/`
- `RawStickInputProvider` — interface. Six required methods plus
  two `default` opt-in hooks for low-latency polling
  (`supportsBlockingWait()` / `waitForSnapshot(int timeoutMs)`); see
  **Latency tuning** above.
- `RawStickInputProviders` — selection registry. First provider
  whose `isAvailable()` returns `true` wins.
- `RawStickState` — uniform snapshot: per-stick X/Y in Android axis
  convention (X+ = right, Y/RZ+ = down), `[-1, 1]`.
- `NativeEvdevStickInputProvider` — reads `/dev/input/event*`
  directly via JNI. Only works on rooted / unusually permissive
  firmware; stock Android handhelds deny it.
- `OdinMappingInput` — Ayn Thor / Odin path. Binds to the vendor
  `com.odin.mapping` service and calls binder transaction `24`
  (`currentRawEvent()`), which returns four `double` lanes with the
  pre-saturation stick throw scaled to ±4096. Lane polarity is
  *inverted* relative to Android axes (right/down come back
  negative), which is why `updateFromRawArray` negates each value.
- `KnownUnsupportedHandheldProvider` — non-functional research stubs
  for retroid / ayaneo / anbernic / gpd. They sit in the selector
  list so device-family detection logs are useful; they always
  report unavailable.

**Adding a new provider** (the path a contributor with a different
handheld would walk):

1. Create a new `MyVendorRawInput.java` in
   `org/dolphinemu/dolphinemu/utils/` implementing
   `RawStickInputProvider`. Six methods:
   - `id()` — short stable string for logs / preferences.
   - `label()` — human-readable, shown in the launcher status line.
   - `isAvailable()` — cheap probe. For a vendor `Service`, use
     `PackageManager.resolveService`. For a `/dev/input` reader,
     attempt the `open` once and cache. **Must not** report
     available if the source is the same already-saturated
     `MotionEvent` data the app would otherwise get.
   - `start()` / `stop()` — lifecycle hooks called by
     `EmulationActivity`. Bind services, register listeners, open
     fds here; tear them down in `stop()`.
   - `snapshot()` — return a fresh `RawStickState` for the current
     frame, or `null` if no sample is ready. Called on the main
     thread every frame, so keep it lock-light. `OdinMappingInput`
     does an atomic copy under a single short `synchronized` block.
   - `keepPollingWhenUnavailable()` — `true` if the provider can
     re-acquire mid-session (service was unbound, fd hung up).
     `false` for the evdev provider, which is either there at
     startup or not at all.
2. Register it in `RawStickInputProviders.create()`. **Order
   matters** — the first provider whose `isAvailable()` returns
   true is chosen, so list device-specific providers above generic
   ones. The order today is: Odin/Thor vendor service → native
   evdev (privileged kernel access) → research stubs.
3. Convert to Android axis convention in your provider. Vendor
   payloads frequently have inverted polarity (positive = left
   instead of positive = right) or transposed XY; handle that
   inside the provider so consumers stay uniform.
4. The launcher's calibration wizard automatically uses whichever
   built-in/Bluetooth provider is selected. GC adapter controllers are
   excluded because their stick bytes should pass through unchanged. If
   your provider works, the live preview should jump past the firmware
   saturation point during the "roll the stick" capture phase.

**How the Odin/Thor binder contract was reverse-engineered** (for
contributors who want to do similar work on another vendor):

- `com.odin.mapping` ships with the Thor / Odin OS. `adb shell dumpsys package com.odin.mapping`
  reveals the exported service `com.ro.mapping.service.ApiService`
  with action `com.ro.mapping.action.MAPPING_SERVICE`.
- The AIDL interface descriptor is `com.ro.mapping.sdk.IServerApi`.
  Disassembling the vendor's SDK jar (or the system app itself with
  `apktool` + `jadx`) gives the transaction codes used in
  `OdinMappingInput`:
  - `2` = registerListener (vendor-scaled, saturated — kept for
    diagnostics only)
  - `3` = unregisterListener
  - `24` = currentRawEvent (the unsaturated payload we actually use)
- The `currentRawEvent()` return shape (`double[]`, first 4 lanes =
  L.x, L.y, R.x, R.y, scaled to ~±4096) is not documented anywhere;
  we discovered it by logging the array length + values on first
  poll (see `loggedRawEventShape` in `OdinMappingInput`). The same
  one-shot log strategy is a reasonable starting point on a new
  vendor service.
- Lane polarity inversion was caught the same way — by physically
  pushing the stick right and observing that lane 0 went negative.

If you have a handheld whose firmware saturates input, a similar
"is there a vendor mapping service / driver / sysfs file?" hunt is
the right first step before deciding raw evdev is the only option.

## Calibration + remap UI

- `CalibrationActivity` — single-screen wizard with a live
  `StickPreviewView` (raw + calibrated dots, captured-range box,
  octagonal gate). Per-stick edit state with a "touched" flag so a
  single Save commits both sticks when both have been adjusted.
- `ButtonMapActivity` — list-style remapper with color-coded GC
  glyphs. Listen-mode dialog intercepts keys via OnKeyListener
  (built-in pad) or polls `GCAdapter::GetLatestRawButtons` (adapter).
  Saves per-device; multi-bind is supported.
- Both wizards are reachable per-controller-port (built-in, adapter
  port 1-4) so each player in a 4-controller local match can tune
  their own controls independently.

## Known untested / probably-broken bits

- JIT memory allocation under Android's W^X — `MemoryUtil.cpp` still
  uses `PROT_READ|PROT_WRITE|PROT_EXEC` mmap. Works on app-private
  anonymous maps through Android 14; future versions may restrict.
- On-screen touch overlay has not had device QA yet. Use the
  launcher **Touch controls** link to edit layout, or build with
  `-PforceTouchControls=true` to force it visible on handhelds that
  also have built-in controls.
- First boot of a new game compiles ~hundreds of shaders (~5–10s on
  Adreno 740) before the first frame.
- Audio backend hasn't been latency-tuned.
- Linux build hosts: the gradle config hard-codes
  `aarch64-apple-darwin` in the rustup path resolution. Edit
  `Source/Android/app/build.gradle` around line ~32 if you're on
  Linux.

## What changed vs upstream Slippi/Ishiiruka

See `git log android-port` for the full series. Highlights:

- `Source/Android/` — completely rewritten: AGP 8.3.2, AndroidX,
  Kotlin-free Java launcher with one-screen Material 3 cards
  (`MainActivity`, `EmulationActivity`, `SlippiLoginActivity`,
  `CalibrationActivity`, `ButtonMapActivity`, `TouchOverlayActivity`).
- `CMakeLists.txt` — Slippi Rust extensions imported once at the top
  so the Android JNI build can link them (previously only DolphinWX
  did).
- `Source/Core/VideoCommon/PostProcessing.cpp` — Adreno GLSL ES
  parser-quirk workarounds (function-vs-macro for parameterless
  helpers, hoisted bicubic forward-decl, float literals).
- `Source/Core/VideoCommon/TextureDecoder_Generic.cpp` — added;
  gates the SSE-only decoder paths so ARM64 builds aren't missing a
  TU.
- `Source/Core/VideoBackends/Vulkan/VulkanContext.cpp` — gate BC/DXT
  texture format support on `features.textureCompressionBC` (fixes
  CMPR colored squares on Adreno).
- `Source/Core/VideoBackends/Vulkan/SwapChain.cpp` — drop swap chain
  depth to `minImageCount` for IMMEDIATE mode latency.
- `Source/Core/Common/StringUtil.cpp` — Android iconv can't open
  CP1252 / SJIS converters; `MinimalSJISToUTF8` and
  `MinimalUTF8ToSJIS` fallbacks keep game IDs and player names from
  going blank.
- `Source/Core/Common/FileUtil.cpp` — `GetSysDirectory()` on Android
  returns `<files>/Sys/` instead of the hardcoded
  `/sdcard/dolphin-emu/`.
- `Source/Core/HW/SI_DeviceGCController.cpp` +
  `Source/Core/NetPlayClient.cpp` — per-port `SI_PadOverride` lets
  the Android launcher push already-calibrated GC pad bytes
  straight into the SI layer, bypassing ControllerEmu's half-axis
  arithmetic (which doubled inputs in the touchscreen-device case).
- `Source/Core/InputCommon/GCAdapter_Android.cpp` — async
  `UsbRequest` read pipeline, big-core `sched_setaffinity`, fixed
  memcpy buffer bound that was overflowing the 37-byte payload,
  raw stick pass-through, and per-port atomic button remap.
- `Source/Android/app/src/main/java/.../utils/Java_GCAdapter.java` —
  pre-queued `UsbRequest`s, hot-plug intent filter, faster polling.
- `Source/Android/jni/MainAndroid.cpp` —
  `PinEmuThreadToPerformanceCores()`, JNI for
  `PerformanceHintManager`, raw evdev reader for the built-in pad.
- `Source/Core/PowerPC/JitArm64/*.cpp`, `Arm64Emitter.cpp` — emitter
  patches from `connoranastasio/Ishiiruka-rocknix` to make JitArm64
  build under modern Clang.
- `Externals/libpng/CMakeLists.txt` — `PNG_ARM_NEON_OPT=0` (no
  arm/*.c sources in this vendored copy of libpng).
- `Externals/SlippiRustExtensions` (submodule) — Android logger that
  installs a panic hook routing `__android_log_write` to the
  `SlippiRust` tag.
