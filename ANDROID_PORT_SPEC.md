# Atom Matrix Optical Link - Android port spec

For Claude Code. Android Studio, Kotlin, CameraX. Read all of it before writing code.

This REPLACES the earlier `ANDROID_PORT_SPEC.md`. That document described a
three-colour, never-off protocol (magenta sync). That protocol is gone. Do not
read the old spec for anything except history. Sections 1 and 6 here contradict
it on purpose.

Ground truth for behaviour is the Python source in this repo:
`atom_matrix_sender.py` (UIFlow2 MicroPython, runs on the Atom) and
`pc_receiver.py` (PC, OpenCV). This document explains the why. The Python is
the what. When they disagree, the Python wins and this document has a bug.

`HANDOFF.md` and `element14_blog_post.md` have the project history and the
list of things that failed on real hardware. Section 6 below repeats the ones
that matter for a port.

## 0. Scope

Required: an Android app that RECEIVES. Phone camera reads the Atom Matrix
(or any other correct sender) and shows the decoded text with telemetry.

Optional, second phase: a SEND mode that transmits the same protocol by
filling the phone screen with colour. Phone to phone, phone to PC receiver,
Atom to phone all interchangeable.

Not in scope: changing the protocol. If the port needs a protocol change to
work, stop and report why.

## 1. The protocol

### 1.1 Physical layer

One light source shows one colour at a time. On the Atom that is all 25 LEDs
the same colour behind a diffuser. On a phone sender it is a full-screen fill.

```
DARK   -> separator, between every bit
GREEN  -> one symbol
BLUE   -> the other symbol
```

One bit = DARK for `LINK_SPEED_MS`, then GREEN or BLUE for `LINK_SPEED_MS`.
Reference sender uses 250 ms per phase, so 500 ms per bit, about 2 bit/s.

`LINK_SPEED_MS` is a sender-only constant. The receiver has no timing
constant that must match the sender. It has rhythm BOUNDS (see 2.4) used only
to reject mains flicker and hand movement. It does not sample at expected
phase boundaries. Keep it that way.

Which colour means 1 is NOT defined. The receiver decodes both polarities in
parallel and the chunk CRC picks. Do not hardcode polarity.

The dark phase is deliberate. An older design kept the light always on and
used a third colour as separator. It was dropped because hue targets drift
with lighting and the third colour (magenta) sat next to red on the hue
wheel, so any warm object read as a symbol. Dark as separator gives framing
for free and makes the sender self-identifying: static objects never blink
out. The known cost is auto-exposure lag after a bright phase. The receiver
handles it with a relative brightness threshold (2.3), not an absolute one.

Reference sender colours, packed RGB: GREEN `(0,255,0)`, BLUE `(0,0,255)`,
DARK `(0,0,0)`. Brightness on the Atom is kept LOW (12 to 45 of 100). An
overdriven LED clips to white on camera and white has no hue.

### 1.2 Packet

Message is UTF-8, split into `CHUNK_SIZE = 8` byte chunks. Each chunk:

```
[idx][total][length][header_crc8][payload_crc8][payload 0..8 bytes]
  1B    1B     1B        1B            1B
```

Header is always 40 bits. `header_crc8` covers `[idx,total,length]`.
`payload_crc8` covers the payload bytes only. Bits go MSB first per byte.

Sender loops all chunks forever. Between chunks it holds DARK for `GAP_MS`
(1200 ms). No end-of-message signal. No retransmit request. The next lap is
the retransmit.

`CHUNK_SIZE` must match sender and receiver. The receiver uses it in the
header plausibility check (`length <= CHUNK_SIZE`).

### 1.3 CRC-8

Poly `0x07`, init 0, no reflection, no final XOR.

```kotlin
fun crc8(data: ByteArray): Int {
    var crc = 0
    for (b in data) {
        crc = crc xor (b.toInt() and 0xFF)
        repeat(8) {
            crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF
                  else (crc shl 1) and 0xFF
        }
    }
    return crc
}
```

Golden vectors, test these before anything else:

```
""            -> 0x00
[0x00]        -> 0x00
[0x01]        -> 0x07
"123456789"   -> 0xF4
[0,4,8]       -> 0x6C     (header of chunk 0 of a 4-chunk message, len 8)
"All your"    -> 0xDA
" to us"      -> 0x53
```

## 2. Receiver core

Pure Kotlin, no Android imports. Input is one HSV frame plus a timestamp in
ms. Output is a symbol per frame (`A`, `B`, `SYNC`, or null) and telemetry.
Everything below lives in a `core` module that unit tests can drive with
synthetic frames. CameraX wiring is a thin layer on top (section 3).

