package com.apn201.blinker

import com.apn201.blinker.core.Box
import com.apn201.blinker.core.HeaderInfo
import com.apn201.blinker.core.RichSegment

/**
 * An immutable snapshot of everything the HUD draws for one frame. Built on the analyzer
 * thread from the receiver's state and handed to the UI thread, so the overlay never
 * touches mutable receiver state.
 */
data class HudState(
    val box: Box?,
    val srcW: Int,
    val srcH: Int,
    val locked: Boolean,
    val active: Boolean,
    val cls: String?,
    val fps: Int,
    val frameMs: Int,
    val msPerBit: Double?,
    val colorsDesc: String,
    val boxV: Double,
    val boxSwing: Double,
    val statesSeen: Int,
    val reason: String,
    val symA: Int,
    val symB: Int,
    val symSync: Int,
    val bufferBits: Int,
    val totalBits: Int,
    val hdrTries: Int,
    val chunkTries: Int,
    val lastHeader: HeaderInfo?,
    val polarity: String?,
    val progress: String,
    val progressFrac: Double,
    val chunksOk: Int,
    val chunksSeen: Int,
    val segments: List<RichSegment>
)
