package com.apn201.blinker.core

/**
 * CRC-8, poly 0x07, init 0, no reflection, no final XOR.
 *
 * Ground truth: crc8() in atom_matrix_sender.py and pc_receiver.py. This is the
 * FIRST thing to get right - every header and payload check depends on it. Golden
 * vectors are in CrcTest.
 */
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

/** Convenience: CRC-8 over a list of unsigned byte values (0..255). */
fun crc8(vararg values: Int): Int = crc8(ByteArray(values.size) { values[it].toByte() })

/** MSB-first accumulation of a bit list into an integer. Mirrors bits_to_int(). */
fun bitsToInt(bits: List<Int>): Int {
    var v = 0
    for (b in bits) v = (v shl 1) or (b and 1)
    return v
}

/** Interpret a bit list as ASCII, non-printable bytes shown as '.'. Mirrors bits_to_ascii(). */
fun bitsToAscii(bits: List<Int>): String {
    val out = StringBuilder()
    var i = 0
    while (i <= bits.size - 8) {
        val byte = bitsToInt(bits.subList(i, i + 8))
        out.append(if (byte in 32..126) byte.toChar() else '.')
        i += 8
    }
    return out.toString()
}
