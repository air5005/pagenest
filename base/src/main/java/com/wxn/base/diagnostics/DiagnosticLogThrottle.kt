package com.wxn.base.diagnostics

data class ThrottleDecision(
    val accepted: Boolean,
    val suppressedDuplicates: Int = 0,
)

class DiagnosticLogThrottle(
    private val windowMillis: Long = 10_000L,
    private val maxFingerprints: Int = 256,
) {
    private data class State(
        val acceptedAtMillis: Long,
        var suppressed: Int,
    )

    private val states = LinkedHashMap<String, State>(maxFingerprints, 0.75f, true)

    @Synchronized
    fun accept(entry: DiagnosticLogEntry, nowMillis: Long): ThrottleDecision {
        val fingerprint = "${entry.level}\u0000${entry.category}\u0000${entry.message}"
        val state = states[fingerprint]
        if (state != null && nowMillis - state.acceptedAtMillis < windowMillis) {
            state.suppressed++
            return ThrottleDecision(accepted = false)
        }
        val suppressed = state?.suppressed ?: 0
        states[fingerprint] = State(nowMillis, suppressed = 0)
        trimOldest()
        return ThrottleDecision(accepted = true, suppressedDuplicates = suppressed)
    }

    private fun trimOldest() {
        while (states.size > maxFingerprints) {
            states.entries.iterator().run {
                next()
                remove()
            }
        }
    }
}