The pipeline, in order. Order matters. Each stage exists because a specific
thing failed without it.

```
frame (HSV, downscaled)
  -> FlickerMap      where is anything changing
  -> Shape filter    is that shape plausibly the device
  -> Tracker         persistent lock, needs rhythm + two hues to acquire
  -> BoxReader       once locked: read the box directly every frame
  -> ColorSplitter   learn the two colours, classify lit frames A/B
  -> Decoder x2      run-length, header search, one per polarity
  -> Assembler       verified vs unverified chunks
```

### 2.1 FlickerMap

Downscale frame 4x. Per pixel keep the last 10 values of
`saturation * value / 255`. Spread = max - min. Pixels with spread above
`FLICKER_MIN` (reference 40) form the flicker mask. Dilate 5x5, scale back
up. Candidate blobs may only come from inside this mask.

Detection is by CHANGE, never by appearance. Every version that detected by
appearance (saturated blob, right hue) and then checked blinking afterwards
failed: by the time the check ran the lock had moved to a printer logo.

### 2.2 Shape filter

Threshold the full frame on `saturation >= sat_min` (adaptive, floor 40,
reference 85 in good light) AND `value >= 42`, AND inside the flicker mask.
Morphological close 7x7. Contours. For each contour with area >= 60 px and
<= 12% of frame:

- aspect ratio of bounding box <= 1.9
- contour area / bounding box area >= 0.5

The second test is the one that matters. A diagonal cable has aspect 1.3
and fills 16% of its box.

Then read the blob: erode the contour mask by 20% of its short side, take
circular mean hue and mean sat/value of what is left. Score:
`2 * closeness_to_last_lock + 0.03 * sat + 0.01 * value`.

Once the ColorSplitter has a fit, a candidate whose hue is not within the
splitter's matching margin of either colour is dropped here. Before a fit
exists everything passes so bootstrap can happen.

### 2.3 Tracker

A lock is a persistent belief, not a per-frame decision.

- Acquire: a 40x40 px cell must be hit `ACQUIRE_HITS = 8` frames, show hues
  with a robust spread (15th to 85th percentile) >= 16, and have at least 2
  observed colour changes whose median interval sits in 120 to 900 ms. A red
  cable is one hue no matter how it flickers, so it never qualifies.
- Locked: only candidates within `max(55, 0.9 * lock_size)` px of the lock
  are considered. Position smoothed 0.6/0.4, size 0.7/0.3.
- Move: a different location needs `MOVE_HITS = 24` to steal the lock.
- Lost: nothing near for `LOST_MS = 2500` and the lock is stale. Full
  re-acquire (reset splitter, reader, activity gate) after 6000 ms.

The lock is kept alive by the BoxReader reporting lit (`touch()`), not by a
contour winning. This matters: during dark phases there is no contour.

### 2.4 BoxReader

Once locked, bits come from here and contours are ignored for reading.

Every frame, on the locked box:

1. Crop 20% off each side.
2. Take the brightest 25% of pixels by value. Mean value = `vtop`.
3. Keep the last 60 `vtop` values (about 2 s at 30 fps, several bits).
4. `swing = max - min` of that history. If fewer than 8 samples or
   `swing < 25`: not settled, return null.
5. `lit = vtop > min + 0.4 * swing`.
6. If lit: hue = circular mean hue of those same brightest pixels, using only
   those with sat > 40 (fall back to all of them if fewer than 3).

Dark or lit is decided by brightness against the swing seen recently, never
by hue. Colour is read from the brightest pixels because those are the LEDs,
not the surface they illuminate and not the cable next to them. The reading
does not have to be the "true" LED hue. It has to be the SAME reading every
frame the LED is in that state.

Before this existed the receiver re-detected a contour every frame and read
the bit from whichever won. At 1.5 m that was a few pixels, the close op
fused it with the cable the LED illuminates, and the erosion kept a
different subset each frame. Same phase, different hue every frame.

### 2.5 ColorSplitter

Never told what green or blue look like. Learns from the lit readings at the
locked spot.

- Collect hue samples. Bootstrap gate: until a fit exists, a sample must be
  within 45 of hue 60 or hue 120 (OpenCV 0..179 scale). This only stops red
  from seeding the fit. After a fit exists it is not used.
