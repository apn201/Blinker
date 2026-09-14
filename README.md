# Blinker — a one-way optical data link

An M5Stack Atom Matrix blinks colours; a camera watches it and reconstructs the text.
No radio, no cable, no return channel — just a 5×5 LED matrix behind a diffuser and
something that can see it.

This repo has the sender and two receivers:

| path | runs on | what it is |
|---|---|---|
| [`atom_matrix_sender.py`](atom_matrix_sender.py) | Atom Matrix (UIFlow2 MicroPython) | the transmitter |
| [`pc_receiver.py`](pc_receiver.py) | PC with a webcam (Python + OpenCV) | the reference receiver |
| [`android/`](android/) | Android phone (Kotlin + CameraX) | a port of the receiver, see [`android/README.md`](android/README.md) |
| [`HANDOFF.md`](HANDOFF.md) | — | how it works and **what failed on real hardware** |
| [`ANDROID_PORT_SPEC.md`](ANDROID_PORT_SPEC.md) | — | the spec the Android port was built from |

Built as an entry for element14's Project14 "Make a Connection".

**Status: works, imperfectly.** Full messages decode, but the receivers still lose lock or
misread now and then in cluttered scenes. See *Open problems* below.

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

CRC-8 uses polynomial 0x07. The sender loops the message forever — the next lap is the
retransmit. Which colour means 1 is deliberately **not** defined: the receivers decode both
polarities in parallel and let the chunk CRC decide.

The receivers find the transmitter by what it *does*, not what it looks like. They look
only at pixels that are blinking, learn the two colours from the transmitter itself rather
than trusting fixed hues, and keep a persistent lock. [`HANDOFF.md`](HANDOFF.md) explains
each stage and the specific failure it exists to prevent. Read section 5 before changing
anything — several ideas that look obviously correct were tried on hardware and failed.

## Quick start

### 1. Sender (Atom Matrix)

Open [`atom_matrix_sender.py`](atom_matrix_sender.py) in the **UIFlow2 code editor** (not
Blockly), set `MESSAGE`, and run it on the device. Re-push to the device whenever the file
changes — downloading it to the PC does nothing.

Keep `BRIGHTNESS` low (12–45). An overdriven LED clips to white on camera, and white has no
hue. The code refuses values above 70, which can damage the device.

### 2a. PC receiver

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

### 2b. Android receiver

Requires Android 7.0 (API 24) or newer with a camera. Open the `android/` folder in Android
Studio and Run, or build from the command line:

```bash
cd android && ./gradlew :app:assembleDebug
```

Details, the HUD glossary and performance notes are in
[`android/README.md`](android/README.md).

## Tests

The Android port's receiver core is plain Kotlin and is tested without a device. The suite
includes CRC golden vectors, protocol round-trips, and a synthetic-frame harness with noise,
dropped frames and a warm decoy object:

```bash
cd android && ./gradlew :core:test
```

## Open problems

- Occasional lock loss in cluttered scenes (an orange cable is the recurring offender).
- Chunk CRC failures still happen at a low rate. Unverified text is shown in amber and
  upgraded to green when a clean copy arrives.
- Very washed-out lighting can lose the blob entirely.
- Sharp close focus, where individual LEDs resolve as separate dots, was never fully handled.
- The Android app has no send mode yet (spec section 3.3).

Ideas not yet tried are listed in [`HANDOFF.md`](HANDOFF.md) section 8.

## License

[MIT](LICENSE)
