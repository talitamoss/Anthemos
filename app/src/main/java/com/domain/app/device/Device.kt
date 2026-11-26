// app/src/main/java/com/domain/app/device/Device.kt
package com.domain.app.device

/** The phone itself, said (docs/anthemos-2.md, "Phone"): the torch, an app, the battery. */
sealed class Device {
    data class Torch(val on: Boolean) : Device()
    /** "Open Signal": an app by its name. */
    data class Open(val app: String) : Device()
    object Battery : Device()

    companion object {
        /** [command] is lowercase, without punctuation or "hey Polly". */
        fun parse(command: String): Device? {
            val c = command.trim()
            TORCH.matchEntire(c)?.let { m -> return Torch((m.groupValues[1] + m.groupValues[2] + m.groupValues[3]) == "on") }
            if (BATTERY.containsMatchIn(c)) return Battery
            OPEN.matchEntire(c)?.let { m -> return Open(m.groupValues[1].trim()) }
            return null
        }

        private const val LIGHT = "(?:torch|flashlight|flash light|flash)"
        private val TORCH = Regex("^(?:turn|switch) (on|off) (?:the |my )?$LIGHT$|^(?:turn|switch) (?:the |my )?$LIGHT (on|off)$|^$LIGHT (on|off)$")
        private val BATTERY = Regex("^(?:how much|what(?:'s| is)(?: my| the)?) battery|\\bbattery (?:level|left|percentage)\\b|^how much charge")
        private val OPEN = Regex("^(?:open|launch)(?: up)? (?:the |my )?(.+?)(?: app)?$")
    }
}