- At `MIN_SAMPLES = 20`, fit: rotate samples around their circular mean so
  the 0/180 wrap cannot split a cluster, sort, threshold at the widest gap.
  Refit once with the 20% of samples furthest from their group centre
  dropped. Require centre separation >= 12 or keep collecting.
- The two groups are `A` (below threshold) and `B` (above). Unnamed.
- Learning after fit: a new sample is admitted ONLY if within
  `LEARN_MARGIN = 10` (absolute) of a centre. After `FREEZE_AFTER = 120`
  admitted samples the fit is frozen and stops adapting.
- Classify a lit frame: nearest group, always. Never null for a lit frame.
- Disown: if 32 consecutive BITS (not frames) come out identical, the fit is
  wrong. Drop it, keep the last 20 samples, unfreeze, refit.
- Refit on a verified chunk is not needed; freeze happens on stability.

Why the tight learning margin and the auto-freeze: the previous version let
a cluster walk. Blue read at 152, a reflection at 170 was within margin,
then the cable at hue 4 was within margin of 170, and in a minute the "blue"
cluster sat at 4 while real blue frames were being rejected. And it never
froze because freezing waited for a verified chunk, which needs stable
colours first.

Hue gap between the two learned colours has been measured as low as 20 on
real hardware against the 60 emitted. Do not assume a big gap anywhere.

### 2.6 Decoder

Two instances, one per polarity (`A=1`, `A=0`). Feed both every frame.

- Majority vote over the last 5 symbols, need 3 agreeing, to change state.
- Each time the state changes, the finished run emits one bit if it was a
  data state. SYNC runs emit nothing.
- Keep a sliding 40 bit window. On every new bit, parse it as a header:
  `plausible = length <= CHUNK_SIZE && total != 0 && idx < total`,
  `crc_ok = crc8([idx,total,length]) == header_crc`. If both, lock the
  header and collect `length * 8` more bits.
- When the payload is complete, check `payload_crc8`. Emit
  `(ok, idx, total, payload)` either way. Reset the window.
- Bits older than `STALE_MS = 6000` with no new bit: clear.

A 1-byte header CRC means a random window passes about 1 in 256 tries, and a
new window is tested on every bit. False headers are expected, not
exceptional. That is the Assembler's problem.

### 2.7 Assembler

- Verified chunks and unverified (payload CRC failed) chunks in separate
  maps. Verified always wins at a position and is never downgraded.
- Only a VERIFIED chunk may set the message length (`total`).
- Changing an established `total` needs TWO verified chunks agreeing. A
  single false header claiming `total=2` once wiped a finished message.
- Unverified chunks may only fill a slot in a structure a verified chunk
  already established. Never resize or reset.
- Display: verified in green, unverified in amber, missing as `?` blocks.
  A partly wrong chunk you can read beats nothing.

### 2.8 Activity gate

Bits are only passed to the decoders when the locked spot has shown at
least 2 distinct states (SYNC counts) inside a 4 s window, or a brightness
swing >= 30 over its last 40 readings. Stops a static object that somehow
got a lock from feeding garbage.

## 3. Android integration

### 3.1 Camera

- CameraX `ImageAnalysis`, `STRATEGY_KEEP_ONLY_LATEST`, back camera.
- Target resolution 640x480 or the nearest the device offers. The reference
  works at that. Higher is slower and gains nothing.
- Input is `YUV_420_888`. Convert to HSV yourself, on the analyzer thread.
  Do NOT round trip through a Bitmap per frame. A direct YUV to HSV per
  pixel routine, or YUV to RGB then RGB to HSV, on a 4x downscaled copy for
  the flicker map and full res only inside the locked box. Hue must be on
  the OpenCV 0..179 scale so the reference constants transfer.
- Lock auto exposure and auto white balance once locked
  (`CONTROL_AE_LOCK`, `CONTROL_AWB_LOCK` via Camera2 interop). Not required
  for correctness, the relative brightness threshold copes, but it removes
  the exposure hunting after each dark phase and it stabilises hue. Expose
  as a toggle, default on.
- Torch off. Obviously.
- 30 fps. If the device delivers less, everything still works, the vote
  window just spans more time. If it delivers a lot less than 15, warn on
  the HUD.
- Keep the screen on while receiving.

OpenCV for Android is allowed but not required. The reference uses it for
contours, morphology, HSV conversion. A pure Kotlin implementation of
threshold + connected components + a 7x7 close on a 640x480 frame is fine
at 30 fps. Pick one and say which in the README.

### 3.2 Threading

