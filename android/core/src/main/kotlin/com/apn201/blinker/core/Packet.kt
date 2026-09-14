package com.apn201.blinker.core

/**
 * Packet layer, ground truth: atom_matrix_sender.py.
 *
 * A message is UTF-8, split into CHUNK_SIZE-byte chunks. Each chunk on the wire:
 *
 *   [idx][total][length][header_crc8][payload_crc8][payload 0..CHUNK_SIZE bytes]
 *     1B    1B     1B        1B            1B
 *
 * header_crc8 covers [idx,total,length]; payload_crc8 covers the payload bytes only.
 * Bits are sent MSB first per byte. The sender loops all chunks forever; there is no
 * end-of-message marker and no retransmit request - the next lap is the retransmit.
 */
const val CHUNK_SIZE = 8

/** Split a UTF-8 message into CHUNK_SIZE-byte chunks. Empty message -> one empty chunk. */
fun splitIntoChunks(message: String, chunkSize: Int = CHUNK_SIZE): List<ByteArray> {
    val payload = message.toByteArray(Charsets.UTF_8)
    if (payload.isEmpty()) return listOf(ByteArray(0))
    val chunks = ArrayList<ByteArray>()
    var i = 0
    while (i < payload.size) {
        val end = minOf(i + chunkSize, payload.size)
        chunks.add(payload.copyOfRange(i, end))
        i = end
    }
    return chunks
}

/** Build one on-the-wire packet for a chunk. Mirrors build_chunk_packet(). */
fun buildChunkPacket(idx: Int, total: Int, chunkBytes: ByteArray): ByteArray {
    val headerBody = byteArrayOf(idx.toByte(), total.toByte(), chunkBytes.size.toByte())
    val headerCrc = crc8(headerBody).toByte()
    val payloadCrc = crc8(chunkBytes).toByte()
    return headerBody + byteArrayOf(headerCrc, payloadCrc) + chunkBytes
}

/** Flatten a packet to its bit list, MSB first per byte. Mirrors packet_to_bits(). */
fun packetToBits(packet: ByteArray): List<Int> {
    val bits = ArrayList<Int>(packet.size * 8)
    for (byte in packet) {
        val b = byte.toInt() and 0xFF
        for (i in 7 downTo 0) bits.add((b shr i) and 1)
    }
    return bits
}

/**
 * The full bit stream a sender emits for one lap of a message: every chunk's packet
 * bits, in order. The physical framing (dark separators, data phases, inter-chunk gaps)
 * is added by the transmitter; this is just the logical bit sequence.
 */
fun messageToBits(message: String, chunkSize: Int = CHUNK_SIZE): List<Int> {
    val chunks = splitIntoChunks(message, chunkSize)
    val total = chunks.size
    val bits = ArrayList<Int>()
    for ((idx, chunk) in chunks.withIndex()) {
        bits.addAll(packetToBits(buildChunkPacket(idx, total, chunk)))
    }
    return bits
}
