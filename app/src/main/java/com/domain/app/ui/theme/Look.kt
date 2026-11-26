// app/src/main/java/com/domain/app/ui/theme/Look.kt
package com.domain.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * This copy's own name, colours and update channel (docs/anthemos-2.md,
 * "Your own Anthemos"), from `assets/look.json`: anthemos.org/make swaps
 * that file, the icons and the launcher name for each person's copy.
 * Plain Anthemos has no channel and follows the main builds.
 */
data class Look(val name: String, val background: Color, val text: Color, val accent: Color, val channel: String?) {

    companion object {
        val Plain = Look("Anthemos", Ink, Paper, Glaucous, null)

        @Volatile var current = Plain
            private set

        fun load(context: Context) {
            current = runCatching { parse(context.assets.open("look.json").bufferedReader().readText()) }.getOrDefault(Plain)
        }

        fun parse(json: String): Look {
            val o = Json.parseToJsonElement(json).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            fun colour(k: String, default: Color) = s(k)?.let(::hex) ?: default
            return Look(
                name = s("name") ?: Plain.name,
                background = colour("background", Plain.background),
                text = colour("text", Plain.text),
                accent = colour("accent", Plain.accent),
                channel = s("channel")?.takeIf { it.matches(Regex("[a-z0-9-]{1,64}")) }
            )
        }

        /** "#RRGGBB" as a colour, or null. */
        fun hex(text: String): Color? = Regex("#?([0-9a-fA-F]{6})").matchEntire(text.trim())?.let { Color(0xFF000000 or it.groupValues[1].toLong(16)) }
    }
}
