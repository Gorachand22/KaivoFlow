package com.kaivoflow.core.engine

/** Run-history retention defaults. The effective rule is "whichever limit is smaller". */
object RunRetention {
    const val MAX_RUNS_PER_FLOW = 500
    const val MAX_AGE_DAYS = 30

    const val INTERRUPTED_MESSAGE = "Interrupted: the app was closed or the device restarted during this Run."
}
