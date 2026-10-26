package dz.association.app.core.util

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AppLocale {
    /** Arabic (Algeria) is the default; French is the secondary language. */
    fun current(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (!l.isEmpty && l[0]?.language == "fr") "fr" else "ar"
    }

    fun ensureDefault() {
        if (AppCompatDelegate.getApplicationLocales().isEmpty) set("ar")
    }

    fun set(tag: String) = AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))

    val isFrench get() = current() == "fr"
}

/** Picks the French text when the app is in French and a translation exists. */
fun loc(ar: String, fr: String?): String = if (AppLocale.isFrench && !fr.isNullOrBlank()) fr else ar
fun locOrNull(ar: String?, fr: String?): String? = if (AppLocale.isFrench && !fr.isNullOrBlank()) fr else ar

private val ALGIERS: TimeZone = TimeZone.getTimeZone("Africa/Algiers")

private fun fmt(pattern: String): SimpleDateFormat {
    // Latin digits are used in both languages (common practice in Algeria).
    val locale = if (AppLocale.isFrench) Locale.FRANCE else Locale.Builder().setLanguage("ar").setRegion("DZ").setExtension('u', "nu-latn").build()
    return SimpleDateFormat(pattern, locale).apply { timeZone = ALGIERS }
}

fun formatDate(ms: Long): String = fmt("d MMMM yyyy").format(Date(ms))
fun formatDateTime(ms: Long): String = fmt("d MMMM yyyy – HH:mm").format(Date(ms))
fun formatShortDate(ms: Long): String = fmt("d MMM").format(Date(ms))

/** Resolves server-relative media paths (e.g. "/media/x.jpg") against the API base. */
fun absoluteUrl(url: String?, base: String): String? = when {
    url.isNullOrBlank() -> null
    url.startsWith("https://") -> url
    url.startsWith("/") -> base.trimEnd('/') + url
    else -> null // never load http:// or other schemes
}
