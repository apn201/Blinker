# Atom Matrix Optical Link: project handoff

Read this before changing anything. Section 5 is the important part: it lists
approaches that were tried and failed on real hardware, and why. Several of
them look obviously correct on paper, which is exactly why they keep getting
re-invented and keep wasting days.

## 1. What this is

A one-way optical data link. An M5Stack Atom Matrix (5x5 RGB LED, behind a
diffuser) blinks colours. A webcam watches it. `pc_receiver.py` finds the
device in the frame, reads the colours, and reconstructs text.

Entry for element14's Project14 "Make a Connection". Write-up:
https://community.element14.com/challenges-projects/project14/b/make-a-connection/posts/blink_2d00_optical_2d00_one_2d00_way_2d00_communication_2d00_m5stack_2d00_atom_2d00_matrix

Status: works, imperfectly. Full messages decode at about 1.5 m indoors. It
still loses lock or misreads occasionally in cluttered scenes. It is not
finished.

The project is the two Python files. They are the reference implementation:

| file | runs on | notes |
|---|---|---|
| `atom_matrix_sender.py` | the Atom, via UIFlow2 MicroPython | paste into the UIFlow2 **code editor**, not Blockly |
| `pc_receiver.py` | PC | `pip install opencv-python numpy`, then `python pc_receiver.py` |

Bonus, added after the deadline was extended:

| file | runs on | notes |
|---|---|---|
| `android/` | Android phone | Kotlin/CameraX port of `pc_receiver.py`, see `android/README.md`. Where it disagrees with the Python, the Python wins. |

**Re-push the sender to the DEVICE whenever it changes.** Downloading it to the
PC does nothing. This has caused confusion more than once. A screenshot showing
old behaviour usually means the device is still running the old code.

## 2. The protocol

Whole matrix shows ONE colour at a time. There is no spatial pattern to read.

```
dark   -> bit separator (framing)
green  -> one symbol
blue   -> the other symbol
```

Each bit: dark for `LINK_SPEED_MS`, then a colour for `LINK_SPEED_MS`.

Packet (a "chunk"), 8 bytes of payload by default:

```
[idx][total][length][header crc8][payload crc8][payload bytes...]
```

CRC-8, poly 0x07, no reflection, no final XOR. Header is a fixed 40 bits. The
sender loops the whole message forever; there is no end signal and no
retransmit request, because there is no return channel.

Which colour means 1 is not defined by the protocol. The receiver decodes
both polarities in parallel and lets the chunk CRC decide. Do not "fix" this by
hardcoding it.

## 3. Receiver pipeline

Order matters. Each stage exists because of a specific failure.

1. **FlickerMap**: per-pixel colour change over ~10 frames, downscaled 4x.
   Detection happens only inside regions that are actually changing. Static
   objects never become candidates at all.
2. **Shape filter**: aspect ratio ≤ 1.9 AND the blob must fill ≥ 50% of its
   bounding box. The extent test is the one that matters: a *diagonal* cable
   has a bounding box of aspect ~1.31 (passes an aspect test easily) but fills
   only ~16% of it.
3. **Colour screen**: once `ColorSplitter` has learned the two colours, a blob
   must match one of them to be a candidate at all.
4. **Tracker**: a persistent lock, not a per-frame decision. Once locked, only
   candidates near the lock are considered. Acquiring needs 8 frames of
   agreement *and* two distinct hues at that spot *and* a blink rhythm inside
   120–900 ms. Moving the lock needs 24.
5. **ColorSplitter**: learns the two colours from the transmitter itself,
   unnamed ("A"/"B"). Splits the observed hue distribution at its widest gap.
   Freezes once a chunk verifies. Disowns its own fit after 32 identical bits
   in a row.
6. **Decoder**: run-length over symbols, sliding 40-bit header search.
7. **Assembler**: verified and unverified chunks kept separately.

## 4. Live telemetry (read this before debugging anything)

The HUD exists because guessing wasted enormous time. Every number on it has
caught at least one real bug.

```
BLOB hue:88 sat:117 v:190 flicker_px:5648  colours 84|104 gap20 LOCKED
SYMBOLS A:1442 B:1904 SYNC:3200  balance:43%
BUFFER bits:40/40 total_bits:807 hdr_tries:225 chunk_tries:5
LAST HDR idx:0 total:4 len:8 plausible:True crc:True
```

How to read it:

- `colours X|Y gapN`: the learned pair. On real hardware this gap has been
  measured as low as 20, not the 60 the sender emits; the diffuser blends
  white in and pulls the hues together. `learning (N samples)` forever means
  the gap thresholds are too strict.
- `balance`: near 50% is healthy. Heavily lopsided means the split is wrong
  even if it passed the gap test.
- `hdr_tries`: climbing fast with `chunk_tries` at 0 means bits are being read
  but never form a valid header. A 1-byte header CRC means a random window
  passes about 1 in 256 tries, and a new window is tested every bit, so false
  headers are expected, not exceptional.
- `LAST HDR`: `plausible:False` with garbage numbers means the bit stream is
  misaligned. `plausible:True crc:False` means it's close and noisy.

Console prints per-chunk results, including the text of failed chunks.

