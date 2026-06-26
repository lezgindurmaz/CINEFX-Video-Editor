package com.cinefx.videoeditor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object AppConfig {
    private const val PREFS_NAME = "cinefx_config"
    private const val KEY_IS_PRO = "is_pro"
    private const val KEY_LICENSE_KEY = "license_key"

    // TODO: Replace with your actual Gumroad Product ID
    const val GUMROAD_PRODUCT_ID = "YOUR_PRODUCT_ID_HERE"

    // Free version limits
    const val FREE_MAX_EXPORT_HEIGHT = 720
    const val PRO_MAX_EXPORT_HEIGHT = 2160
    val FREE_FILTERS = listOf(FilterType.GRAYSCALE, FilterType.SEPIA)
    val PRO_FILTERS = FilterType.values().toList()
    const val FREE_MAX_JOIN_VIDEOS = 1
    const val PRO_MAX_JOIN_VIDEOS = 10

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isProVersion(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_PRO, false)

    fun setProVersion(context: Context, isPro: Boolean) {
        prefs(context).edit().putBoolean(KEY_IS_PRO, isPro).apply()
    }

    fun getLicenseKey(context: Context): String =
        prefs(context).getString(KEY_LICENSE_KEY, "") ?: ""

    fun saveLicenseKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_LICENSE_KEY, key).apply()
    }

    fun shouldAddWatermark(context: Context): Boolean = !isProVersion(context)

    fun getMaxExportHeight(context: Context): Int =
        if (isProVersion(context)) PRO_MAX_EXPORT_HEIGHT else FREE_MAX_EXPORT_HEIGHT

    fun getAvailableFilters(context: Context): List<FilterType> =
        if (isProVersion(context)) PRO_FILTERS else FREE_FILTERS

    fun canJoinVideo(context: Context): Boolean = isProVersion(context)

    /**
     * Verify a Gumroad license key via their public API.
     * Returns true if the license is valid.
     */
    suspend fun verifyGumroadLicense(licenseKey: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.gumroad.com/v2/licenses/verify")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "product_id=$GUMROAD_PRODUCT_ID&license_key=$licenseKey"
                connection.outputStream.write(postData.toByteArray())

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    response.contains("\"success\":true")
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
    }

    /**
     * Activate Pro with a license key. Returns true if successful.
     */
    suspend fun activateWithLicense(context: Context, licenseKey: String): Boolean {
        val isValid = verifyGumroadLicense(licenseKey)
        if (isValid) {
            saveLicenseKey(context, licenseKey)
            setProVersion(context, true)
        }
        return isValid
    }
}
