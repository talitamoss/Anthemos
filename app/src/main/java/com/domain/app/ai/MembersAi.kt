// app/src/main/java/com/domain/app/ai/MembersAi.kt
package com.domain.app.ai

import android.content.Context
import com.domain.app.pod.PodClient
import com.domain.app.pod.PodSync
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The members' AI at ai.anthemos.org (docs/anthemos-2.md, "Members AI"):
 * one model shared by everyone with a pod there, which keeps nothing.
 * Membership is proven with the pod's own sign-in, which the AI can check
 * but not use. Only the question and today's date are sent.
 */
object MembersAi {
    private const val ASK = "https://ai.anthemos.org/ask"
    private const val PODS = "https://pod.anthemos.org/"

    private val http = OkHttpClient.Builder().readTimeout(5, TimeUnit.MINUTES).callTimeout(6, TimeUnit.MINUTES).build()

    fun canAsk(context: Context) = PodSync(context).status().let { it.connected && it.pod?.startsWith(PODS) == true }

    /** The answer to [question]; [so] is shown the answer so far as it arrives. */
    fun ask(context: Context, question: String, so: (String) -> Unit): String {
        val (client, _) = PodSync(context).open() ?: throw PodClient.PodError("Link a pod first")
        val body = buildJsonObject {
            put("about", "Today is " + ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm")) + ".")
            put("messages", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", question) }) })
        }
        val request = Request.Builder().url(ASK)
            .apply { client.proveTo("POST", ASK).forEach { (k, v) -> header(k, v) } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val source = response.body?.source() ?: error("The members' AI didn't answer")
            if (!response.isSuccessful) {
                val said = runCatching { Json.parseToJsonElement(source.readUtf8()).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                throw PodClient.PodError(said ?: "The members' AI said ${response.code}")
            }
            val answer = StringBuilder()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                val part = Json.parseToJsonElement(line).jsonObject
                part["error"]?.jsonPrimitive?.content?.let { throw PodClient.PodError(it) }
                part["t"]?.jsonPrimitive?.content?.let { answer.append(it); so(answer.toString()) }
                if (part["done"] != null) break
            }
            return answer.toString().trim().ifEmpty { "The members' AI had nothing to say." }
        }
    }
}
