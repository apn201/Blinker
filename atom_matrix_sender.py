# ==========================================================
# ATOM MATRIX OPTICAL LINK - SENDER (UIFlow2 MicroPython)
# STRUCTURAL VERSION. Still one bit per frame - deliberately slow and safe.
# All 25 LEDs are spent on making that ONE bit unmistakable, not on speed.
#
# Every frame looks like this (5x5):
#   Whole matrix one colour: off = bit separator, green = 1, blue = 0.
#
# Why this beats the old "whole matrix is one colour" scheme:
#
#  1. The corners are a fixed colour reference visible IN THE SAME FRAME as
#     the data, under the same light, in the same exposure. The receiver
#     classifies by the DIFFERENCE between interior and corners, never
#     against remembered absolute hues. Daylight shifting every hue by 20
#     degrees shifts both equally and the difference is unchanged. The
#     "magenta stopped working when the sun came up" failure cannot happen
#     by construction - there is nothing left to drift out of sync with.
#
#  2. Corners + uniform interior is a STRUCTURE, like a QR finder pattern.
#     The receiver locks onto geometry, not "largest blob of roughly the
#     right hue". A printer logo is the wrong shape and gets rejected no
#     matter how perfect its colour is.
#
#  3. The 21 interior cells all carry the SAME bit - 21 independent
#     measurements of one bit, majority-voted. Blur, glare on a few cells,
#     or a finger over a corner cost accuracy instead of costing the bit.
#
# Paste into the UiFlow2 code editor, Run. Re-push to the DEVICE whenever
# this file changes - downloading it to the PC does nothing by itself.
# ==========================================================

import os, sys, io
import M5
from M5 import *
from hardware import RGB
import time

M5.begin()
NUM_LEDS = 25
rgb = RGB()

# ================== TUNE THESE ==================
LINK_SPEED_MS = 250      # ms per phase. Sync phase and data phase each take this long, so
                         # one bit takes 2x this. The receiver has NO timing constants of its
                         # own - it just watches for the pattern to change - so this can be
                         # changed freely without touching the receiver.
BRIGHTNESS = 12          # 0-100, hard capped at 70 below. Keep it low: an overdriven LED
                         # clips to white on camera and white has no hue for the receiver to
                         # measure. If the receiver reports low confidence with the matrix
                         # clearly in frame, try LOWERING this, not raising it.
MESSAGE = "All your base are belong to us"
CHUNK_SIZE = 8
GAP_MS = 1200            # hold on SYNC between chunks

assert BRIGHTNESS <= 70, "BRIGHTNESS above 70 can damage the device"
assert CHUNK_SIZE <= 255

rgb.set_brightness(BRIGHTNESS)


def pack(r, g, b):
    return (r << 16) | (g << 8) | b


# Chosen so the interior-minus-corner hue difference is symmetric and far apart:
#   SYNC  ->   0   (interior identical to corners)
#   ONE   -> -45   (green vs azure)
#   ZERO  -> +45   (magenta vs azure)
# Verified against a real colour-space conversion, not assumed from theory.
# TWO colours, and the bit separator is the matrix going DARK.
#
#   SYNC  -> all LEDs off
#   ONE   -> whole matrix green (hue 60)
#   ZERO  -> whole matrix blue  (hue 120)
#
# This replaced a much more elaborate scheme (reference band, dark separator row,
# 45-degree hue steps) that was built to survive daylight shifting the colours.
# It solved that, but at the cost of needing the receiver to resolve internal
# structure - which meant it broke on diffuser bleed, on tilt, on focus, and kept
# locking onto furniture that happened to have horizontal structure.
#
# The real problem was only ever that hue targets drift. Two colours 90 degrees
# apart solve that directly: even a large white-balance shift cannot turn green
# into magenta. And with the whole matrix one colour there is no internal
# structure to resolve, so bleed, tilt and rotation stop mattering entirely -
# there is nothing to be oriented.
#
# Going dark between bits also gives the receiver its framing for free, and makes
# the transmitter self-identifying: a printer or a sticker never blinks out.
COLOR_ONE = pack(0, 255, 0)      # green (hue 60)
COLOR_ZERO = pack(0, 0, 255)     # blue  (hue 120). Was magenta, which turned out to sit
                                  # only 30 degrees from RED on the hue wheel - a red or
                                  # orange cable in frame was legitimately readable as a
                                  # ZERO and kept stealing the lock. Blue is 60 from red
                                  # and 72 from orange, so no warm object comes close.
COLOR_OFF = pack(0, 0, 0)


def fill(color):
    rgb.set_screen([color] * NUM_LEDS)


def crc8(data):
    crc = 0
    for b in data:
        crc ^= b
        for _ in range(8):
            if crc & 0x80:
                crc = ((crc << 1) ^ 0x07) & 0xFF
            else:
                crc = (crc << 1) & 0xFF
    return crc


def split_into_chunks(message):
    payload = message.encode("utf-8")
    chunks = [payload[i:i + CHUNK_SIZE] for i in range(0, len(payload), CHUNK_SIZE)]
    return chunks or [b""]


def build_chunk_packet(idx, total, chunk_bytes):
    # [idx][total][length][header crc8][payload crc8][chunk bytes]
    header_body = bytes([idx, total, len(chunk_bytes)])
    return header_body + bytes([crc8(header_body), crc8(chunk_bytes)]) + chunk_bytes


def packet_to_bits(packet):
    bits = []
    for byte in packet:
        for i in range(7, -1, -1):
            bits.append((byte >> i) & 1)
    return bits


def send_bit(bit):
    fill(COLOR_OFF)                              # separator: matrix dark
    time.sleep_ms(LINK_SPEED_MS)
    fill(COLOR_ONE if bit else COLOR_ZERO)       # data
    time.sleep_ms(LINK_SPEED_MS)
    M5.update()


def run_transmit():
    chunks = split_into_chunks(MESSAGE)
    total = len(chunks)
    print("message={} bytes, {} chunks, {}ms/phase".format(
        len(MESSAGE.encode("utf-8")), total, LINK_SPEED_MS))
    while True:
        for idx, chunk in enumerate(chunks):
            for bit in packet_to_bits(build_chunk_packet(idx, total, chunk)):
                send_bit(bit)
            fill(COLOR_OFF)
            time.sleep_ms(GAP_MS)
            M5.update()


if __name__ == "__main__":
    try:
        run_transmit()
    except (Exception, KeyboardInterrupt) as e:
        try:
            from utility import print_error_msg
            print_error_msg(e)
        except ImportError:
            print("please update to latest firmware")
