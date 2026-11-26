// app/src/main/java/com/domain/app/home/HomeAssistant.kt
package com.domain.app.home

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The house's Home Assistant, reached at home or over the VPN
 * (docs/anthemos-2.md, "Home"). What's said is handed to its own Assist,
 * which knows the devices and rooms, and its answer is said back. The
 * address and long-lived token are kept encrypted.
 */
class HomeAssistant(private val context: Context) {

    class HomeError(message: String) : Exception(message)

    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()

    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "anthemos_home", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    val linked: Boolean get() = prefs.getString(TOKEN, null) != null
    val address: String? get() = prefs.getString(URL, null)

    /** Checks the address and token answer, then keeps them. */
    fun link(address: String, token: String) {
        val url = normalise(address)
        val text = call(url, token, Request.Builder().url("$url/api/").get())
        if (!text.contains("API running")) throw HomeError("That address didn't answer like Home Assistant")
        prefs.edit().putString(URL, url).putString(TOKEN, token.trim()).apply()
    }

    fun forget() = prefs.edit().clear().apply()

    /**
     * Hands [text] to Assist and returns what it says back. A follow-up
     * within a few minutes carries on the same conversation ("and the
     * kitchen").
     */
    fun ask(text: String): String {
        val url = prefs.getString(URL, null) ?: throw HomeError("Connect Home Assistant in Settings first")
        val token = prefs.getString(TOKEN, null) ?: throw HomeError("Connect Home Assistant in Settings first")
        val body = JSONObject().put("text", text).put("language", "en")
        val recent = prefs.getString(CONVERSATION, null)?.takeIf { System.currentTimeMillis() - prefs.getLong(CONVERSATION_AT, 0) < FOLLOW_UP_MS }
        recent?.let { body.put("conversation_id", it) }
        val reply = JSONObject(
            call(url, token, Request.Builder().url("$url/api/conversation/process").post(body.toString().toRequestBody(JSON)))
        )
        reply.optString("conversation_id").takeIf { it.isNotEmpty() }?.let {
            prefs.edit().putString(CONVERSATION, it).putLong(CONVERSATION_AT, System.currentTimeMillis()).apply()
        }
        val response = reply.optJSONObject("response")
        val speech = response?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
        return speech.ifBlank { if (response?.optString("response_type") == "error") "Home Assistant couldn't do that" else "Done" }
    }

    /** True when Assist didn't understand, so the words can be answered another way. */
    fun understood(reply: String) = !NOT_UNDERSTOOD.containsMatchIn(reply)

    private fun call(url: String, token: String, builder: Request.Builder): String {
        val request = builder.header("Authorization", "Bearer ${token.trim()}").build()
        try {
            http.newCall(request).execute().use { response ->
                if (response.code == 401) throw HomeError("Home Assistant didn't accept the token. Make a new one in your profile, under Security.")
                if (!response.isSuccessful) throw HomeError("Home Assistant said ${response.code}")
                return response.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            throw HomeError("I can't reach Home Assistant. Are you on home Wi-Fi or the VPN?")
        }
    }

    companion object {
        private const val URL = "url"
        private const val TOKEN = "token"
        private const val CONVERSATION = "conversation"
        private const val CONVERSATION_AT = "conversation_at"
        private const val FOLLOW_UP_MS = 5 * 60 * 1000L
        private val JSON = "application/json".toMediaType()
        private val NOT_UNDERSTOOD = Regex("(?i)sorry, i (?:couldn't|could not|didn't|did not|am not) (?:understand|aware)|no (?:area|device|entity) (?:called|named)")

        /** "192.168.1.10" -> "http://192.168.1.10:8123"; trailing slashes dropped. */
        fun normalise(address: String): String {
            var a = address.trim().trimEnd('/')
            if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
            if (Regex("^https?://[^/:]+$").matches(a)) a += ":8123"
            return a
        }

        /**
         * Sounds like something for the house: switching, setting, opening,
         * locking, or asking whether something is on, open or locked, or
         * how warm it is inside.
         */
        fun isHomeCommand(command: String): Boolean = HOME.any { it.containsMatchIn(command) }

        private val HOME = listOf(
            Regex("^(?:turn|switch|flick) (?:on|off) "),
            Regex("^(?:turn|switch) (?:the |all the |all )?.+ (?:on|off)$"),
            Regex("^(?:dim|brighten) "),
            Regex("^set (?:the )?.+ to \\d"),
            Regex("^(?:open|close|shut|lock|unlock|raise|lower) (?:the |all the |all )"),
            Regex("^(?:start|stop|pause|dock) (?:the )?(?:vacuum|robot|hoover|dishwasher|washing machine|dryer)"),
            Regex("^(?:activate|run|start) (?:the )?.+ (?:scene|script)$"),
            Regex("^(?:is|are) (?:the |any )?.+ (?:on|off|open|closed|locked|unlocked|home|running)$"),
            Regex("^(?:what(?:'s| is) the )?(?:temperature|humidity) (?:inside|in the|in here|at home)"),
            Regex("^how (?:warm|cold|hot|humid) is (?:it )?(?:inside|in the|in here|at home)"),
            Regex("^(?:which|what) (?:lights|doors|windows) are (?:on|open)"),
            Regex("^(?:lights|light) (?:on|off)$")
        )
    }
}
