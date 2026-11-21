// app/src/main/java/com/domain/app/place/LocationService.kt
package com.domain.app.place

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.domain.app.MainActivity
import com.domain.app.calendar.RemindersEntryPoint
import com.domain.app.core.data.DataPoint
import com.domain.app.core.data.DataRepository
import com.domain.app.kronk.Kronk
import com.domain.app.voice.Entry
import com.domain.app.voice.Item
import com.domain.app.voice.RuleReader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** A live share running from this phone. */
data class LiveShare(val id: String, val names: List<String>, val until: Instant)

/**
 * What location is doing, kept between runs: whether the trail is on, the
 * stay going on now, and the live shares running.
 */
class LocationState(context: Context) {
    private val prefs = context.getSharedPreferences("anthemos_location", Context.MODE_PRIVATE)

    var trail: Boolean
        get() = prefs.getBoolean("trail", false)
        set(on) = prefs.edit().putBoolean("trail", on).apply()

    var shares: List<LiveShare>
        get() = runCatching {
            val rows = JSONArray(prefs.getString("shares", "[]"))
            (0 until rows.length()).map { i ->
                val o = rows.getJSONObject(i)
                val names = o.getJSONArray("names")
                LiveShare(o.getString("id"), (0 until names.length()).map(names::getString), Instant.ofEpochSecond(o.getLong("until")))
            }
        }.getOrDefault(emptyList()).filter { it.until.isAfter(Instant.now()) }
        set(list) = prefs.edit().putString(
            "shares",
            JSONArray(list.map { JSONObject().put("id", it.id).put("names", JSONArray(it.names)).put("until", it.until.epochSecond) }).toString()
        ).apply()

    var tracker: StayTracker
        get() = StayTracker(
            prefs.getFloat("stay_lat", 0f).toDouble(),
            prefs.getFloat("stay_lng", 0f).toDouble(),
            prefs.getLong("stay_start", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond),
            prefs.getLong("stay_last", 0).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
        )
        set(t) = prefs.edit()
            .putFloat("stay_lat", t.lat.toFloat()).putFloat("stay_lng", t.lng.toFloat())
            .putLong("stay_start", t.start?.epochSecond ?: 0).putLong("stay_last", t.last?.epochSecond ?: 0)
            .apply()

    /** The last reading, for "where am I" when nothing fresher comes. */
    var lastFix: Fix?
        get() = prefs.getLong("fix_time", 0).takeIf { it > 0 }?.let {
            Fix(prefs.getFloat("fix_lat", 0f).toDouble(), prefs.getFloat("fix_lng", 0f).toDouble(), prefs.getFloat("fix_acc", 0f), Instant.ofEpochSecond(it))
        }
        set(fix) {
            fix ?: return
            prefs.edit().putFloat("fix_lat", fix.lat.toFloat()).putFloat("fix_lng", fix.lng.toFloat())
                .putFloat("fix_acc", fix.accuracy).putLong("fix_time", fix.time.epochSecond).apply()
        }
}

/**
 * Follows the phone's location while the trail is on or a live share runs
 * (docs/anthemos-2.md, "Places"). A foreground service, so Android shows
 * that it is running and it keeps going with the screen off.
 *
 * The trail keeps readings in the encrypted store and turns time spent in
 * one spot into an entry ("At home, 2 hours"). A live share sends each
 * reading to Kronk, signed, until it ends. Plain GPS and the phone's own
 * network location: GrapheneOS has no Google location service, and nothing
 * here asks a map service anything.
 */
