// app/src/main/java/com/domain/app/kronk/Kronk.kt
package com.domain.app.kronk

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Anthemos's link to Kronk (docs/anthemos-2.md, "Kronk"; Kronk's
 * docs/spaces/map.md, Live location).
 *
 * Signs in with Kronk like any app (OAuth), then links this phone: it makes
 * a P-256 key in the phone's keystore, which never leaves it, and gives Kronk
 * the public half. Everything shared after that is signed with the key, so
 * Kronk can show it as coming from Anthemos. The token is kept encrypted.
 *
 * Blocking calls: run them off the main thread.
 */
class Kronk(private val context: Context) {

    data class Mate(val id: String, val name: String, val handle: String)

    class KronkError(message: String) : Exception(message)

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "anthemos_kronk", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val server: String get() = prefs.getString(SERVER, null) ?: DEFAULT_SERVER

    /** Signed in and this phone linked. */
    val linked: Boolean get() = prefs.getString(LINK_ID, null) != null && prefs.getString(TOKEN, null) != null

    // ── Signing in ────────────────────────────────────────────────────

    /** The page to open in the browser to sign in; registers Anthemos with the server first. */
    fun signInPage(): Uri {
        val app = JSONObject(
            post(
                "/api/v1/apps",
                FormBody.Builder()
                    .add("client_name", "Anthemos")
                    .add("redirect_uris", REDIRECT)
                    .add("scopes", SCOPES)
                    .add("website", "https://anthemos.org")
                    .build(),
                auth = false
            )
        )
        prefs.edit()
            .putString(CLIENT_ID, app.getString("client_id"))
            .putString(CLIENT_SECRET, app.getString("client_secret"))
            .apply()
        return Uri.parse("$server/oauth/authorize").buildUpon()
            .appendQueryParameter("client_id", app.getString("client_id"))
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES)
            .build()
    }

    /** The browser came back with a code: get a token, then link this phone. */
    fun finishSignIn(code: String) {
        val token = JSONObject(
            post(
                "/oauth/token",
                FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("client_id", prefs.getString(CLIENT_ID, null) ?: throw KronkError("Sign in again"))
                    .add("client_secret", prefs.getString(CLIENT_SECRET, null).orEmpty())
                    .add("redirect_uri", REDIRECT)
                    .add("scope", SCOPES)
                    .build(),
                auth = false
            )
        ).getString("access_token")
        prefs.edit().putString(TOKEN, token).apply()

        val me = JSONObject(get("/api/v1/accounts/verify_credentials"))
        val publicKey = Base64.encodeToString(publicKey(), Base64.NO_WRAP)
        val link = JSONObject(
            postJson(
                "/api/v1/anthemos/links",
                JSONObject()
                    .put("public_key", publicKey)
                    .put("device_name", Build.MODEL.take(60))
                    .put("signature", sign("anthemos-link-v1", me.getString("id"), publicKey))
            )
        )
        prefs.edit()
            .putString(ACCOUNT, me.getString("acct"))
            .putString(LINK_ID, link.getString("id"))
            .apply()
    }

    fun forget() = prefs.edit().remove(TOKEN).remove(LINK_ID).remove(ACCOUNT).remove(SHARES).remove(MATES).apply()

    // ── Mates and live location ───────────────────────────────────────

    /** Mates whose name or handle fits [words]. */
    fun mates(words: String): List<Mate> {
        val url = "$server/api/v1/kommunity/mates".toHttpUrl().newBuilder()
            .addQueryParameter("q", words).addQueryParameter("limit", "10").build()
        return matesFrom(JSONArray(call(Request.Builder().url(url).get())))
    }

    /**
     * Every Mate, kept on the phone for a day so names can be matched by how
     * they sound ([SoundsLike]); [fresh] asks Kronk again.
     */
    fun allMates(fresh: Boolean = false): List<Mate> {
        val kept = prefs.getString(MATES, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (!fresh && kept != null && System.currentTimeMillis() - kept.optLong("at") < MATES_KEPT_MS) return matesFrom(kept.getJSONArray("mates"))
        val all = mutableListOf<Mate>()
        var offset = 0
        while (offset < MATES_MOST) {
            val url = "$server/api/v1/kommunity/mates".toHttpUrl().newBuilder()
                .addQueryParameter("limit", "80").addQueryParameter("offset", offset.toString()).build()
            val page = matesFrom(JSONArray(call(Request.Builder().url(url).get())))
            all += page
            if (page.size < 80) break
            offset += 80
        }
        val rows = JSONArray(all.map { JSONObject().put("id", it.id).put("display_name", it.name).put("acct", it.handle) })
        prefs.edit().putString(MATES, JSONObject().put("at", System.currentTimeMillis()).put("mates", rows).toString()).apply()
        return all
    }

    /** How many Mates are kept on the phone. */
    val keptMates: Int get() = prefs.getString(MATES, null)?.let { runCatching { JSONObject(it).getJSONArray("mates").length() }.getOrNull() } ?: 0

    /** One account, by id, as a Mate (for a share started on Kronk). */
    fun account(id: String): Mate = matesFrom(JSONArray().put(JSONObject(get("/api/v1/accounts/$id")))).first()

    private fun matesFrom(rows: JSONArray) = (0 until rows.length()).map { i ->
        val a = rows.getJSONObject(i)
        Mate(a.getString("id"), a.optString("display_name").ifBlank { a.optString("username").ifBlank { a.getString("acct") } }, a.getString("acct"))
    }

    /** Starts sharing with [mates] until [until]; returns the share's id. */
    fun startSharing(mates: List<Mate>, until: Instant): String {
        val linkId = prefs.getString(LINK_ID, null) ?: throw KronkError("Connect to Kronk first")
        val ids = mates.map { it.id.toLong() }.distinct().sorted()
        val expires = until.epochSecond.toString()
        val share = JSONObject(
            postJson(
                "/api/v1/map/live",
                JSONObject()
                    .put("link_id", linkId)
                    .put("recipient_ids", JSONArray(ids.map { it.toString() }))
                    .put("expires_at", expires)
                    .put("signature", sign("anthemos-share-v1", linkId, ids.joinToString(","), expires))
            )
        )
        return share.getString("id")
    }

    /** False when Kronk says the share has ended. */
    fun sendPoint(shareId: String, lat: Double, lng: Double, accuracy: Float, at: Instant): Boolean {
        val sent = listOf(
            "%.6f".format(Locale.ROOT, lat),
            "%.6f".format(Locale.ROOT, lng),
            accuracy.toInt().coerceAtLeast(0).toString(),
            at.epochSecond.toString()
        )
        val body = JSONObject()
            .put("lat", sent[0]).put("lng", sent[1]).put("accuracy", sent[2]).put("at", sent[3])
            .put("signature", sign("anthemos-live-v1", shareId, *sent.toTypedArray()))
        val request = Request.Builder().url("$server/api/v1/map/live/$shareId/point")
            .put(body.toString().toRequestBody(JSON))
        authorise(request).build().let(http::newCall).execute().use { response ->
            if (response.code == 410 || response.code == 404) return false
            if (!response.isSuccessful) throw KronkError("Kronk said ${response.code}")
        }
        return true
    }

    /** Who this phone is linked as ("tal"), once connected. */
    val account: String? get() = prefs.getString(ACCOUNT, null)

    /** Unlinks this phone on Kronk (its shares stop there too), then forgets the sign-in. */
    fun disconnect() {
        val link = prefs.getString(LINK_ID, null)
        if (link != null) {
            runCatching {
                val request = Request.Builder().url("$server/api/v1/anthemos/links/$link").delete()
                authorise(request).build().let(http::newCall).execute().close()
            }
        }
        forget()
    }

    fun stopSharing(shareId: String) {
        val request = Request.Builder().url("$server/api/v1/map/live/$shareId").delete()
        authorise(request).build().let(http::newCall).execute().close()
    }

    // ── The phone's key ───────────────────────────────────────────────

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun publicKey(): ByteArray {
        val store = keyStore()
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }.generateKeyPair()
        }
        return store.getCertificate(KEY_ALIAS).publicKey.encoded
    }

    /** Signs lines joined with "\n", as Kronk checks them. */
    private fun sign(vararg parts: String): String {
        val key = keyStore().getKey(KEY_ALIAS, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(key)
            update(parts.joinToString("\n").toByteArray())
        }.sign()
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    // ── HTTP ──────────────────────────────────────────────────────────

    private fun authorise(builder: Request.Builder): Request.Builder {
        val token = prefs.getString(TOKEN, null) ?: throw KronkError("Connect to Kronk first")
        return builder.header("Authorization", "Bearer $token")
    }

    private fun get(path: String) = call(Request.Builder().url("$server$path").get())

    private fun post(path: String, body: FormBody, auth: Boolean = true) =
        call(Request.Builder().url("$server$path").post(body), auth)

    private fun postJson(path: String, body: JSONObject) =
        call(Request.Builder().url("$server$path").post(body.toString().toRequestBody(JSON)))

    private fun call(builder: Request.Builder, auth: Boolean = true): String {
        val request = (if (auth) authorise(builder) else builder).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401) {
                forget()
                throw KronkError("Kronk signed Anthemos out. Say connect to Kronk.")
            }
            if (!response.isSuccessful) {
                val said = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw KronkError(said.ifBlank { "Kronk said ${response.code}" })
            }
            return text
        }
    }

    companion object {
        /** Kronk's test server, until live location is released to kronk.info. */
        const val DEFAULT_SERVER = "https://shadow.kronk.info"
        const val REDIRECT = "anthemos://kronk"
        private const val SCOPES = "read write"
        private const val KEY_ALIAS = "anthemos-kronk"
        private val JSON = "application/json".toMediaType()

        private const val SERVER = "server"
        private const val CLIENT_ID = "client_id"
        private const val CLIENT_SECRET = "client_secret"
        private const val TOKEN = "token"
        private const val ACCOUNT = "account"
        private const val LINK_ID = "link_id"
        private const val SHARES = "shares"
        private const val MATES = "mates"
        private const val MATES_KEPT_MS = 24 * 60 * 60 * 1000L
        private const val MATES_MOST = 800
    }
}

/**
 * The browser coming back from signing in to Kronk (anthemos://kronk?code=),
 * waiting for Speak to finish linking.
 */
object KronkInbox {
    val code = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** "Share live location" tapped on Kronk's Map: anthemos://kronk/share?to=<account id>&server=<host>. */
    data class Share(val to: String, val server: String)

    val share = kotlinx.coroutines.flow.MutableStateFlow<Share?>(null)

    fun receive(intent: android.content.Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "anthemos" || data.host != "kronk") return
        if (data.path == "/share") {
            val to = data.getQueryParameter("to")?.takeIf { it.all(Char::isDigit) && it.isNotEmpty() } ?: return
            share.value = Share(to, data.getQueryParameter("server").orEmpty())
            return
        }
        code.value = data.getQueryParameter("code") ?: ""
    }
}
