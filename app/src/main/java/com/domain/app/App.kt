// app/src/main/java/com/domain/app/App.kt
package com.domain.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.domain.app.core.storage.encryption.EncryptionManager
import com.domain.app.pod.PodWorker
import com.domain.app.prompts.PromptWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for app-wide initialization
 */
@HiltAndroidApp
class App : Application(), Configuration.Provider {
    
    @Inject
    lateinit var workerFactory: HiltWorkerFactory
    
    // ADD THIS PROPERTY - This is what DatabaseModule needs!
    lateinit var encryptionManager: EncryptionManager
        private set
    
    override fun onCreate() {
        super.onCreate()
        com.domain.app.ui.theme.Look.load(this)
        
        // Initialize encryption manager BEFORE other components
        encryptionManager = EncryptionManager(this)
        
        // Initialize any app-wide components here
        initializeApp()

        // Anthemos 2.0: look for a due prompt every fifteen minutes.
        PromptWorker.schedule(this)
        // Copies the store to the pod; does nothing until one is connected (pod/PodSync.kt).
        PodWorker.schedule(this)
        // Contacts, texts and calls to the pod once a day, once turned on (backup/PhoneBackup.kt).
        if (com.domain.app.backup.PhoneBackup(this).on) com.domain.app.backup.BackupWorker.schedule(this)
    }
    
    /**
     * Provide WorkManager configuration with Hilt integration
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
    
    private fun initializeApp() {
        // Initialize crash reporting, analytics, etc.
        // This is where you'd initialize any third-party SDKs
    }
    
    companion object {
        lateinit var instance: App
            private set
    }
    
    init {
        instance = this
    }
}