Analyzer thread does: YUV -> HSV, core pipeline, produce a `FrameResult`.
Post the result to the UI. No allocation churn per frame: reuse buffers.
Profile on a mid-range phone, not a flagship.

### 3.3 Send mode (phase 2)

- Full-screen Compose surface, no chrome over the colour area. Anything
  drawn over the transmit area becomes part of the blob on the receiver.
- Colours: GREEN `#00FF00`, BLUE `#0000FF`, DARK `#000000`. Screen at max
  brightness while sending, but expose a brightness slider: an OLED at max
  clips to white on cheap cameras exactly like the LEDs do.
- Timing with a coroutine and `delay()`; jitter of tens of ms is fine, the
  receiver is self-clocking.
- Text input, `LINK_SPEED_MS` stepper (100..1000, default 250), stop button.
  `CHUNK_SIZE` fixed at 8, not exposed.
- Loop forever until stopped. That is correct behaviour.

## 4. UI

Receive screen, top to bottom:

- Camera preview with the lock box drawn on it. Colour of the box = current
  symbol (green/blue for A/B, grey for SYNC, dashed while acquiring).
- The decoded message, big, monospace. Verified chunks normal, unverified
  chunks amber, missing chunks as `????????`. This is the point of the app.
- Progress `n/total` and a bar.
- Telemetry, small, monospace, same fields as the PC HUD:

```
LINK OPTICAL  LOCKED  fps 29
RATE 505 ms/bit
BOX v:159 swing:98  colours 152|47 gap74 FROZEN refit0
SIGNAL A  states 3
SYMBOLS A:121 B:67 SYNC:162  balance 36%
BUFFER 19/40  bits 19  hdr_tries 0  chunk_tries 0
LAST HDR idx 0 total 4 len 8  plausible yes  crc yes
```

Every one of those fields has caught a real bug. Keep all of them.
`balance` near 50% is healthy. `hdr_tries` climbing with `chunk_tries` at 0
means a bit is being eaten somewhere. `plausible no` with garbage numbers
means misalignment.

- Debug toggle: raw bit stream and ASCII at current alignment. Default off.
- Buttons: re-acquire (full reset), AE/AWB lock toggle, camera flip.

## 5. Testing

Do this before touching the camera. The PC receiver was built the same way
and it caught most bugs before hardware.

### 5.1 Unit tests, pure core

1. `crc8` against the golden vectors in 1.3. First test in the project.
2. Packet builder round trip: build chunks from a message, feed the bits
   into `Decoder` + `Assembler`, get the message back. Both polarities.
3. `ColorSplitter`: two noisy clusters at 47 and 150 (this straddles the
   0/180 wrap once rotated, that is the point), expect a fit, expect nearest
   classification, expect freeze after 120 admitted samples, expect a
   sample at hue 4 to be rejected for learning once the fit exists.
4. `ColorSplitter` wrap test: clusters at 170 and 20. Must fit as two
   groups 30 apart, not 150 apart.
5. `Decoder`: a header with `idx=0,total=4,len=8` contains 13 identical
   bits in a row. Must decode. Any "runs of identical bits are noise"
   heuristic must be on bits AND above 13.
6. `Assembler`: a verified 4-chunk message, then a false header with
   `total=2`. Message must survive. Then two verified chunks with
   `total=2`. Message must switch.

### 5.2 Synthetic frame harness

Port `/tmp/smoke.py` from the handoff (or reconstruct it: it is 40 lines).
Generate 60x60 HSV frames: a background patch at hue 10 / value 70, a 20x20
LED patch at hue 47 or 150 / value 160 on lit frames, a red strip at hue 3
that brightens with the LED (a reflection), noise of +-6 hue and +-15
value. 8 frames dark, 8 frames lit per bit, 36 dark between chunks. Run the
message twice. Expect every chunk verified and the fit at roughly `150|47`.

Add noise sweeps: misclassification 2% and 5%, dropped frames 5%, a
brightness ramp simulating auto exposure recovery over the first 4 frames of
every lit phase. Report the verified chunk rate per setting. Nothing goes on
a phone until these pass.

### 5.3 Hardware

Test in this order, and record the HUD:

1. Atom -> phone, 30 cm, evening light, brightness 12.
2. Same at 1.5 m.
3. Same in daylight, brightness 45.
4. Phone sender -> PC `pc_receiver.py` unmodified. This proves the sender.
5. Phone -> phone.
6. Cluttered scene with a red cable next to the device. This is the known
   recurring offender.

## 6. Pitfalls. Read before implementing, not after

