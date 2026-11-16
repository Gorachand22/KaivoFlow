package com.kaivoflow.core.engine

import com.kaivoflow.core.engine.stepdata.SaveDataMode
import com.kaivoflow.core.engine.stepdata.StepDataBudget
import com.kaivoflow.core.engine.stepdata.StepDataLimits
import com.kaivoflow.core.engine.stepdata.StepDataRetention
import com.kaivoflow.core.engine.stepdata.StepDataStore

/** Run-history retention defaults. The effective rule is "whichever limit is smaller". */
object RunRetention {
    const val MAX_RUNS_PER_FLOW = 500
    const val MAX_AGE_DAYS = 30

    const val INTERRUPTED_MESSAGE = "Interrupted: the app was closed or the device restarted during this Run."

    /** Deletes the stored Step data of Runs that were pruned from the history. Returns how many Runs lost data. */
    suspend fun deleteStepData(store: StepDataStore, runIds: Collection<String>): Int {
        var n = 0
        for (id in runIds.toSet()) { store.deleteRun(id); n++ }
        return n
    }

    /**
     * Applies the Step data rules of spec 5.3 rule 5: count-based retention (last 20 manual Runs per
     * Flow, production Runs by [mode]) and then the global byte budget, oldest Run first. Runs in
     * [protectedRunIds] (open in the editor) and Runs holding pinned replays are never removed.
     * Returns the ids of the Runs whose data was deleted.
     */
    suspend fun pruneStepData(
        store: StepDataStore,
        mode: SaveDataMode = SaveDataMode.Failed,
        protectedRunIds: Set<String> = emptySet(),
        budgetBytes: Long = StepDataLimits.DEFAULT_GLOBAL_BUDGET_BYTES,
    ): List<String> {
        val dropped = StepDataRetention.runsToDrop(store.runMetas(), mode, protectedRunIds)
        deleteStepData(store, dropped)
        val plan = StepDataBudget.plan(store.usage(), budgetBytes, protectedRunIds)
        deleteStepData(store, plan.evictRunIds)
        return (dropped + plan.evictRunIds).distinct()
    }
}
