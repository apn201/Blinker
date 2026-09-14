package com.apn201.blinker.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Only trusts a location once it has been seen showing more than one distinct state.
 * Port of ActivityGate in pc_receiver.py. A real transmitter alternates SYNC/data
 * forever; a static object shows one state and never changes. A uniformly coloured
 * square is a structurally valid dark/lit frame, so structure alone cannot reject it -
 * only behaviour over time can.
 *
 * Trust is earned two independent ways: decoding several distinct states (needs working
 * hue), OR seeing the brightness blink its rhythm (works purely in the value channel, so
 * it survives conditions that make hue unreadable). A static object satisfies neither.
 */
class ActivityGate {
    companion object {
        const val WINDOW_MS = 4000L        // ACTIVITY_WINDOW_MS
        const val MIN_STATES = 2           // ACTIVITY_MIN_STATES
        const val BLINK_MIN_SWING = 22.0   // pc_receiver.py value
        const val BLINK_MIN_SAMPLES = 12
    }

    private class Track(var box: Box, val states: HashMap<String, Long>, var lastMs: Long, val vs: ArrayList<Double>)

    private val tracks = ArrayList<Track>()

    private fun swing(vals: List<Double>): Double {
        if (vals.size < BLINK_MIN_SAMPLES) return 0.0
        val s = vals.sorted()
        val lo = s[max(0, (s.size * 0.15).toInt())]
        val hi = s[min(s.size - 1, (s.size * 0.85).toInt())]
        return hi - lo
    }

    private fun find(box: Box): Track? {
        for (t in tracks) {
            if (abs(t.box.cx - box.cx) < 100 && abs(t.box.cy - box.cy) < 100) return t
        }
        return null
    }

    /** (trusted, nStates, swing) for a location, recording nothing. */
    fun evidence(box: Box, nowMs: Long): Triple<Boolean, Int, Double> {
        tracks.removeAll { nowMs - it.lastMs >= WINDOW_MS }
        val t = find(box) ?: return Triple(false, 0, 0.0)
        val states = t.states.filterValues { nowMs - it < WINDOW_MS }
        val sw = swing(t.vs)
        return Triple(states.size >= MIN_STATES || sw >= BLINK_MIN_SWING, states.size, sw)
    }

    /** Record a sighting and return (trusted, nStates, swing). */
    fun observe(box: Box, cls: String?, nowMs: Long, cornerV: Double? = null): Triple<Boolean, Int, Double> {
        tracks.removeAll { nowMs - it.lastMs >= WINDOW_MS }
        var t = find(box)
        if (t == null) {
            t = Track(box, HashMap(), nowMs, ArrayList())
            tracks.add(t)
        }
        t.box = box
        t.lastMs = nowMs
        if (cls != null) t.states[cls] = nowMs
        val stale = t.states.filterValues { nowMs - it >= WINDOW_MS }.keys
        for (k in stale) t.states.remove(k)
        if (cornerV != null) {
            t.vs.add(cornerV)
            if (t.vs.size > 40) t.vs.removeAt(0)
        }
        val sw = swing(t.vs)
        val trusted = t.states.size >= MIN_STATES || sw >= BLINK_MIN_SWING
        return Triple(trusted, t.states.size, sw)
    }

    fun reset() = tracks.clear()
}
