// app/src/main/java/com/domain/app/backup/PhoneBackup.kt
package com.domain.app.backup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.domain.app.pod.PodClient
import com.domain.app.pod.PodSync
import com.domain.app.pod.Seal
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The phone's contacts, texts and call log, kept on your pod and put back
 * on a new or wiped phone (docs/anthemos-2.md, "Phone backup"). Sealed
 * with the pod's key like everything else, under `anthemos/phone/v1/`.
 */
class PhoneBackup(private val context: Context) {

    data class Status(val on: Boolean, val last: Instant?, val problem: String?, val index: Pack.Index?)

    private val prefs = context.getSharedPreferences("anthemos_phone_backup", Context.MODE_PRIVATE)
    private val data = PhoneData(context)

    val on get() = prefs.getBoolean(ON, false)

    fun turn(on: Boolean) {
        prefs.edit().putBoolean(ON, on).apply()
        if (on) BackupWorker.schedule(context) else BackupWorker.stop(context)
    }

    fun status() = Status(
        on, prefs.getLong(LAST, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli), prefs.getString(PROBLEM, null),
        prefs.getString(INDEX, null)?.let { runCatching { Pack.Index.decode(it.toByteArray()) }.getOrNull() }
    )

    /** Whether Anthemos may read this kind; the ones it may not are left out. */
    fun allowed(kind: String) = READ[kind]!!.let { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Texts waiting to be put back once Anthemos is the texting app. Kept, not
     * held on a screen: becoming the texting app restarts Anthemos (2.40).
     */
    var textsWaiting: Boolean
        // Ten minutes: a request turned down or left doesn't wait for ever.
        get() = System.currentTimeMillis() - prefs.getLong(TEXTS_WAITING, 0) < 10 * 60_000
        set(value) = prefs.edit().putLong(TEXTS_WAITING, if (value) System.currentTimeMillis() else 0).apply()

    /** Texts can only be written by the phone's texting app, so Anthemos is that for the moment it restores them. */
    fun isTextingApp() = TextingApp.held(context)

    /** Adds what's new on the phone to the pod's copy. What the pod holds after. */
    fun backup(): Pack.Index {
        val (client, seal) = PodSync(context).open() ?: throw PodClient.PodError("Connect a pod first")
        try {
            val counts = index(client, seal)?.counts.orEmpty().toMutableMap()
            val seen = mutableMapOf<String, String>()
            for (kind in Pack.KINDS.filter(::allowed)) {
                for ((bundle, items) in Pack.group(kind, data.read(kind))) {
                    val hash = Pack.hash(kind, items)
                    if (prefs.getString(SEEN + bundle, null) == hash && bundle in counts) continue
                    // Always what's there, even when the list doesn't name it: a run cut short may have sent it.
                    val there = client.get(path(seal, bundle))?.let { Pack.decode(seal.open(it)) }.orEmpty()
                    val merged = Pack.merge(kind, items, there)
                    client.put(path(seal, bundle), seal.seal(Pack.encode(merged)))
                    counts[bundle] = merged.size
                    seen[bundle] = hash
                }
            }
            val index = Pack.Index(counts, System.currentTimeMillis(), "${Build.MANUFACTURER} ${Build.MODEL}")
            client.put(path(seal, INDEX_NAME), seal.seal(index.encode()))
            prefs.edit().apply {
                seen.forEach { (bundle, hash) -> putString(SEEN + bundle, hash) }
                putString(INDEX, String(index.encode()))
                putLong(LAST, index.made)
                remove(PROBLEM)
            }.apply()
            return index
        } catch (e: Exception) {
            prefs.edit().putString(PROBLEM, e.message ?: e.javaClass.simpleName).apply()
            throw e
        }
    }

    /** What the pod holds, without changing anything; null when nothing is backed up yet. */
    fun look(): Pack.Index? {
        val (client, seal) = PodSync(context).open() ?: throw PodClient.PodError("Connect a pod first")
        return index(client, seal)?.also { prefs.edit().putString(INDEX, String(it.encode())).apply() }
    }

    /** Puts back what this phone is missing, for each kind asked. How many of each were added. */
    fun restore(kinds: List<String>, progress: (String) -> Unit = {}, done: (Int, Int) -> Unit = { _, _ -> }): Map<String, Int> {
        val (client, seal) = PodSync(context).open() ?: throw PodClient.PodError("Connect a pod first")
        val index = index(client, seal) ?: throw PodClient.PodError("Nothing is backed up on the pod yet")
        return kinds.associateWith { kind ->
            if (kind == Pack.TEXTS && !isTextingApp()) throw PodClient.PodError("Anthemos needs to be the texting app while it puts texts back")
            progress(NAMES[kind]!!)
            val items = index.counts.keys.filter { Pack.kindOf(it) == kind }.flatMap { bundle ->
                client.get(path(seal, bundle))?.let { Pack.decode(seal.open(it)) }.orEmpty()
            }
            data.write(kind, items, done)
        }
    }

    private fun index(client: PodClient, seal: Seal) = client.get(path(seal, INDEX_NAME))?.let { Pack.Index.decode(seal.open(it)) }

    private fun path(seal: Seal, bundle: String) = DIR + seal.name("phone:$bundle")

    companion object {
        const val DIR = "anthemos/phone/v1/"
        private const val INDEX_NAME = "index"
        private const val ON = "on"
        private const val LAST = "last"
        private const val PROBLEM = "problem"
        private const val INDEX = "index"
        private const val SEEN = "seen:"
        private const val TEXTS_WAITING = "texts_waiting"

        val NAMES = mapOf(Pack.CONTACTS to "contacts", Pack.TEXTS to "texts", Pack.CALLS to "calls")
        private val READ = mapOf(
            Pack.CONTACTS to Manifest.permission.READ_CONTACTS,
            Pack.TEXTS to Manifest.permission.READ_SMS,
            Pack.CALLS to Manifest.permission.READ_CALL_LOG
        )
        /** Asked for when backup is turned on: reading all three, and writing contacts and calls back. */
        val PERMISSIONS = arrayOf(
            Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
            Manifest.permission.READ_SMS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG
        )
    }
}

/** Once a day, on a network and not on a low battery. */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val backup = PhoneBackup(applicationContext)
        if (!backup.on || !PodSync(applicationContext).connected) return Result.success()
        return runCatching { backup.backup() }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.w("Anthemos", "Phone backup failed", it)
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        )
    }

    companion object {
        private const val WORK = "phone-backup"
        private val conditions = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS).setConstraints(conditions).build()
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(conditions).build()
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}
