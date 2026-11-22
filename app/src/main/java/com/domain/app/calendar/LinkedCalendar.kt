// app/src/main/java/com/domain/app/calendar/LinkedCalendar.kt
package com.domain.app.calendar

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * A calendar read through its share link: Proton's "Share with anyone"
 * (docs/anthemos-2.md, "Calendar"). Read only; adding still goes through
 * Proton's own screen. The link is kept encrypted, since anyone with it
 * can read the calendar; the last copy is kept so answers work offline.
 */
class LinkedCalendar(private val context: Context) {

    class LinkError(message: String) : Exception(message)

    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "anthemos_calendar", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val copy get() = File(context.filesDir, "linked-calendar.ics")

    val linked: Boolean get() = prefs.getString(LINK, null) != null

    /** Why the last [events] had nothing fresh: the fetch failed (and no copy, or an old one). */
    @Volatile var problem: String? = null
        private set

    /** Checks the link reads as a calendar, then keeps it. The number of events in it. */
    fun link(url: String): Int {
        val text = fetch(url)
        val count = Ics.parse(text, ZoneId.systemDefault()).size
        prefs.edit().putString(LINK, url).putLong(FETCHED, System.currentTimeMillis()).apply()
        copy.writeText(text)
        return count
    }

    fun forget() {
        prefs.edit().clear().apply()
        copy.delete()
    }

    /** Events between [from] and [until]: fetched if the copy is old, else (or offline) the copy. */
    fun events(from: Instant, until: Instant): List<Event> {
        val url = prefs.getString(LINK, null) ?: return emptyList()
        val text = if (System.currentTimeMillis() - prefs.getLong(FETCHED, 0) > FRESH_MS || !copy.exists()) {
            runCatching { fetch(url) }.onSuccess {
                copy.writeText(it)
                prefs.edit().putLong(FETCHED, System.currentTimeMillis()).apply()
                problem = null
            }.onFailure { problem = it.message ?: it.javaClass.simpleName }.getOrNull()
        } else null
        val ics = text ?: copy.takeIf { it.exists() }?.readText() ?: return emptyList()
        return Ics.expand(Ics.parse(ics, ZoneId.systemDefault()), from, until)
    }

    private fun fetch(url: String): String {
        val request = Request.Builder().url(url.replaceFirst(Regex("^webcal://"), "https://")).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw LinkError("The calendar link didn't open (${response.code})")
            val text = response.body?.string().orEmpty()
            if (!text.contains("BEGIN:VCALENDAR")) throw LinkError("That link isn't a calendar")
            return text
        }
    }

    companion object {
        private const val LINK = "link"
        private const val FETCHED = "fetched"
        private const val FRESH_MS = 10 * 60 * 1000L

        /** A calendar share link in what was typed or copied. */
        fun find(text: String): String? =
            Regex("(?:https|webcal)://\\S*(?:calendar\\.proton\\.me|\\.ics)\\S*", RegexOption.IGNORE_CASE).find(text)?.value?.trimEnd('.', ',', ')')
    }
}
