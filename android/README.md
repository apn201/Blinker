# Blinker: Atom Matrix Optical Link, Android port

A bonus. The project itself is the sender and PC receiver in the repo root (see
[`../README.md`](../README.md)); this app was added after the deadline was extended.

An Android port of the PC receiver in `../pc_receiver.py`. The phone camera watches a
blinking light source (an M5Stack Atom Matrix, or any sender that speaks the protocol)
and reconstructs the transmitted text, with the same live telemetry as the PC HUD.

Ground truth for behaviour is the Python (`../pc_receiver.py`, `../atom_matrix_sender.py`)
and `../ANDROID_PORT_SPEC.md`. Where the spec's constant table and the Python disagree,
the Python wins (see *Deviations* below).

## Status

- **Phase 1 (receiver): done and tested.** Pure-Kotlin `core` (CRC, packet, FlickerMap,
  Tracker, BoxReader, ColorSplitter, Decoder, Assembler, ActivityGate) with unit tests and
  a synthetic-frame harness, plus a CameraX receive screen and HUD.
- **Phase 2 (send mode): not built yet.** The spec describes it (section 3.3); it is an
  optional second phase.

## Module layout

```
core/   pure Kotlin, NO Android imports. The whole receiver pipeline + protocol.
        Unit tests (spec 5.1) and the synthetic frame harness (spec 5.2) live here.
app/    Android application: CameraX ImageAnalysis -> YUV->HSV -> core.Receiver -> HUD.
```

The `core` module is a plain `kotlin("jvm")` library on purpose: it has no Android
dependencies, so its tests run on any JDK and the correctness-critical logic is verified
without a device or emulator.

## Building

In Android Studio, `File → Open…` and select this `android` folder (not the parent
`Blinker` folder, which only holds the Python). Then Build/Run as usual. No JDK or Gradle
settings to change. The toolchain is AGP 9.3.2 / Gradle 9.7.1 / compileSdk 36, which runs
on Android Studio's bundled JBR (Java 25).

Toolchain notes: AGP 9 has built-in Kotlin support, so the `:app` module deliberately
does *not* apply `org.jetbrains.kotlin.android`. Doing so clashes with the `kotlin`
extension AGP registers itself. `:core` is a plain Kotlin/JVM library and uses
`kotlin("jvm")`. Both target JVM 17 bytecode via `compilerOptions`, with no separate JDK
toolchain required.

From the command line (`android/` directory):

```bash
./gradlew :core:test            # run the core unit tests + synthetic harness
./gradlew :app:assembleDebug    # build the debug APK
```

If `JAVA_HOME` on your shell points at an old JDK, use Studio's JBR explicitly:

```bash
export JAVA_HOME="/path/to/Android Studio/jbr"
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Install it with
`adb install -r app/build/outputs/apk/debug/app-debug.apk`, or open the project in Android
Studio and Run.

`local.properties` points `sdk.dir` at the Android SDK. It is machine-specific and should
not be committed.

## Running the tests

```bash
./gradlew :core:test
```

- `CoreTest` (spec 5.1): CRC golden vectors, packet round-trip (both polarities),
  ColorSplitter (fit / nearest classify / freeze / post-fit outlier rejection / hue wrap),
  Decoder 13-identical-bits, MAX_RUN-in-bits, Assembler message-length defence.
- `PerfTest`, the throughput guard: the whole pipeline at the real 480×640 working size.
  Currently 13.9 ms/frame (~72 fps) on a desktop JVM; the test fails above 25 ms/frame,
  so reintroducing per-frame allocation or a full-frame sweep gets caught.
- `HarnessTest` (spec 5.2): the full image pipeline recovers the message from 60×60
  synthetic frames carrying a warm decoy (a red strip that brightens with the LED), a noisy
  background and per-pixel noise. Sweeps: 2% and 5% per-frame misclassification, 5% dropped
  frames, and an auto-exposure recovery ramp. All recover the full message; the clean scene
  learns colours `150|47 gap77 LOCKED`.

## Image processing: pure Kotlin, not OpenCV

The spec allows either OpenCV-for-Android or a pure-Kotlin implementation. This port is
pure Kotlin (`core/ImageOps.kt`): threshold, separable box dilate/erode (a rectangular
structuring element is separable, so morphology is O(w·h·k)), morphological close, and
8-connected components. Reasons: `core` stays free of Android/OpenCV so its tests run on a
bare JVM; there is no native-library packaging; and at ~640×480 the cost is acceptable
(measure on a mid-range phone, not a flagship; spec 3.2).

## Performance

Frame rate matters for correctness, not just smoothness: the sender holds each phase for
`LINK_SPEED_MS` (250 ms) and the decoder needs 3 agreeing frames out of a 5-frame window to
change state. At 30 fps that is ~7 frames per phase; below ~15 fps it gets marginal, which
is why the HUD warns (`!LOW`) under 15 and shows `RES` and `ms/frame` directly.

What this cost and where it went (all measured, not estimated):

| fix | effect |
|---|---|
| Bulk-copy YUV planes into byte arrays instead of ~921k `ByteBuffer.get(index)` calls per frame | YUV→HSV 275 ms → ~5 ms |
| Reuse every large buffer; no per-frame allocation anywhere in the pipeline | ended continuous GC (was `mark compact GC freed 6912KB` every frame) |
| Histogram instead of sorting a boxed `Integer` list in `peakSat` and `BoxReader` | `peakSat` ~540 ms → 1 ms |
| Confine threshold/close/connected-components to the flickering region | detect is proportional to the blinking area, not the frame |

**Testing on an emulator is misleading.** On a Pixel emulator with Android Studio's
profiler/layout-inspector attached (a JVMTI agent gets loaded into the app process), the
same build reports ~3 fps while its own instrumented stages sum to ~15 ms. Measure on a
real device, and close the profiler/Layout Inspector before judging frame rate.

## HUD field glossary (spec section 4)

```
LINK OPTICAL  LOCKED  fps 29
RATE 505 ms/bit (~1.98 bit/s)
BOX v:159 swing:98  colours 152|47 gap74 FROZEN refit0
SIGNAL A  states 3
SYMBOLS A:121 B:67 SYNC:162  balance 36%
BUFFER 19/40  bits 19  hdr_tries 0  chunk_tries 0
LAST HDR idx 0 total 4 len 8  plausible yes  crc yes
PROGRESS 3/4  chunks_ok 7/9 (78%)  pol A1
```

- `colours X|Y gapN`: the two learned colours and the gap between them. On real hardware
  the gap has been as low as 20 (the diffuser blends white in); `learning (N samples)`
  forever means the fit thresholds are too strict. `FROZEN` = the fit stopped adapting after
  a chunk verified.
- `balance`: near 50% is healthy; heavily lopsided means the split is wrong.
- `hdr_tries` climbing with `chunk_tries` at 0: bits are being read but never form a
  valid header. A 1-byte header CRC passes ~1 in 256 random windows, and a new window is
  tested every bit, so false headers are expected, not exceptional.
- `LAST HDR`: `plausible no` with garbage numbers means the bit stream is misaligned;
  `plausible yes crc no` means it is close and noisy.
- The lock box is coloured by the current symbol (green A, blue B, amber SYNC, grey/dashed
  while acquiring). The decoded message is drawn verified (green) / unverified (amber) /
  missing (grey), so a partly wrong chunk you can read beats nothing.
- `RES` / `ms per frame`: the working frame size and how long one frame took end to end.

The HUD is laid out for a portrait phone: all sizes are density-scaled, the telemetry text
auto-shrinks until the longest line fits the screen width, each panel is sized to the text
it contains (so no line lands half on the panel and half on the camera image), the message
wraps instead of running off the right edge, and the status bar and control bar are kept
clear via window insets.

## Camera notes (spec section 3)

- CameraX **1.6.2**, `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, `ResolutionSelector`
  targeting 640×480, back camera. 1.6.x is required rather than cosmetic: CameraX 1.3.x
  ships native libs that are not 16 KB page-size aligned, which makes Android 15+ show an
  "app isn't 16 KB compatible" dialog. Every packaged `.so` reports `LOAD p_align=0x4000`;
  check any build with `python tools/check_16kb_alignment.py app/build/outputs/apk/debug/app-debug.apk`.
- Whatever resolution the camera actually delivers, the conversion subsamples so the
  working frame's long side is ~640 px (`YuvToHsv.TARGET_LONG_SIDE`). The core's constants
  are all in pixels and tuned around 640×480, and cameras do not reliably honour a
  requested analysis size.
- `YUV_420_888` is converted to HSV (OpenCV 0..179 hue scale) on the analyzer thread,
  reading pixel/row strides explicitly so it works on both I420 and NV12/NV21 devices. The
  frame is rotated upright in the same pass so the core's pixel-space constants stay valid
  and the HUD box maps with a plain fill-centre scale.
- AE/AWB are locked once the receiver has a lock (toggle in the bottom bar, default on),
  via Camera2 interop. Not required for correctness (the relative-brightness threshold
  copes), but it stops exposure hunting after each dark phase.
- The screen is kept on while receiving. Torch off.
- Controls: Re-acquire (full reset), AE/AWB toggle, Flip camera.

## Deviations from the Python reference

All intentional; the synthetic harness passes with them.

1. **Pure-Kotlin image ops instead of OpenCV** (see above). Blob area is the connected
   pixel count and extent is `area / bounding-box area`, both pixel-based, against
   OpenCV's polygon `contourArea`. Equivalent for solid blobs; may differ slightly on ragged edges.
2. **FlickerMap** downscales the S and V planes and averages them, rather than downscaling
   BGR and then converting to HSV. The metric (`s·v/255`) and threshold are unchanged.
3. **Constants follow `pc_receiver.py`, not the spec's section 8 table**, where they
   differ: `FLICKER_MIN` 26 (spec says 40), `STALE_MS` 8000 (spec 6000), adaptive SAT floor
   32 (spec 40), `BLOB_MIN_SAT` 85, blob-area floor uses `FLICKER_MIN_AREA` 150,
   `BLINK_MIN_SWING` 22 (spec 30). The spec states the Python wins on disagreement.

Nothing in the protocol was changed.
