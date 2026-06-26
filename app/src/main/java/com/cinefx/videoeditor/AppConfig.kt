package com.cinefx.videoeditor

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val PREFS_NAME = "cinefx_config"
    private const val KEY_IS_PRO = "is_pro"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isProVersion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PRO, false)

    fun setProVersion(context: Context, isPro: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PRO, isPro).apply()
    }

    fun shouldAddWatermark(context: Context): Boolean = !isProVersion(context)
}
