// app/src/main/java/com/domain/app/focus/DeviceFocus.kt
package com.domain.app.focus

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import com.domain.app.device.Device
import com.domain.app.focus.HomeFocus.say
import com.domain.app.voice.Focus
import com.domain.app.voice.VoiceRequest
import com.domain.app.voice.command

/** Device: the phone itself, the torch, apps and the battery (docs/anthemos-2.md, "Phone"). */
object DeviceFocus {

    fun focus() = Focus(
        "device", "The phone itself: torch, apps, battery", listOf(
            command(
                "device.torch", "Turns the torch on or off",
                listOf("turn on the torch", "flashlight off"),
                { s -> (Device.parse(s.command) as? Device.Torch)?.let { VoiceRequest.Device(s.transcript, it) } }
            ) { torch((it.device as Device.Torch).on) },
            command(
                "device.battery", "How much battery is left",
                listOf("how much battery do I have", "what's my battery level"),
                { s -> (Device.parse(s.command) as? Device.Battery)?.let { VoiceRequest.Device(s.transcript, it) } }
            ) { battery() },
            command(
                "device.open", "Opens an app by its name; the house's things go to Home Assistant",
                listOf("open Signal", "launch the camera"),
                { s -> (Device.parse(s.command) as? Device.Open)?.let { VoiceRequest.Device(s.transcript, it) } }
            ) { open((it.device as Device.Open).app, it.transcript) }
        )
    )

    private fun Turn.torch(on: Boolean) {
        val cameras = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cameras.cameraIdList.firstOrNull { cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return reply("This phone has no torch")
        runCatching { cameras.setTorchMode(id, on) }
            .onSuccess { reply(if (on) "Torch on" else "Torch off") }
            .onFailure { reply("The camera is busy, so the torch can't change") }
    }

    private fun Turn.battery() {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        reply("$level%" + if (battery.isCharging) ", charging" else "")
    }

    /** The app whose name was said; if there's none, the house may have it ("open the garage"). */
    private suspend fun Turn.open(name: String, transcript: String) {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != context.packageName }
        val wanted = name.lowercase()
        val app = apps.firstOrNull { it.first.lowercase() == wanted }
            ?: apps.firstOrNull { it.first.lowercase().startsWith(wanted) }
            ?: apps.firstOrNull { wanted.length >= 4 && it.first.lowercase().contains(wanted) }
        if (app == null) {
            if (home.linked) return say(transcript)
            return reply("I can't find an app called $name")
        }
        val launch = pm.getLaunchIntentForPackage(app.second) ?: return reply("${app.first} can't be opened from here")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        reply("Opening ${app.first}")
    }
}
