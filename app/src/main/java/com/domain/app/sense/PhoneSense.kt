// app/src/main/java/com/domain/app/sense/PhoneSense.kt
package com.domain.app.sense

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** A stretch of the screen being on. */
data class ScreenOn(val from: Instant, val until: Instant)

/** Sleep as the phone saw it: the long gap in use overnight. */
data class SleepGuess(val bed: Instant, val up: Instant) {
    val minutes: Int get() = Duration.between(bed, up).toMinutes().toInt()
}

/**
 * What the phone already knows, read only with permission (docs/anthemos-2.md,
 * "What the phone already knows"). Screen time and estimated sleep come from
 * Android's usage access, which the person turns on in Settings. Nothing
 * here leaves the phone.
 */
class PhoneSense(private val context: Context) {

    fun allowed(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun settings(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** When the screen was on between [from] and [until]. Android 9+. */
    fun screenOn(from: Instant, until: Instant): List<ScreenOn> {
        if (!allowed() || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = usage.queryEvents(from.toEpochMilli(), until.toEpochMilli())
        val spans = mutableListOf<ScreenOn>()
        var on: Instant? = null
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val t = Instant.ofEpochMilli(e.timeStamp)
            when (e.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE -> if (on == null) on = t
                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> on?.let { spans += ScreenOn(it, t); on = null }
            }
        }
        on?.let { spans += ScreenOn(it, minOf(until, Instant.now())) }
        return spans
    }

    fun screenMinutes(from: Instant, until: Instant): Int =
        screenOn(from, until).sumOf { Duration.between(it.from, it.until).toMinutes() }.toInt()

    /** Apps by time in front, most first: (name, minutes). */
    fun topApps(from: Instant, until: Instant, count: Int = 3): List<Pair<String, Int>> {
        if (!allowed()) return emptyList()
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        return usage.queryAndAggregateUsageStats(from.toEpochMilli(), until.toEpochMilli()).values
            .filter { it.packageName != context.packageName && it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(count)
            .map { stats ->
                val name = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(stats.packageName, 0)).toString()
                } catch (e: PackageManager.NameNotFoundException) {
                    stats.packageName
                }
                name to (stats.totalTimeInForeground / 60_000).toInt()
            }
    }

    /** Last night's sleep, guessed from phone use (see [Sleep.guess]). */
    fun lastNight(now: ZonedDateTime): SleepGuess? {
        val midnight = now.truncatedTo(ChronoUnit.DAYS)
        val from = midnight.minusHours(6).toInstant()   // 6 pm yesterday
        val until = minOf(midnight.plusHours(13).toInstant(), now.toInstant())
        return Sleep.guess(screenOn(from, until), from, until)
    }
}

object Sleep {
    /**
     * The longest gap in phone use between [from] and [until], of three
     * hours or more. Glances under five minutes (checking the time) don't
     * break it.
     */
    fun guess(spans: List<ScreenOn>, from: Instant, until: Instant): SleepGuess? {
        val real = spans.filter { Duration.between(it.from, it.until).toMinutes() >= 5 }.sortedBy { it.from }
        if (real.isEmpty()) return null
        var best: SleepGuess? = null
        for ((a, b) in real.zipWithNext()) {
            val gap = SleepGuess(a.until, b.from)
            if (gap.minutes >= 180 && (best == null || gap.minutes > best.minutes)) best = gap
        }
        return best
    }
}
