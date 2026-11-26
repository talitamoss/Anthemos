// app/src/main/java/com/domain/app/pod/PodClient.kt
package com.domain.app.pod

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A Solid pod, reached with a client credential made on the pod's own
 * account page (docs/anthemos-2.md, "Pod"): an id and a secret, swapped
 * for a short-lived token bound to this session's [Dpop] key. Paths are
 * relative to the pod's address. Plain JVM, so it is tested against a
 * real pod server.
 */
class PodClient(
    podUrl: String,
    private val id: String,
    private val secret: String,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
) {
    class PodError(message: String) : Exception(message)

    val pod: String = podUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
    private val dpop = Dpop()
    private var token: String? = null
    private var expires = 0L

    /** Gets a token: proves the id and secret are right. */
    fun check() {
        token = null
        token()
    }

    /**
     * Headers proving this pod's member to another service (the members AI):
     * the token and a proof for that one request, made with a key that stays here.
     */
    fun proveTo(method: String, url: String): Map<String, String> {
        val t = token()
        return mapOf("Authorization" to "DPoP $t", "DPoP" to dpop.proof(method, url, t))
    }

    fun put(path: String, bytes: ByteArray) {
        val body = bytes.toRequestBody(BYTES)
        send("PUT", pod + path) { it.put(body) }.close()
    }

    /** The bytes there, or null when nothing is. */
    fun get(path: String): ByteArray? = send("GET", pod + path, missingOk = true) { it.get() }.use { r ->
        if (r.code == 404) null else r.body?.bytes()
    }

    fun delete(path: String) {
        send("DELETE", pod + path, missingOk = true) { it.delete() }.close()
    }

    /** The names in a container (a path ending in "/"); empty when it isn't there yet. */
    fun list(container: String): List<String> {
        val url = pod + container
        val text = send("GET", url, missingOk = true) { it.get().header("Accept", "application/ld+json") }.use { r ->
            if (r.code == 404) return emptyList()
            r.body?.string().orEmpty()
        }
        val graph = Json.parseToJsonElement(text).let { if (it is JsonArray) it else (it.jsonObject["@graph"] as? JsonArray) ?: JsonArray(listOf(it)) }
        return graph.flatMap { node ->
            val o = node as? JsonObject ?: return@flatMap emptyList()
            if (o["@id"]?.jsonPrimitive?.content?.let { resolve(url, it) } != url) return@flatMap emptyList()
            (o[CONTAINS] as? JsonArray).orEmpty().mapNotNull { it.jsonObject["@id"]?.jsonPrimitive?.content }
        }.map { resolve(url, it).removePrefix(url) }.filter { it.isNotEmpty() }
    }

    private fun resolve(base: String, ref: String) = base.toHttpUrl().resolve(ref).toString()

    private fun send(method: String, url: String, missingOk: Boolean = false, build: (Request.Builder) -> Request.Builder): okhttp3.Response {
        repeat(2) { attempt ->
            val t = token()
            val request = build(Request.Builder().url(url))
                .header("Authorization", "DPoP $t")
                .header("DPoP", dpop.proof(method, url, t))
                .build()
            val response = http.newCall(request).execute()
            if (response.code == 401 && attempt == 0) {
                response.close()
                token = null
                return@repeat
            }
            if (response.isSuccessful || (missingOk && response.code == 404)) return response
            response.close()
            throw PodError("The pod said ${response.code} to $method ${url.removePrefix(pod)}")
        }
        throw PodError("The pod didn't accept the credential")
    }

    @Synchronized
    private fun token(): String {
        token?.takeIf { System.currentTimeMillis() < expires }?.let { return it }
        val origin = pod.toHttpUrl().newBuilder().encodedPath("/").build().toString()
        val config = http.newCall(Request.Builder().url(origin + ".well-known/openid-configuration").build()).execute().use { r ->
            if (!r.isSuccessful) throw PodError("That isn't a Solid pod server (${r.code})")
            Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        }
        val endpoint = config["token_endpoint"]?.jsonPrimitive?.content ?: throw PodError("The pod server gives no token address")
        val request = Request.Builder().url(endpoint)
            .header("Authorization", Credentials.basic(enc(id.trim()), enc(secret.trim())))
            .header("DPoP", dpop.proof("POST", endpoint))
            .post(FormBody.Builder().add("grant_type", "client_credentials").add("scope", "webid").build())
            .build()
        val body = http.newCall(request).execute().use { r ->
            if (r.code == 400 || r.code == 401) throw PodError("The pod didn't accept that token id and secret")
            if (!r.isSuccessful) throw PodError("The pod's sign-in said ${r.code}")
            Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        }
        val t = body["access_token"]?.jsonPrimitive?.content ?: throw PodError("The pod gave no token")
        val life = body["expires_in"]?.jsonPrimitive?.longOrNull ?: 300
        token = t
        expires = System.currentTimeMillis() + (life - 30).coerceAtLeast(30) * 1000
        return t
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private val BYTES = "application/octet-stream".toMediaType()
        private const val CONTAINS = "http://www.w3.org/ns/ldp#contains"
    }
}
