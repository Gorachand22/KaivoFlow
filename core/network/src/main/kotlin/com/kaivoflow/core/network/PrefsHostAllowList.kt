package com.kaivoflow.core.network

import android.content.Context

/**
 * The persisted list of local hosts the person allowed (an Ollama box on the LAN, a dev server). Stored as a
 * string set in a private preferences file; no secrets live here. Entries are exact host names or IP literals.
 */
class PrefsHostAllowList(context: Context) : MutableHostAllowList() {
    private val prefs = context.applicationContext.getSharedPreferences("kaivo.network", Context.MODE_PRIVATE)

    init {
        prefs.getStringSet(KEY, emptySet()).orEmpty().forEach { super.allow(it) }
    }

    @Synchronized
    override fun allow(host: String): Boolean {
        val added = super.allow(host)
        if (added) persist()
        return added
    }

    @Synchronized
    override fun revoke(host: String) {
        super.revoke(host)
        persist()
    }

    private fun persist() { prefs.edit().putStringSet(KEY, all()).apply() }

    private companion object { const val KEY = "allowed_local_hosts" }
}
