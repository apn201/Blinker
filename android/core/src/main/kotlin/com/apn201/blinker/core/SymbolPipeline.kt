package com.apn201.blinker.core

/** What one fed symbol produced. */
data class FeedResult(
    val chunk: ChunkResult?,          // a chunk completed this frame (verified or not)
    val completedMessage: ByteArray?, // non-null iff a verified message just completed
    val verifiedChunk: Boolean        // the completed chunk passed its payload CRC
)

/**
 * The symbol-level tail of pc_receiver.py's receive() loop: two decoders (one per
 * polarity), CRC-based polarity selection, and the Assembler. The image front-end
 * feeds it one symbol per frame ("A", "B", "SYNC", or null); this owns bit framing,
 * header search and chunk reassembly.
 *
 * Which observed colour ("A"/"B") means 1 is never assumed - both polarities are
 * decoded in parallel and the first payload-CRC-valid chunk fixes the polarity.
 */
class SymbolPipeline {
    val assembler = Assembler()
    private val decoders = linkedMapOf("A1" to Decoder(), "B1" to Decoder())
    var polarity: String? = null
        private set
    var chunksOk = 0
        private set
    var chunksSeen = 0
        private set
    val symCount = linkedMapOf("A" to 0, "B" to 0, "SYNC" to 0)

    /** Decoder currently trusted for telemetry (the resolved polarity, else A1). */
    fun activeDecoder(): Decoder = decoders[polarity ?: "A1"]!!

    /**
     * Re-acquire from scratch: fresh decoders, polarity and symbol counts. The Assembler
     * is deliberately KEPT - pc_receiver.py's re-acquire resets the front-end but never
     * throws away the message received so far.
     */
    fun reset() {
        decoders["A1"] = Decoder()
        decoders["B1"] = Decoder()
        polarity = null
        symCount["A"] = 0; symCount["B"] = 0; symCount["SYNC"] = 0
    }

    /**
     * Count a raw per-frame symbol for telemetry. Kept separate from [feed] because
     * pc_receiver.py counts the RAW cls but only feeds the decoders the gated cls (null
     * when the activity gate has not trusted the location yet).
     */
    fun countRaw(cls: String?) {
        if (cls != null && symCount.containsKey(cls)) symCount[cls] = symCount[cls]!! + 1
    }

    fun feed(nowMs: Long, cls: String?): FeedResult {
        val results = LinkedHashMap<String, ChunkResult>()
        for ((pol, dec) in decoders) {
            val sym: String? = if (cls == "A" || cls == "B") {
                val one = if (pol == "A1") "A" else "B"
                if (cls == one) "ONE" else "ZERO"
            } else {
                cls   // SYNC or null passes straight through
            }
            dec.feed(nowMs, sym)?.let { results[pol] = it }
        }

        var result: ChunkResult? = null
        val known = polarity
        if (known != null && results.containsKey(known)) {
            result = results[known]
        } else {
            var picked = false
            for ((pol, r) in results) {
                if (r.ok) {                 // a valid CRC identifies the true polarity
                    polarity = pol
                    result = r
                    picked = true
                    break
                }
            }
            if (!picked && results.isNotEmpty() && polarity == null) {
                result = results.values.first()
            }
        }

        if (result == null) return FeedResult(null, null, false)

        chunksSeen += 1
        return if (result.ok) {
            chunksOk += 1
            val full = assembler.add(result.idx, result.total, result.payload, verified = true)
            FeedResult(result, full, true)
        } else {
            assembler.add(result.idx, result.total, result.payload, verified = false)
            FeedResult(result, null, false)
        }
    }
}
