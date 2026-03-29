package com.v2ray.ang

import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.SettingsManager

/**
 * Helper object for initializing v2ray library components.
 * This should be called from the host application's Application class.
 */
object AngLibraryInitializer {

    private var isInitialized = false

    /**
     * Initializes the v2ray library.
     * Should be called from the host application's Application.onCreate()
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true

        MMKV.initialize(context)
        SettingsManager.setNightMode()

        try {
            val workManagerConfiguration: Configuration = Configuration.Builder()
                .setDefaultProcessName("${ANG_PACKAGE}:bg")
                .build()
            WorkManager.initialize(context, workManagerConfiguration)
        } catch (e: Exception) {
            // WorkManager is already initialized.
            android.util.Log.w("AngLibraryInitializer", "WorkManager initialization skipped: ${e.message}")
        }

        SettingsManager.initRoutingRulesets(context)
    }

    /**
     * Initializes only MMKV without other components.
     * This is useful for multi-process setups where MMKV needs to be initialized
     * before the v2ray service starts in a separate process.
     */
    fun initializeMmkvOnly(context: Context) {
        try {
            MMKV.initialize(context)
        } catch (e: Exception) {
            // MMKV might already be initialized
            android.util.Log.w("AngLibraryInitializer", "MMKV initialization skipped: ${e.message}")
        }
    }
}

/**
 * Application class for v2ray module when used as a library.
 * Note: This is kept for compatibility but should not be used directly.
 * Call AngLibraryInitializer.initialize() from your own Application class instead.
 */
class AngApplication : android.app.Application(), Configuration.Provider {
    companion object {
        lateinit var application: AngApplication
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setDefaultProcessName("${AppConfig.ANG_PACKAGE}:bg")
            .build()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        AngLibraryInitializer.initialize(this)
    }
}
