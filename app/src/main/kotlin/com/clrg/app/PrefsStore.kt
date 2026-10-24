package com.clrg.app

import android.content.Context
import com.clrg.core.party.KeyValueStore

/** Local persistence (settings, roster, tournament) in private SharedPreferences. Never leaves the device. */
class PrefsStore(context: Context) : KeyValueStore {
    private val prefs = context.getSharedPreferences("clrg", Context.MODE_PRIVATE)
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String?) {
        val e = prefs.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }
}
