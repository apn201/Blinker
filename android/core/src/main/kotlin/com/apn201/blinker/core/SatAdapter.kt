package com.apn201.blinker.core

import kotlin.math.max
import kotlin.math.min

/**
 * Adapts only the saturation floor used to FIND the matrix. Port of SatAdapter in
 * pc_receiver.py. Hue calibration is gone entirely - the ColorSplitter replaced it.
 */
class SatAdapter(initial: Double = SAT_MIN_INITIAL) {
    companion object {
        const val SAT_MIN_INITIAL = 120.0
        const val SAT_FLOOR = 32.0
    }

    var satMin: Double = initial
        private set
    private var ema = initial
    private var missStreak = 0

    fun update(gotRead: Boolean, peakSat: Int, cellSatHint: Double? = null) {
        if (gotRead) {
            missStreak = 0
            if (cellSatHint != null) {
                ema = ema * 0.97 + cellSatHint * 0.03
                satMin = max(SAT_FLOOR, min(200.0, ema * 0.55))
            }
        } else {
            missStreak += 1
            if (missStreak > 45) {                 // about 1.5s of nothing
                satMin = max(SAT_FLOOR, satMin * 0.97)
                if (missStreak > 400) {
                    satMin = SAT_MIN_INITIAL
                    missStreak = 0
                }
            }
        }
    }

    fun reset() {
        satMin = SAT_MIN_INITIAL
        ema = SAT_MIN_INITIAL
        missStreak = 0
    }
}
