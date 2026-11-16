package com.kaivoflow.core.engine.reliability

/*
 * AO-21 (spec 6.5): pass criteria of ST1 to ST7 as pure functions of what the runner measured. The runner (Android) only
 * arms, waits and measures; it never decides pass or fail itself.
 */

enum class SelfTestId(val title: String) {
    ST1("Alarm"), ST2("Inbox round trip"), ST3("Engine restart"), ST4("Webhook"), ST5("Listener"),
    ST6("Foreground start from background"), ST7("Overnight soak");

    val code: String get() = name
}

enum class SelfTestState { Pass, Fail, Skipped, Pending }

data class SelfTestOutcome(val id: SelfTestId, val state: SelfTestState, val detail: String, val measuredMs: Long? = null)

object SelfTestCriteria {
    const val ST1_MAX_LATE_MS = 60_000L
    const val ST2_ENGINE_MS = 5_000L
    const val ST2_FALLBACK_MS = 30_000L
    const val ST3_MAX_MS = 16 * 60_000L
    const val ST5_MAX_MS = 3_000L

    private fun out(id: SelfTestId, ok: Boolean, detail: String, ms: Long? = null) =
        SelfTestOutcome(id, if (ok) SelfTestState.Pass else SelfTestState.Fail, detail, ms)

    /** [exactLateMs] and [onTimeLateMs] are null when the alarm never fired in the wait window. */
    fun st1(exactLateMs: Long?, onTimeLateMs: Long?): SelfTestOutcome {
        if (exactLateMs == null || onTimeLateMs == null)
            return out(SelfTestId.ST1, false, "The ${if (exactLateMs == null) "exact" else "on-time"} alarm did not fire.")
        val worst = maxOf(exactLateMs, onTimeLateMs)
        return out(SelfTestId.ST1, worst <= ST1_MAX_LATE_MS, "Exact ${exactLateMs / 1000} s late, on-time ${onTimeLateMs / 1000} s late.", worst)
    }

    fun st2(startMs: Long?, viaEngine: Boolean): SelfTestOutcome {
        if (startMs == null) return out(SelfTestId.ST2, false, "The internal Run did not start.")
        val limit = if (viaEngine) ST2_ENGINE_MS else ST2_FALLBACK_MS
        return out(SelfTestId.ST2, startMs <= limit, "Run started after ${startMs} ms via the ${if (viaEngine) "engine" else "fallback worker"}.", startMs)
    }

    /** Background test: [backMs] null while still waiting, [waitedMs] is how long it has waited. */
    fun st3(backMs: Long?, waitedMs: Long): SelfTestOutcome = when {
        backMs != null -> out(SelfTestId.ST3, backMs <= ST3_MAX_MS, "Engine was back after ${backMs / 1000} s.", backMs)
        waitedMs < ST3_MAX_MS -> SelfTestOutcome(SelfTestId.ST3, SelfTestState.Pending, "Waiting for the engine to come back (${waitedMs / 1000} s).")
        else -> out(SelfTestId.ST3, false, "The engine did not come back within 16 minutes.")
    }

    /**
     * The probe path has no route of its own, so an authorised request is answered 404 (route unknown) rather than 2xx; both prove
     * the listener is up and the token is accepted. A 401 with the token, or anything but 401 without it, fails.
     */
    fun st4(withTokenStatus: Int?, withoutTokenStatus: Int?): SelfTestOutcome {
        val ok = withTokenStatus != null && (withTokenStatus in 200..299 || withTokenStatus == 404) && withoutTokenStatus == 401
        return out(SelfTestId.ST4, ok, "With token: ${withTokenStatus ?: "no answer"}, without token: ${withoutTokenStatus ?: "no answer"} (expected accepted and 401).")
    }

    fun st5(eventMs: Long?): SelfTestOutcome =
        if (eventMs == null) out(SelfTestId.ST5, false, "The listener did not see the test notification.")
        else out(SelfTestId.ST5, eventMs <= ST5_MAX_MS, "Listener saw the test notification after $eventMs ms.", eventMs)

    fun st6(engineRunningAfter: Boolean, alarmFired: Boolean): SelfTestOutcome = when {
        !alarmFired -> out(SelfTestId.ST6, false, "The 30 second alarm did not fire.")
        else -> out(SelfTestId.ST6, engineRunningAfter, if (engineRunningAfter) "Engine started from the background." else "Android refused to start the engine from the background.")
    }

    /** Soak slots: lateness of each slot in ms (null = missed). Stock needs 99% within 60 s, OEM phones 95% within 5 min and no unexplained deaths. */
    fun st7(latenessMs: List<Long?>, unexplainedDeaths: Int, stockAndroid: Boolean): SelfTestOutcome {
        if (latenessMs.isEmpty()) return SelfTestOutcome(SelfTestId.ST7, SelfTestState.Skipped, "No soak slots recorded.")
        val limit = if (stockAndroid) 60_000L else 5 * 60_000L
        val need = if (stockAndroid) 0.99 else 0.95
        val within = latenessMs.count { it != null && it <= limit }
        val ratio = within.toDouble() / latenessMs.size
        val fired = latenessMs.filterNotNull().sorted()
        val p50 = RunAggregation.percentile(fired, 50); val p95 = RunAggregation.percentile(fired, 95)
        val ok = ratio >= need && unexplainedDeaths == 0
        return out(SelfTestId.ST7, ok, "${within} of ${latenessMs.size} slots within ${limit / 1000} s, p50 ${p50 / 1000} s, p95 ${p95 / 1000} s, max ${(fired.lastOrNull() ?: 0) / 1000} s, $unexplainedDeaths unexplained engine deaths.", fired.lastOrNull())
    }
}
