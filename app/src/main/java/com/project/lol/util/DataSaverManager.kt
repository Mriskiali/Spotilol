package com.project.lol.util

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView
import com.project.lol.yt.AudioQuality

enum class DataSaverImageQuality {
    HIGH,
    MEDIUM,
    LOW;

    companion object {
        fun from(value: String?): DataSaverImageQuality =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: HIGH
    }
}

object DataSaverManager {
    const val PREFS_NAME = "spotilol_prefs"
    const val KEY_ENABLED = "data_saver_enabled"
    const val KEY_IMAGE_QUALITY = "data_saver_image_quality"

    private val BLOCKED_HOSTS = listOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "ads.yahoo.com",
        "analytics.spotify.com",
        "pixel.spotify.com",
        "log.spotify.com"
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    @JvmStatic
    fun imageQuality(context: Context): DataSaverImageQuality =
        DataSaverImageQuality.from(prefs(context).getString(KEY_IMAGE_QUALITY, null))

    @JvmStatic
    fun setImageQuality(context: Context, quality: DataSaverImageQuality) {
        prefs(context).edit().putString(KEY_IMAGE_QUALITY, quality.name).apply()
    }

    /**
     * Rewrites sized Spotify CDN artwork URLs to a lower-res variant.
     * misc.scdn.co assets embed the width (…-640.png); i.scdn.co/image URLs are
     * content-addressed with no size parameter, so they pass through unchanged.
     */
    @JvmStatic
    fun rewriteImageUrl(url: String?, quality: DataSaverImageQuality = DataSaverImageQuality.LOW): String? {
        if (url == null) return null
        if (quality == DataSaverImageQuality.HIGH) return url
        val target = if (quality == DataSaverImageQuality.MEDIUM) "300" else "64"
        return url.replace(Regex("-(640|300|160|64)(\\.[a-z]+)$")) { "-$target${it.groupValues[2]}" }
    }

    /** Integration point for shouldInterceptRequest: drop telemetry/ads when saver is on. */
    @JvmStatic
    fun shouldBlockRequest(context: Context, url: String): Boolean {
        if (!isEnabled(context)) return false
        val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()?.lowercase() ?: return false
        return BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** Data Saver forces low-bitrate audio; otherwise leave quality selection to AUTO. */
    @JvmStatic
    fun audioQuality(context: Context): AudioQuality =
        if (isEnabled(context)) AudioQuality.LOW else AudioQuality.AUTO

    /** Bridge snippet for the document-start payload; consumed by DataSaverHook.CONTENT. */
    @JvmStatic
    fun bridgeJs(context: Context): String {
        val on = isEnabled(context)
        val q = imageQuality(context).name.lowercase()
        return "window.__splDataSaver=$on;window.__splDsImgQ='$q';"
    }

    /** Push a live toggle to an already-loaded WebView without reload. */
    @JvmStatic
    fun applyToWebView(view: WebView, enabled: Boolean) {
        view.evaluateJavascript(
            "window.__splDataSaver=$enabled;" +
                "if(window.__splApplyDataSaver) window.__splApplyDataSaver($enabled);",
            null
        )
    }
}
