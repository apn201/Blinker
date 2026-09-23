# Blinker: a one-way optical data link

An M5Stack Atom Matrix blinks colours. A webcam watches it and rebuilds the text.
One way only, no radio and no cable, so the receiver has to work with what it sees.

I built it for element14's Project14 "Make a Connection". The write-up, with the
reasoning behind the decisions and the things that went wrong, is there:
[Blinking at a webcam: a one-way optical data link with an M5Stack Atom Matrix](https://community.element14.com/challenges-projects/project14/b/make-a-connection/posts/blink_2d00_optical_2d00_one_2d00_way_2d00_communication_2d00_m5stack_2d00_atom_2d00_matrix)

## The project: two Python files

| path | runs on | what it is |
|---|---|---|
| [`atom_matrix_sender.py`](atom_matrix_sender.py) | Atom Matrix (UIFlow2 MicroPython) | the transmitter |
| [`pc_receiver.py`](pc_receiver.py) | PC with a webcam (Python + OpenCV) | the receiver |
| [`HANDOFF.md`](HANDOFF.md) | | how it works, and what failed on real hardware |

Those two files are the whole link. Everything else here is extra.

Bonus: [`android/`](android/) is a port of the receiver to a native Android app
(Kotlin + CameraX), done after the deadline was extended. It follows `pc_receiver.py`,
and where they disagree the Python is correct. [`ANDROID_PORT_SPEC.md`](ANDROID_PORT_SPEC.md)
is the spec I wrote it from.

Status: works, imperfectly. Full messages decode at about 1.5 m indoors. It still loses
lock or misreads in cluttered scenes. It is a proof of concept, not a product. Open
problems are at the bottom.

## How it works

The whole matrix shows one colour at a time. There is no pattern inside the 5x5 grid to
read.

```
dark   -> bit separator (framing)
green  -> one symbol
blue   -> the other symbol
```

Each bit is dark for `LINK_SPEED_MS`, then a colour for `LINK_SPEED_MS` (250 ms each by
default, so about 2 bits per second). The message is split into 8-byte chunks, each sent
as:

```
[idx][total][length][header crc8][payload crc8][payload bytes...]
```

CRC-8 uses polynomial 0x07. The matrix sits dark for 1200 ms between chunks. The sender
loops the message forever, so the next lap is the retransmit. A 30-byte message is 4
chunks, roughly 70 seconds per lap. Which colour means 1 is deliberately left undefined:
the receiver decodes both polarities in parallel and lets the chunk CRC pick the winner.

The receiver finds the transmitter by behaviour. It only considers pixels
that are blinking, learns the two colours from the transmitter itself instead of trusting
fixed hues, and keeps a persistent lock. [`HANDOFF.md`](HANDOFF.md) goes through each
stage and the failure it exists to prevent. Read section 5 before changing anything,
because several ideas that look obviously correct were tried on hardware and failed.

## Quick start

### 1. Sender (Atom Matrix)

Open [`atom_matrix_sender.py`](atom_matrix_sender.py) in the UIFlow2 code editor (not
Blockly), set `MESSAGE`, and run it on the device. Re-push to the device whenever the file
changes. Downloading it to the PC does nothing.

Keep `BRIGHTNESS` low (12 to 45). An overdriven LED clips to white on camera, and white
has no hue. The code refuses anything above 70, which can damage the device.

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

Press `q` to quit. The HUD shows live telemetry, and [`HANDOFF.md`](HANDOFF.md) section 4
explains how to read it.

## Bonus: Android receiver

Needs Android 7.0 (API 24) or newer with a camera. Open the `android/` folder in Android
Studio and Run, or build it from the command line:

```bash
cd android && ./gradlew :app:assembleDebug
```

The receiver core is plain Kotlin and runs without a device, so the tests cover CRC golden
vectors, protocol round-trips, and a synthetic-frame harness with noise, dropped frames
and a warm decoy object:

```bash
cd android && ./gradlew :core:test
```

Details, the HUD glossary and performance notes are in
[`android/README.md`](android/README.md).

## Open problems

- Occasional lock loss in cluttered scenes. An orange cable is the recurring offender.
- Chunk CRC failures still happen at a low rate. Unverified text shows in amber and turns
  green when a clean copy arrives.
- Very washed-out lighting can lose the blob entirely.
- Range is capped by pixel thresholds in the detector. Detection needs a blob of at least
  150 px² with both sides at least 10 px, so on a 640x480 webcam the matrix drops under
  the floor at roughly 1 to 1.5 m and never becomes a candidate, even with two hues and
  the blink plainly visible to the eye. The behaviour tests that actually identify the
  transmitter (two hues at one spot, a 120 to 900 ms rhythm) do not need that many pixels.
  Diagnosed by reading the code, not tested on hardware. See `HANDOFF.md` section 7.
- Sharp close focus, where the LEDs resolve as separate dots, was never handled properly.
- The message is hardcoded in the sender.
- The Android app has no send mode yet (spec section 3.3).

Ideas I have not tried are in [`HANDOFF.md`](HANDOFF.md) section 8.

## License

[MIT](LICENSE)