Most of these look obviously correct on paper. Every one was tried on real
hardware and failed.

- Spatial patterns inside the matrix (corner reference, separator row, QR
  style finder). The diffuser smears everything. A separator row that is
  black in a render is barely visible in a photo. A version tuned on
  renders passed every test and rejected the real device.
- Fixed hue targets. Green is not at 60 on a camera. Measured 88, another
  day 84. Blue 148, another day 104. Anything constant works in the evening
  and fails at noon.
- Two independently adapting hue targets. They drift together and collapse.
- One adapting fit with a wide learning margin and no freeze. Same collapse,
  slower, across the hue wrap. See 2.5.
- Reporting an off-colour lit frame as DARK. Ate blue bits as separators,
  shifted every header by one bit, symbols looked healthy the whole time.
- Reading the bit from a fresh contour every frame once locked. See 2.4.
- Detecting by appearance and blink-checking afterwards. Lock has already
  moved by then.
- Magenta as a data colour. 30 degrees from red. Any warm object reads as
  a symbol. Also hue 0..35 in general: skin lives there.
- Trusting an unverified chunk to set message length.
- Counting identical-symbol runs in frames instead of bits. Each bit spans
  several frames, so it fired constantly.
- Assuming runs of identical bits are rare. A real header has 13.
- Sharing one constant between "gap between hue clusters" and "distance
  between cluster centres". Different quantities. Caused seed-dependent
  intermittent failures.
- Repeating each raw bit 3x for redundancy. Worse than 1x. A single lost
  run shifts alignment for the whole group. Chunk CRC + loop forever beats
  it.
- RGB vs BGR channel order. Verified a colour in one convention, typed it
  into code expecting the other. Print the hue of each target colour through
  the actual conversion you use before writing anything that depends on it.
  On Android also check the YUV plane order and pixel stride on the test
  device; some devices deliver NV21, some I420.
- Auto exposure after a bright phase. Reason the separator is judged
  against recent swing, not an absolute level. Do not "simplify" to a fixed
  threshold.
- Synthetic scenes that lie. Rendering the dark phase as pure black or the
  LED as a perfect square passes tests and fails on glass. Put a reflection,
  noise, and a warm object in every synthetic frame.

## 7. Delivery

Phase 1, required:

- `core/` Kotlin module: crc, packet, FlickerMap, Tracker, BoxReader,
  ColorSplitter, Decoder, Assembler, ActivityGate. No Android imports.
- Unit tests 5.1 and synthetic harness 5.2 passing.
- `app/` with CameraX receive screen and HUD per section 4.
- README: build, which OpenCV choice was made and why, how to run the tests,
  the HUD field glossary copied from section 4.

Phase 2, optional:

- Send mode per 3.3.
- Hardware tests 5.3 items 4 and 5.

Report the HUD numbers from hardware tests in the PR. Screenshots, not
prose. If a stage in section 2 is changed from the reference, the PR says
which, why, and what the synthetic harness did before and after.

## 8. Reference constants

From `pc_receiver.py`. Each is commented there with the failure it prevents.
Start with these. Change only with the HUD as evidence.

```
Frame            640x480, 30 fps target
FlickerMap       scale 0.25, history 10, FLICKER_MIN 40, dilate 5x5
Shape            SAT floor 40 / ref 85, VAL_MIN 42, close 7x7,
                 MIN_BLOB_AREA 60, MAX_AREA_FRAC 0.12,
                 aspect <= 1.9, extent >= 0.5, erode 20% of short side
Tracker          ACQUIRE_HITS 8, MOVE_HITS 24, LOST_MS 2500, cell 40 px,
                 PERIOD 120..900 ms, MIN_PERIODS 3, TWO_COLOUR_SPREAD 16,
                 reacquire 6000 ms
BoxReader        HIST 60, MIN_SWING 25, DARK_FRAC 0.4, CROP 0.2,
                 TOP_FRAC 0.25, HUE_MIN_SAT 40
ColorSplitter    MIN_SAMPLES 20, MIN_CENTRE_SEP 12, bootstrap 45 around
                 60/120, LEARN_MARGIN 10, FREEZE_AFTER 120, MAX_RUN 32 bits
Decoder          VOTE_WINDOW 5, VOTE_MIN 3, header 40 bits, STALE_MS 6000
Activity         window 4000 ms, min states 2, BLINK_MIN_SWING 30
Sender           LINK_SPEED_MS 250, GAP_MS 1200, CHUNK_SIZE 8,
                 brightness 12..45, hard cap 70
```
