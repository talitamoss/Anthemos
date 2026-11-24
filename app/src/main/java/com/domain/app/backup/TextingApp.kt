// app/src/main/java/com/domain/app/backup/TextingApp.kt
package com.domain.app.backup

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast

/**
 * Android lets only the phone's texting app write texts, so Anthemos asks
 * to be it while it puts texts back, then sends you to hand it back. To be
 * allowed to ask, an app must be able to receive texts and MMS, answer a
 * call with a text, and open a new text: these are those parts, kept to
 * the least and switched on only for then. A text that arrives meanwhile
 * is filed in the inbox, so the real texting app shows it once it's back;
 * an MMS in that moment is lost.
 */
object TextingApp {
    private const val BEFORE = "texting_app_before"

    /** The phone's own question: may Anthemos be the texting app? */
    fun ask(context: Context): Intent {
        parts(context, on = true)
        prefs(context).edit().putString(BEFORE, Telephony.Sms.getDefaultSmsPackage(context)).apply()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }

    /** Back to the app that was the texting app. Android 10 and later only allow this in its settings. */
    fun handBack(context: Context): Intent {
        val before = prefs(context).getString(BEFORE, null)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && before != null) {
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, before)
        } else {
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        }
    }

    /**
     * The texting parts are off except while restoring, so Anthemos isn't
     * offered for every text link. Switched off again once it isn't the texting app.
     */
    fun tidy(context: Context) {
        // Not while texts wait: the phone may not have made Anthemos the texting app yet.
        if (PhoneBackup(context).textsWaiting) return
        if (Telephony.Sms.getDefaultSmsPackage(context) != context.packageName) parts(context, on = false)
    }

    private fun parts(context: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (part in listOf(TextArrived::class.java, MmsArrived::class.java, ReplyByText::class.java, NewText::class.java)) {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, part), state, PackageManager.DONT_KILL_APP)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("anthemos_phone_backup", Context.MODE_PRIVATE)
}

/** A text arriving while Anthemos is the texting app: into the inbox, unread. */
class TextArrived : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
        val first = parts.firstOrNull() ?: return
        context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, ContentValues().apply {
            put(Telephony.Sms.ADDRESS, first.displayOriginatingAddress)
            put(Telephony.Sms.BODY, parts.joinToString("") { it.displayMessageBody.orEmpty() })
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.DATE_SENT, first.timestampMillis)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
        })
    }
}

/** Required to be the texting app; Anthemos doesn't handle MMS. */
class MmsArrived : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}

/** Required to be the texting app ("reply with a text" to a call); Anthemos doesn't send texts. */
class ReplyByText : Service() {
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/** Required to be the texting app (writing a new text): says so and opens where to hand it back. */
class NewText : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Toast.makeText(this, "Anthemos is only the texting app while it restores texts. Choose your texting app again.", Toast.LENGTH_LONG).show()
        runCatching { startActivity(TextingApp.handBack(this)) }
        finish()
    }
}