## 5. Failed approaches (do not re-try these without reading why)

**Spatial patterns inside the matrix** (reference band + dark separator row,
corner markers, QR-style finder). Repeatedly attractive, repeatedly broken.
The diffuser smears everything: a separator row that is pitch black in a
synthetic render is barely visible in a photo. A version tuned on rendered
images rejected the actual device while passing every test. Single-LED corners
are worse still: camera blur bleeds neighbouring LEDs into them until the
"reference" is mostly reading the data colour.

**Fixed hue targets.** The camera does not render green at 60. Measured on one
real camera: green at 88, blue at 148. On another day, 84 and 104. Anything
that compares against a constant will work in the evening and fail in daylight.

**Two independently adapting hue targets.** They drift toward each other and
collapse: a misread pulls one target toward the other colour, which causes more
misreads. Observed live: the target slid from 150 to 131 and then onto a shirt print.
The current model learns both together and freezes once data flows.

**On/off keying (light off = 0).** Auto-exposure ruins it: right after a bright
flash, an "off" LED still reads bright, biasing every 0 toward 1.

**Blink-check as a post-filter.** Detecting by appearance and then rejecting
impostors afterwards does not work: by the time the check runs, the lock has
already moved. Flicker has to come *first*, at detection.

**Trusting any chunk to define message length.** A false header claiming
`total=2` wiped a verified message and reset progress to 0/2. Only a
payload-CRC-verified chunk may set the length, and changing it needs two
agreeing chunks.

**Counting identical-symbol runs in frames rather than bits.** Each bit spans
several frames, so this fired constantly and destroyed good colour fits
(`refit128` in the logs).

**Assuming runs of identical bits are rare.** A header with `idx=0, total=4`
legitimately contains 13 consecutive identical bits. A limit of 14 tripped
on valid data.

**One constant for two different measurements.** The empty void *between* hue
clusters and the distance between cluster *centres* are not the same quantity.
Sharing a threshold caused intermittent, seed-dependent failures.

## 6. Known-good settings

Sender: `LINK_SPEED_MS = 250` or `350` (both tested; faster starts breaking up),
`BRIGHTNESS = 12`–`45` (raise in bright daylight; hard cap 70, above that can
damage the device), `CHUNK_SIZE = 8`.

Receiver: leave the constants alone unless the telemetry says otherwise. They
are commented with the specific failure each one prevents.

## 7. Open problems

- **Occasional lock loss in cluttered scenes.** Improved a lot but not solved.
  The orange cable on the desk is the recurring offender.
- **Chunk CRC failures** still happen at a low rate. Partial text is displayed
  as unverified (amber) and upgraded to verified (green) when a clean copy
  arrives, so this degrades rather than fails.
- **Very washed-out conditions** (saturation below ~40% of normal) lose the
  blob entirely.
- **Sharp close focus**, where individual LEDs resolve as separate dots rather
  than one blob, was never fully handled.
- **Range is capped by the detector's pixel thresholds.** At distance
  the transmitter is a small dot, and it is rejected before any behaviour test
  runs: `find_and_read` needs `area >= FLICKER_MIN_AREA` (150 px^2) and both
  sides `>= 10` px, and `BLOB_MIN_SAT = 85` is a hard floor that never relaxes
  (unlike the mask floor, which `SatAdapter` adapts). `FlickerMap` measures at
  quarter scale, so a small dot's flicker metric is diluted by the dark pixels
  averaged in with it. On a 640×480 webcam (no capture resolution is
  requested) with a ~60° lens, a 24 mm matrix is about 14 px at 1 m and 9 px
  at 1.5 m, which is where the wall is. Measure your own camera before
  trusting those numbers.

  The shape filter is NOT the problem here: a dot is square and fills its
  bounding box, so it passes both checks. Those exist to reject a large
  diagonal cable and can stay.

  Untested idea: apply the shape checks only above ~14 px, lower the size and
  saturation floors for small candidates, and let the tracker's existing
  two-hue + rhythm test do the rejecting. Requesting a higher capture
  resolution would buy distance for free, but every constant here is in pixels
  and tuned for 640×480, so they would need normalising to frame width first.
  Expect more junk candidates (router LEDs, glints) and therefore slower
  acquisition. Not tested on hardware, so do not assume it works.

## 8. Ideas not yet tried

- A distinctive rhythm on the sync/dark phase (e.g. `110110110` rather than a
  plain alternation) so the transmitter can be identified by temporal signature
  alone. My own idea, and probably the strongest one left.
- Forward error correction so a corrupted chunk can be repaired rather than
  only detected.
- A wider header CRC to cut the false-header rate, which is currently the
  main source of spurious chunk attempts.
- Send mode for the Android app (`ANDROID_PORT_SPEC.md` section 3.3). The
  receiver side of the port now exists in `android/`.

## 9. Working style that helped

Test against synthetic scenes before touching the camera. A rendered desk with
an orange cable, red LEDs, a face, a shirt print and a flickering lamp caught
most of these bugs before they reached hardware. But **be careful that the
synthetic scene is honest**: rendering the separator row as pure black
produced a detector that passed every test and failed on the real device.
