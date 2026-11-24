// app/src/main/java/com/domain/app/pod/PodWorker.kt
package com.domain.app.pod

import android.content.Context
import android.util.Log
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
import com.domain.app.core.storage.dao.DataPointDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/** Copies the store to the pod every hour on a network, and when asked. */
@HiltWorker
class PodWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: DataPointDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = PodSync(applicationContext)
        if (!sync.connected) return Result.success()
        return runCatching { sync.sync(dao) }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                Log.w("Anthemos", "Pod sync failed", it)
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        )
    }

    companion object {
        private const val WORK = "pod-sync"
        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PodWorker>(1, TimeUnit.HOURS).setConstraints(online).build()
            )
        }

        fun now(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PodWorker>().setConstraints(online).build()
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}

/** For screens that aren't injected: the store's DAO. */
@dagger.hilt.EntryPoint
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
interface PodEntry {
    fun dao(): DataPointDao
}

/**
 * The way back from making a pod with Kronk: anthemos://pod/joined?code=<pickup code>,
 * or ?claim=<token>&name=<pod> when a pod with that name is already there and
 * the app asks for its own login to link it.
 */
object PodInbox {
    val code = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val claim = kotlinx.coroutines.flow.MutableStateFlow<Claim?>(null)

    data class Claim(val token: String, val name: String)

    fun receive(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "anthemos" || data.host != "pod" || data.path != "/joined") return
        code.value = data.getQueryParameter("code")?.takeIf(::plain)
        val token = data.getQueryParameter("claim")?.takeIf(::plain)
        val name = data.getQueryParameter("name")?.takeIf(::plain)
        if (token != null && name != null) claim.value = Claim(token, name)
    }

    private fun plain(s: String) = s.isNotEmpty() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