class LocationService : Service(), LocationListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private lateinit var state: LocationState
    private lateinit var kronk: Kronk
    private lateinit var repository: DataRepository
    private var sharing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        state = LocationState(this)
        kronk = Kronk(this)
        repository = EntryPointAccessors.fromApplication(applicationContext, RemindersEntryPoint::class.java).dataRepository()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SHARING) {
            val shares = state.shares
            state.shares = emptyList()
            scope.launch { shares.forEach { runCatching { kronk.stopSharing(it.id) } } }
        }
        val shares = state.shares
        if (!state.trail && shares.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!allowed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = notification(shares)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        listen(shares.isNotEmpty())
        return START_STICKY
    }

    @Suppress("MissingPermission") // allowed() is checked before this runs
    private fun listen(live: Boolean) {
        val manager = getSystemService(LocationManager::class.java)
        manager.removeUpdates(this)
        sharing = live
        // Live: about every 30 seconds, wherever. Trail: every two minutes, or on moving 50 m.
        val every = if (live) 30_000L else 120_000L
        val metres = if (live) 0f else 50f
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { manager.requestLocationUpdates(it, every, metres, this, Looper.getMainLooper()) }
    }

    override fun onLocationChanged(location: Location) {
        val fix = Fix(location.latitude, location.longitude, location.accuracy, Instant.ofEpochMilli(location.time))
        scope.launch { lock.withLock { take(fix) } }
    }

    private suspend fun take(fix: Fix) {
        val previous = state.lastFix
        // A network reading right after a better GPS one only blurs it.
        if (previous != null && fix.accuracy > previous.accuracy * 3 && fix.time.epochSecond - previous.time.epochSecond < 60) return
        state.lastFix = fix

        if (state.trail) {
            repository.saveDataPoint(
                DataPoint(
                    pluginId = TRAIL_ID, timestamp = fix.time, type = "fix",
                    value = mapOf("lat" to fix.lat, "lng" to fix.lng, "accuracy" to fix.accuracy.toDouble()),
                    source = "phone"
                )
            )
            val tracker = state.tracker
            tracker.add(fix)?.let { keep(it) }
            state.tracker = tracker
        }

        val shares = state.shares
        val still = shares.filter { share ->
            runCatching { kronk.sendPoint(share.id, fix.lat, fix.lng, fix.accuracy, fix.time) }.getOrDefault(true)
        }
        if (still.size != shares.size) {
            state.shares = still
            refresh(this)
        } else if (still.isNotEmpty() != sharing) {
            refresh(this)
        }
    }

    /** A stay that has ended, kept as an entry the phone noticed. */
    private suspend fun keep(stay: Stay) {
        val places = repository.getPluginData(Place.PLUGIN_ID).first().mapNotNull(Place::from)
        val place = Geo.placeAt(places, stay.lat, stay.lng)
        val name = place?.name ?: "somewhere new"
        val words = "${if (place != null) "At" else "Stayed"} $name, ${span(stay)}"
        val entry = Entry(
            UUID.randomUUID().toString(), stay.start, words,
            listOf(Item("place", name, minutes = stay.minutes.toInt())), RuleReader.NAME, source = "phone"
        )
        repository.saveDataPoint(entry.toDataPoint())
    }

    private fun span(stay: Stay): String {
        val zone = ZoneId.systemDefault()
        return "${CLOCK.format(stay.start.atZone(zone)).lowercase()} to ${CLOCK.format(stay.end.atZone(zone)).lowercase()}"
    }

    private fun notification(shares: List<LiveShare>): android.app.Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Location", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while Anthemos follows your location"
                }
            )
        }
        val immutable = PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), immutable)
        val zone = ZoneId.systemDefault()
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
        if (shares.isEmpty()) {
            builder.setContentTitle("Keeping your trail").setContentText("Only on this phone")
        } else {
            val names = shares.flatMap { it.names }.distinct().joinToString(", ")
            val until = CLOCK.format(shares.maxOf { it.until }.atZone(zone)).lowercase()
            val stop = PendingIntent.getService(this, 1, Intent(this, LocationService::class.java).setAction(ACTION_STOP_SHARING), immutable)
            builder.setContentTitle("Sharing your location with $names")
                .setContentText("Until $until, through Kronk")
                .addAction(0, "Stop sharing", stop)
        }
        return builder.build()
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TRAIL_ID = "trail"
        private const val CHANNEL = "location"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_STOP_SHARING = "com.anthemos.STOP_SHARING"
        private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

        fun allowed(context: Context) =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts, updates or stops the service to match [LocationState]. */
        fun refresh(context: Context) {
            val state = LocationState(context)
            val intent = Intent(context, LocationService::class.java)
            if (!state.trail && state.shares.isEmpty()) {
                context.stopService(intent)
                return
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
