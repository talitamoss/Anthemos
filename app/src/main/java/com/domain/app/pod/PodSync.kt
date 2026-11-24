// app/src/main/java/com/domain/app/pod/PodSync.kt
package com.domain.app.pod

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.domain.app.core.storage.dao.DataPointDao
import com.domain.app.core.storage.entity.DataPointEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import java.time.Instant

/**
 * Everything in the encrypted store, copied to your pod, sealed on the
 * phone first (docs/anthemos-2.md, "Pod"). One file per item under
 * `anthemos/v1/`, named by [Seal.name]. Sending is one way: what's new
 * or changed goes up, what was deleted here is deleted there. The other
 * way only at connecting, to bring a pod's copy onto a new phone with
 * its recovery code. Two phones writing to one pod comes later.
 */
class PodSync(private val context: Context) {

    data class Status(val connected: Boolean, val pod: String?, val items: Int, val last: Instant?, val problem: String?)

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "anthemos_pod", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val connected get() = prefs.getString(POD, null) != null

    fun status() = Status(
        connected, prefs.getString(POD, null), prefs.getInt(ITEMS, 0),
        prefs.getLong(LAST, 0).takeIf { it > 0 }?.let(Instant::ofEpochMilli), prefs.getString(PROBLEM, null)
    )

    fun recoveryCode(): String? = seal()?.recoveryCode()

    /**
     * Checks the credential, then keeps it. A pod that already holds
     * Anthemos files needs their [recovery] code, and they are brought
     * onto this phone. The recovery code to keep, on success.
     */
    suspend fun connect(dao: DataPointDao, podUrl: String, id: String, secret: String, recovery: String?): String {
        val client = PodClient(podUrl, id, secret)
        client.check()
        val there = client.list(DIR)
        val seal = if (recovery.isNullOrBlank()) {
            if (there.isNotEmpty()) throw PodClient.PodError("This pod already has Anthemos data. Enter its recovery code.")
            Seal.fresh()
        } else {
            Seal.fromCode(recovery) ?: throw PodClient.PodError("That isn't a recovery code")
        }
        if (there.isNotEmpty()) {
            val sample = client.get(DIR + there.first())
            if (sample != null && runCatching { seal.open(sample) }.isFailure) throw PodClient.PodError("That recovery code doesn't open this pod's data")
            val here = dao.getAllDataPoints().map { seal.name(it.id) }.toSet()
            for (name in there.filter { it !in here }) {
                val sealed = client.get(DIR + name) ?: continue
                runCatching { decode(seal.open(sealed)) }.getOrNull()?.let { dao.insert(it.copy(synced = true)) }
            }
        }
        prefs.edit()
            .putString(POD, client.pod).putString(ID, id.trim()).putString(SECRET, secret.trim())
            .putString(KEY, seal.key.toByteString().base64())
            .remove(PROBLEM).apply()
        return seal.recoveryCode()
    }

    fun forget() = prefs.edit().clear().apply()

    /** What the sign-up service handed over for a pickup code: the pod and a credential for it. */
    data class Joined(val pod: String, val id: String, val secret: String, val username: String, val new: Boolean)

    /** Trades the one-time code from the browser for the pod's credential. */
    fun pickup(code: String): Joined {
        val request = okhttp3.Request.Builder().url(SIGNUP + "pickup")
            .post(buildJsonObject { put("code", code) }.toString().toRequestBody("application/json".toMediaType()))
            .build()
        okhttp3.OkHttpClient().newCall(request).execute().use { r ->
            if (r.code == 404) throw PodClient.PodError("That sign-up had expired. Try Create with Kronk again.")
            if (!r.isSuccessful) throw PodClient.PodError("The pod sign-up said ${r.code}")
            val o = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: throw PodClient.PodError("The pod sign-up left out $k")
            return Joined(s("pod"), s("id"), s("secret"), s("username"), o["new"]?.jsonPrimitive?.content == "true")
        }
    }

    /** The pod's own login didn't link it; [again] is false once the sign-in has ended. */
    class ClaimRefused(message: String, val again: Boolean) : Exception(message)

