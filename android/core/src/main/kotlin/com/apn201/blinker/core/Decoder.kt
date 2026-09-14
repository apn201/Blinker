package com.apn201.blinker.core

/** Outcome of a completed chunk. `ok` is the payload-CRC verdict. */
data class ChunkResult(val ok: Boolean, val idx: Int, val total: Int, val payload: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkResult) return false
        return ok == other.ok && idx == other.idx && total == other.total && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var r = ok.hashCode()
        r = 31 * r + idx
        r = 31 * r + total
        r = 31 * r + payload.contentHashCode()
        return r
    }
}

/** The last header this decoder parsed, for telemetry. */
data class HeaderInfo(val idx: Int, val total: Int, val length: Int, val plausible: Boolean, val crcOk: Boolean)

/**
 * Run-length decoder over SYNC/ONE/ZERO states. Direct port of Decoder in
 * pc_receiver.py. Each confirmed run that ends on a data state emits one bit; SYNC
 * runs are separators. A sliding 40-bit window is parsed as a header on every new
 * bit - a 1-byte header CRC means a random window passes ~1 in 256 tries, so false
 * headers are expected; the Assembler, not this class, defends against them.
 */
class Decoder {
    companion object {
        const val VOTE_WINDOW = 5
        const val VOTE_MIN = 3
        const val STALE_MS = 8000L   // pc_receiver.py value (the port spec's 6000 is stale)
    }

    private var current: String? = null
    private var runLen = 0
    private val window = ArrayList<String>()
    val collectedBits = ArrayList<Int>()
    private var packetBitsNeeded: Int? = null
    private var idx: Int? = null
    private var total: Int? = null
    private var length: Int? = null
    private var crcExpected: Int? = null
    private var lastActivity = 0L

    // Telemetry only
    val bitLog = ArrayList<Int>()
    private val bitTimes = ArrayList<Long>()
    var totalBits = 0
        private set
    var headerAttempts = 0
        private set
    var lastHeader: HeaderInfo? = null
        private set
    var chunkAttempts = 0
        private set

    fun feed(nowMs: Long, cls: String?): ChunkResult? {
        var result: ChunkResult? = null
        if (collectedBits.isNotEmpty() && (nowMs - lastActivity) > STALE_MS) {
            collectedBits.clear()
            packetBitsNeeded = null
        }
        if (cls == null) return null   // no information this frame, not a state change

        window.add(cls)
        if (window.size > VOTE_WINDOW) window.removeAt(0)
        runLen += 1

        // counts in first-appearance order, so ties break like Python's max(dict)
        val counts = LinkedHashMap<String, Int>()
        for (c in window) counts[c] = (counts[c] ?: 0) + 1
        var winner = window.first()
        var best = -1
        for ((k, v) in counts) {
            if (v > best) {
                best = v
                winner = k
            }
        }
        if (winner == current || counts[winner]!! < VOTE_MIN) return null

        val finished = current
        current = winner
        runLen = counts[winner]!!
        if (finished == "ONE" || finished == "ZERO") {
            result = pushBit(if (finished == "ONE") 1 else 0, nowMs)
        }
        return result
    }

    private fun pushBit(bit: Int, nowMs: Long): ChunkResult? {
        var result: ChunkResult? = null
        totalBits += 1
        bitLog.add(bit)
        if (bitLog.size > 64) bitLog.removeAt(0)
        bitTimes.add(nowMs)
        if (bitTimes.size > 12) bitTimes.removeAt(0)
        lastActivity = nowMs

        if (packetBitsNeeded == null) {
            collectedBits.add(bit)
            if (collectedBits.size > 40) collectedBits.removeAt(0)
            if (collectedBits.size == 40) {
                val b = collectedBits
                val pIdx = bitsToInt(b.subList(0, 8))
                val pTotal = bitsToInt(b.subList(8, 16))
                val pLength = bitsToInt(b.subList(16, 24))
                val headerCrc = bitsToInt(b.subList(24, 32))
                val payloadCrc = bitsToInt(b.subList(32, 40))
                val plausible = pLength <= CHUNK_SIZE && pTotal != 0 && pIdx < pTotal
                val crcOk = crc8(pIdx, pTotal, pLength) == headerCrc
                headerAttempts += 1
                lastHeader = HeaderInfo(pIdx, pTotal, pLength, plausible, crcOk)
                if (plausible && crcOk) {
                    idx = pIdx
                    total = pTotal
                    length = pLength
                    crcExpected = payloadCrc
                    packetBitsNeeded = 40 + pLength * 8
                }
            }
        } else {
            collectedBits.add(bit)
        }

        val needed = packetBitsNeeded
        if (needed != null && collectedBits.size >= needed) {
            val len = length!!
            val payloadBits = collectedBits.subList(40, 40 + len * 8)
            val payload = ByteArray(len) { i ->
                bitsToInt(payloadBits.subList(i * 8, i * 8 + 8)).toByte()
            }
            val ok = crc8(payload) == crcExpected
            chunkAttempts += 1
            result = ChunkResult(ok, idx!!, total!!, payload)
            collectedBits.clear()
            packetBitsNeeded = null
        }
        return result
    }

    fun measuredMsPerBit(): Double? {
        if (bitTimes.size < 2) return null
        var sum = 0L
        for (i in 1 until bitTimes.size) sum += bitTimes[i] - bitTimes[i - 1]
        return sum.toDouble() / (bitTimes.size - 1)
    }
}
