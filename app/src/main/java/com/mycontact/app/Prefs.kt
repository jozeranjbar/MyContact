package com.mycontact.app

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Local storage — mirrors the original app's localStorage keys
 * (mycontact_display_name_v1, mycontact_turn_domain_v1, mycontact_turn_key_v1,
 * mycontact_font_scale_v1, mycontact_history_v1).
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("mycontact_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var displayName: String
        get() = sp.getString(KEY_DISPLAY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var turnDomain: String
        get() = sp.getString(KEY_TURN_DOMAIN, DEFAULT_TURN_DOMAIN) ?: ""
        set(value) = sp.edit().putString(KEY_TURN_DOMAIN, value).apply()

    var turnKey: String
        get() = sp.getString(KEY_TURN_KEY, DEFAULT_TURN_KEY) ?: ""
        set(value) = sp.edit().putString(KEY_TURN_KEY, value).apply()

    /** 0=small,1=medium,2=large,3=extra-large — mirrors FONT_SCALES index in the original. */
    var fontScaleIndex: Int
        get() = sp.getInt(KEY_FONT_SCALE, 1)
        set(value) = sp.edit().putInt(KEY_FONT_SCALE, value).apply()

    fun loadHistory(): MutableList<Message> {
        val raw = sp.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<Message>>() {}.type
            gson.fromJson(raw, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun saveHistory(history: List<Message>) {
        val trimmed = if (history.size > MAX_HISTORY) history.takeLast(MAX_HISTORY) else history
        sp.edit().putString(KEY_HISTORY, gson.toJson(trimmed)).apply()
    }

    fun clearHistory() = sp.edit().remove(KEY_HISTORY).apply()

    fun clearAll() = sp.edit().clear().apply()

    companion object {
        private const val KEY_DISPLAY_NAME = "mycontact_display_name_v1"
        private const val KEY_TURN_DOMAIN = "mycontact_turn_domain_v1"
        private const val KEY_TURN_KEY = "mycontact_turn_key_v1"
        private const val KEY_FONT_SCALE = "mycontact_font_scale_v1"
        private const val KEY_HISTORY = "mycontact_history_v1"
        private const val MAX_HISTORY = 400

        // TURN settings are intentionally blank in the Settings screen.
        // The built-in Metered TURN fallback is kept inside WebRtcManager.
        const val DEFAULT_TURN_DOMAIN = ""
        const val DEFAULT_TURN_KEY = ""
    }
}