    /** Links a pod already there with its own login, for the Kronk sign-in [token] stands for. */
    fun claim(token: String, email: String, password: String): Joined {
        val body = buildJsonObject { put("token", token); put("email", email); put("password", password) }
        val request = okhttp3.Request.Builder().url(SIGNUP + "claim")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        okhttp3.OkHttpClient().newCall(request).execute().use { r ->
            val o = runCatching { Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject }.getOrNull()
            if (!r.isSuccessful) {
                val said = o?.get("error")?.jsonPrimitive?.content ?: "The pod sign-up said ${r.code}"
                throw ClaimRefused(said, again = r.code == 401)
            }
            if (o == null) throw PodClient.PodError("The pod sign-up's answer couldn't be read")
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: throw PodClient.PodError("The pod sign-up left out $k")
            return Joined(s("pod"), s("id"), s("secret"), s("username"), false)
        }
    }

    /** Sends what changed and removes what was deleted. The number of items there after. */
    suspend fun sync(dao: DataPointDao): Int {
        val pod = prefs.getString(POD, null) ?: return 0
        val seal = seal() ?: return 0
        val client = PodClient(pod, prefs.getString(ID, "")!!, prefs.getString(SECRET, "")!!)
        try {
            val there = client.list(DIR).toMutableSet()
            val all = dao.getAllDataPoints()
            // An empty phone never empties the pod: that copy is what would bring it back.
            if (all.isEmpty() && there.isNotEmpty()) throw PodClient.PodError("Nothing on this phone, so the pod's copy is kept")
            val named = all.associateBy { seal.name(it.id) }
            for ((name, item) in named) {
                if (item.synced && name in there) continue
                client.put(DIR + name, seal.seal(encode(item).toByteArray()))
                dao.markSynced(item.id, item.valueJson, item.metadataJson)
                there += name
            }
            for (name in there - named.keys) {
                client.delete(DIR + name)
                there -= name
            }
            prefs.edit().putInt(ITEMS, there.size).putLong(LAST, System.currentTimeMillis()).remove(PROBLEM).apply()
            return there.size
        } catch (e: Exception) {
            prefs.edit().putString(PROBLEM, e.message ?: e.javaClass.simpleName).apply()
            throw e
        }
    }

    private fun seal() = prefs.getString(KEY, null)?.decodeBase64()?.toByteArray()?.let(::Seal)

    /** The pod and its key, for other copies kept there (backup/PhoneBackup.kt); null when not connected. */
    fun open(): Pair<PodClient, Seal>? {
        val pod = prefs.getString(POD, null) ?: return null
        val seal = seal() ?: return null
        return PodClient(pod, prefs.getString(ID, "")!!, prefs.getString(SECRET, "")!!) to seal
    }

    companion object {
        const val DIR = "anthemos/v1/"
        /** Makes a pod by signing in with Kronk (the sign-up service on the anthemos server). */
        const val SIGNUP = "https://pod.anthemos.org/signup/"
        private const val POD = "pod"
        private const val ID = "id"
        private const val SECRET = "secret"
        private const val KEY = "key"
        private const val ITEMS = "items"
        private const val LAST = "last"
        private const val PROBLEM = "problem"

        fun encode(e: DataPointEntity): String = buildJsonObject {
            put("id", e.id)
            put("pluginId", e.pluginId)
            put("timestamp", e.timestamp.toEpochMilli())
            put("type", e.type)
            put("valueJson", e.valueJson)
            e.metadataJson?.let { put("metadataJson", it) }
            e.source?.let { put("source", it) }
            put("version", e.version)
            put("createdAt", e.createdAt.toEpochMilli())
            e.extra1?.let { put("extra1", it) }
            e.extra2?.let { put("extra2", it) }
            e.extra3?.let { put("extra3", it) }
            e.extra4?.let { put("extra4", it) }
        }.toString()

        fun decode(text: String): DataPointEntity {
            val o: JsonObject = Json.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            return DataPointEntity(
                id = s("id")!!,
                pluginId = s("pluginId")!!,
                timestamp = Instant.ofEpochMilli(o["timestamp"]!!.jsonPrimitive.longOrNull!!),
                type = s("type")!!,
                valueJson = s("valueJson")!!,
                metadataJson = s("metadataJson"),
                source = s("source"),
                version = o["version"]?.jsonPrimitive?.intOrNull ?: 1,
                createdAt = o["createdAt"]?.jsonPrimitive?.longOrNull?.let(Instant::ofEpochMilli) ?: Instant.now(),
                extra1 = s("extra1"),
                extra2 = s("extra2"),
                extra3 = o["extra3"]?.jsonPrimitive?.longOrNull,
                extra4 = o["extra4"]?.jsonPrimitive?.doubleOrNull
            )
        }

        private fun decode(bytes: ByteArray) = decode(String(bytes))
    }
}
