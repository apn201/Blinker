# Blinker — a one-way optical data link

An M5Stack Atom Matrix blinks colours; a webcam watches it and reconstructs the text.
No radio, no cable, no return channel — just a 5×5 LED matrix behind a diffuser and
something that can see it.

Built as an entry for element14's Project14 "Make a Connection". The full write-up, with
the reasoning behind every design decision, is on element14:
**[Blinking at a webcam: a one-way optical data link with an M5Stack Atom Matrix](https://community.element14.com/challenges-projects/project14/b/make-a-connection/posts/blink_2d00_optical_2d00_one_2d00_way_2d00_communication_2d00_m5stack_2d00_atom_2d00_matrix)**

## The project: two Python files

| path | runs on | what it is |
|---|---|---|
| [`atom_matrix_sender.py`](atom_matrix_sender.py) | Atom Matrix (UIFlow2 MicroPython) | the transmitter |
| [`pc_receiver.py`](pc_receiver.py) | PC with a webcam (Python + OpenCV) | the receiver |
| [`HANDOFF.md`](HANDOFF.md) | — | how it works and **what failed on real hardware** |

That is the whole link. Everything else in the repo is extra.

**Bonus:** [`android/`](android/) is a later port of the PC receiver to a native Android
app (Kotlin + CameraX), built after the deadline was extended. It follows `pc_receiver.py`;
where they disagree, the Python is correct. [`ANDROID_PORT_SPEC.md`](ANDROID_PORT_SPEC.md)
is the spec it was built from.

**Status: works, imperfectly.** Full messages decode at about 1.5 m indoors, but the
receiver still loses lock or misreads now and then in cluttered scenes. It is a proof of
concept, not a product. See *Open problems* below.

## How it works

The whole matrix shows **one colour at a time**. There is no spatial pattern to read.

```
dark   -> bit separator (framing)
green  -> one symbol
blue   -> the other symbol
```

Each bit is dark for `LINK_SPEED_MS`, then a colour for `LINK_SPEED_MS` (250 ms each by
default, so about 2 bits per second). The message is split into 8-byte chunks, each sent as:

```
[idx][total][length][header crc8][payload crc8][payload bytes...]
```

CRC-8 uses polynomial 0x07. The matrix stays dark for 1200 ms between chunks. The sender
loops the message forever — the next lap is the retransmit. A 30-byte message is 4 chunks,
roughly 70 seconds per lap. Which colour means 1 is deliberately **not** defined: the
receiver decodes both polarities in parallel and lets the chunk CRC decide.

The receiver finds the transmitter by what it *does*, not what it looks like. It looks only
at pixels that are blinking, learns the two colours from the transmitter itself rather than
trusting fixed hues, and keeps a persistent lock. [`HANDOFF.md`](HANDOFF.md) explains each
stage and the specific failure it exists to prevent. Read section 5 before changing
anything — several ideas that look obviously correct were tried on hardware and failed.

## Quick start

### 1. Sender (Atom Matrix)

Open [`atom_matrix_sender.py`](atom_matrix_sender.py) in the **UIFlow2 code editor** (not
Blockly), set `MESSAGE`, and run it on the device. Re-push to the device whenever the file
changes — downloading it to the PC does nothing.

Keep `BRIGHTNESS` low (12–45). An overdriven LED clips to white on camera, and white has no
hue. The code refuses values above 70, which can damage the device.

### 2. Receiver (PC + webcam)

```bash
pip install opencv-python numpy
```

```bash
python pc_receiver.py --list-cameras
```

```bash
python pc_receiver.py --camera 0
```

Press `q` to quit. The HUD shows live telemetry; [`HANDOFF.md`](HANDOFF.md) section 4
explains how to read it.

## Bonus: Android receiver

Requires Android 7.0 (API 24) or newer with a camera. Open the `android/` folder in Android
Studio and Run, or build from the command line:

```bash
cd android && ./gradlew :app:assembleDebug
```

The receiver core is plain Kotlin and is tested without a device. The suite includes CRC
golden vectors, protocol round-trips, and a synthetic-frame harness with noise, dropped
frames and a warm decoy object:

```bash
cd android && ./gradlew :core:test
```

Details, the HUD glossary and performance notes are in
[`android/README.md`](android/README.md).

## Open problems

- Occasional lock loss in cluttered scenes (an orange cable is the recurring offender).
- Chunk CRC failures still happen at a low rate. Unverified text is shown in amber and
  upgraded to green when a clean copy arrives.
- Very washed-out lighting can lose the blob entirely.
- **Range is capped by a pixel floor, not by physics.** Detection requires a blob of
  at least 150 px² with both sides at least 10 px, so on a 640×480 webcam the matrix
  falls below the floor at roughly 1–1.5 m and is never offered as a candidate — even
  when two hues and the blink are plainly visible to the eye. The behaviour tests that
  actually identify the transmitter (two hues at one spot, a 120–900 ms rhythm) do not
  need that many pixels. Diagnosed by reading the code, not yet tested on hardware; see
  `HANDOFF.md` section 7.
- Sharp close focus, where individual LEDs resolve as separate dots, was never fully handled.
- The message is hardcoded in the sender.
- The Android app has no send mode yet (spec section 3.3).

Ideas not yet tried are listed in [`HANDOFF.md`](HANDOFF.md) section 8.

## License

[MIT](LICENSE)
