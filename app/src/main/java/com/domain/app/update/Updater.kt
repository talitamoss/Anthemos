// app/src/main/java/com/domain/app/update/Updater.kt
package com.domain.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.domain.app.ui.theme.Look
import com.domain.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** A newer build waiting on anthemos.org. */
data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val sha256: String)

sealed class UpdateState {
    object Idle : UpdateState()
    /** Asked for from Settings: looking now. */
    object Checking : UpdateState()
    /** Asked for from Settings: nothing newer. */
    object UpToDate : UpdateState()
    data class Available(val release: Release) : UpdateState()
    data class Downloading(val release: Release, val percent: Int) : UpdateState()
    object Installing : UpdateState()
    /** Android needs "Install unknown apps" allowed for Anthemos first. */
    data class NeedsPermission(val release: Release) : UpdateState()
    data class Failed(val message: String) : UpdateState()
}

/**
 * Updates Anthemos from the builds CI publishes to anthemos.org: reads
 * anthemos2-latest.json, and if its build is newer, downloads the APK,
 * checks its hash and hands it to Android's package installer.
 */
object Updater {
    /** A person's own copy follows its own builds, remade with their look (Look.channel). */
    private val BASE = "https://anthemos.org/builds/" + (Look.current.channel?.let { "u/$it/" } ?: "")
    private val MANIFEST = BASE + (if (Look.current.channel != null) "latest.json" else "anthemos2-latest.json")
    private const val ACTION_STATUS = "com.anthemos.UPDATE_STATUS"

    private val client = OkHttpClient()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Looks for a newer build; quiet when offline. */
    suspend fun check(asked: Boolean = false) = withContext(Dispatchers.IO) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return@withContext
        if (asked) _state.value = UpdateState.Checking
        val release = runCatching {
            client.newCall(Request.Builder().url(MANIFEST).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body!!.string())
                Release(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = BASE + json.getString("apk"),
                    sha256 = json.getString("sha256")
                )
            }
        }.getOrNull()
        _state.value = when {
            release != null && release.versionCode > BuildConfig.VERSION_CODE -> UpdateState.Available(release)
            asked && release == null -> UpdateState.Failed("Couldn't reach anthemos.org. Check the connection and try again.")
            asked -> UpdateState.UpToDate
            else -> UpdateState.Idle
        }
    }

    suspend fun install(context: Context, release: Release) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            return@withContext
        }
        try {
            val apk = download(app, release)
            _state.value = UpdateState.Installing
            commit(app, apk)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "The update didn't download")
        }
    }

    /** Opens the setting that lets Anthemos install its own updates. */
    fun permissionSettings(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun download(context: Context, release: Release): File {
        val file = File(context.cacheDir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) error("The update didn't download (${response.code})")
            val body = response.body!!
            val total = body.contentLength()
            var read = 0L
            var shown = -1
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = if (total > 0) (read * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            _state.value = UpdateState.Downloading(release, percent)
                        }
                    }
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            error("The download was damaged; try again")
        }
        return file
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once Anthemos installed itself, later updates need no extra tap.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("anthemos.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, UpdateReceiver::class.java).setAction(ACTION_STATUS),
                flags
            )
            session.commit(status.intentSender)
        }
    }

    internal fun onStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.Idle // the app restarts
            else -> _state.value = UpdateState.Failed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update didn't install"
            )
        }
    }
}

/** Hears back from the package installer. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onStatus(context, intent)
}
