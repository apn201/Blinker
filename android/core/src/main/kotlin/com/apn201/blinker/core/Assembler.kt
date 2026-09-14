package com.apn201.blinker.core

/** One drawable run of the message so far. */
data class Segment(val text: String, val verified: Boolean)

/** Per-chunk status, richer than [Segment], for UI rendering (not in the Python API). */
enum class ChunkStatus { VERIFIED, TENTATIVE, MISSING }
data class RichSegment(val text: String, val status: ChunkStatus)

/**
 * Holds verified chunks, and separately the unverified ones, so a partial message can
 * be shown while it is still arriving. Direct port of Assembler in pc_receiver.py.
 *
 * The rules that took real-hardware pain to learn:
 *  - Only a CRC-VERIFIED chunk may set the message length (`total`).
 *  - Changing an established `total` needs TWO verified chunks agreeing (a single false
 *    header claiming total=2 once wiped a finished message).
 *  - Unverified chunks are display-only: they may fill a slot in a structure a verified
 *    chunk already established, never resize or reset it.
 *  - Verified always wins at a position and is never downgraded.
 */
class Assembler {
    var total: Int? = null
        private set
    val chunks = HashMap<Int, ByteArray>()       // CRC-verified
    val tentative = HashMap<Int, ByteArray>()    // CRC failed, shown as unverified
    private var pending = HashMap<Int, Int>()    // candidate new lengths awaiting a second vote

    /** Add a chunk. Returns the full message bytes iff this completed a verified message. */
    fun add(idx: Int, total: Int, payload: ByteArray, verified: Boolean = true): ByteArray? {
        val alreadyComplete = this.total != null && chunks.size == this.total
        if (verified && this.total != null && total != this.total) {
            // Require TWO verified chunks agreeing on a new length before switching.
            val cnt = (pending[total] ?: 0) + 1
            pending = hashMapOf(total to cnt)
            if (cnt < 2) return null
            pending = HashMap()
            this.total = total
            chunks.clear()
            tentative.clear()
        }
        if (!verified) {
            if (this.total == null || total != this.total || idx >= this.total!!) return null
            if (idx !in chunks) tentative[idx] = payload
            return null
        }
        if (this.total != total) {
            if (alreadyComplete) return null   // noise must not wipe a finished message
            this.total = total
            chunks.clear()
            tentative.clear()
        }
        if (idx >= total) return null
        chunks[idx] = payload
        tentative.remove(idx)
        if (chunks.size == this.total) {
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until this.total!!) out.write(chunks[i]!!)
            return out.toByteArray()
        }
        return null
    }

    fun progress(): String = if (total == null) "0/0" else "${chunks.size}/$total"

    /** The message so far as merged (text, verified) runs. Faithful to Python segments(). */
    fun segments(chunkSize: Int): List<Segment> {
        val t = total ?: return emptyList()
        val segs = ArrayList<Segment>()
        for (i in 0 until t) {
            when {
                i in chunks -> segs.add(Segment(String(chunks[i]!!, Charsets.UTF_8), true))
                i in tentative -> {
                    val txt = buildString {
                        for (b in tentative[i]!!) {
                            val v = b.toInt() and 0xFF
                            append(if (v in 32..126) v.toChar() else '.')
                        }
                    }
                    segs.add(Segment(txt, false))
                }
                else -> segs.add(Segment("_".repeat(chunkSize), false))
            }
        }
        val merged = ArrayList<Segment>()
        for (s in segs) {
            if (merged.isNotEmpty() && merged.last().verified == s.verified) {
                val prev = merged.removeAt(merged.size - 1)
                merged.add(Segment(prev.text + s.text, s.verified))
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    /** Richer per-run view for the app UI: distinguishes MISSING from TENTATIVE. */
    fun richSegments(chunkSize: Int, missingChar: Char = '?'): List<RichSegment> {
        val t = total ?: return emptyList()
        val segs = ArrayList<RichSegment>()
        for (i in 0 until t) {
            when {
                i in chunks -> segs.add(RichSegment(String(chunks[i]!!, Charsets.UTF_8), ChunkStatus.VERIFIED))
                i in tentative -> {
                    val txt = buildString {
                        for (b in tentative[i]!!) {
                            val v = b.toInt() and 0xFF
                            append(if (v in 32..126) v.toChar() else '.')
                        }
                    }
                    segs.add(RichSegment(txt, ChunkStatus.TENTATIVE))
                }
                else -> segs.add(RichSegment(missingChar.toString().repeat(chunkSize), ChunkStatus.MISSING))
            }
        }
        val merged = ArrayList<RichSegment>()
        for (s in segs) {
            if (merged.isNotEmpty() && merged.last().status == s.status) {
                val prev = merged.removeAt(merged.size - 1)
                merged.add(RichSegment(prev.text + s.text, s.status))
            } else {
                merged.add(s)
            }
        }
        return merged
    }

    fun bytesReceived(): Int = chunks.values.sumOf { it.size }

    fun bytesTotal(): Int? =
        if (total != null && chunks.size == total) bytesReceived() else null

    fun partialText(chunkSize: Int): String? {
        val segs = segments(chunkSize)
        return if (segs.isEmpty()) null else segs.joinToString("") { it.text }
    }
}
