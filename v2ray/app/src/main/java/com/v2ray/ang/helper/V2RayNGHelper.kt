package com.v2ray.ang.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.receiver.V2RayNGReceiver
import org.json.JSONObject

/**
 * Helper class for NekoX to interact with the v2rayNG module.
 * Provides methods for:
 * - Starting/stopping v2rayNG as a standalone app
 * - Importing profiles from NekoX
 * - Testing profiles and getting results
 * - Handling port conflict notifications
 */
class V2RayNGHelper(private val context: Context) {

    companion object {
        private const val TAG = AppConfig.TAG

        @Volatile
        private var instance: V2RayNGHelper? = null

        @JvmStatic
        fun getInstance(context: Context): V2RayNGHelper {
            return instance ?: synchronized(this) {
                V2RayNGHelper(context.applicationContext).also { instance = it }
            }
        }
    }

    private var resultReceiver: BroadcastReceiver? = null
    private var isReceiverRegistered = false

    // Callbacks for async operations
    var onStartResult: ((Boolean, String?) -> Unit)? = null
    var onStopResult: ((Boolean, String?) -> Unit)? = null
    var onImportResult: ((Boolean, String?, String?) -> Unit)? = null
    var onTestResult: ((String, Long, String?) -> Unit)? = null
    var onPortConflict: ((List<Int>) -> Unit)? = null

    /**
     * Initializes the helper and registers broadcast receiver for results.
     * Call this from NekoX when it needs to interact with v2rayNG.
     */
    @JvmOverloads
    fun initialize(registerForResults: Boolean = false) {
        if (registerForResults) {
            registerResultReceiver()
        }
    }

    /**
     * Releases resources and unregisters broadcast receiver.
     */
    fun release() {
        unregisterResultReceiver()
    }

    /**
     * Starts v2rayNG as a standalone application.
     * Will check for port conflicts and notify via callback.
     * @return True if start command was sent successfully.
     */
    @JvmOverloads
    fun startV2Ray(showUINotification: Boolean = true): Boolean {
        return try {
            val intent = Intent(V2RayNGReceiver.ACTION_NEKOX_START_V2RAY).apply {
                setPackage(context.packageName)
                putExtra("show_ui_notification", showUINotification)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent start command to v2rayNG")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send start command", e)
            false
        }
    }

    /**
     * Stops the v2rayNG service.
     * @return True if stop command was sent successfully.
     */
    fun stopV2Ray(): Boolean {
        return try {
            val intent = Intent(V2RayNGReceiver.ACTION_NEKOX_STOP_V2RAY).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent stop command to v2rayNG")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send stop command", e)
            false
        }
    }

    /**
     * Imports a v2ray profile from NekoX to v2rayNG.
     * @param profileUrl The profile URL (vless://, vmess://, trojan://, etc.).
     * @param remarks Optional remarks for the profile.
     * @return True if import command was sent successfully.
     */
    @JvmOverloads
    fun importProfile(profileUrl: String, remarks: String? = null): Boolean {
        return try {
            val intent = Intent(V2RayNGReceiver.ACTION_NEKOX_IMPORT_PROFILE).apply {
                setPackage(context.packageName)
                putExtra(V2RayNGReceiver.EXTRA_PROFILE_URL, profileUrl)
                putExtra(V2RayNGReceiver.EXTRA_PROFILE_REMARKS, remarks)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent import command to v2rayNG for profile: ${profileUrl.take(20)}...")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send import command", e)
            false
        }
    }

    /**
     * Tests a specific profile.
     * @param guid The profile GUID to test.
     * @return True if test command was sent successfully.
     */
    fun testProfile(guid: String): Boolean {
        return try {
            val intent = Intent(V2RayNGReceiver.ACTION_NEKOX_TEST_PROFILE).apply {
                setPackage(context.packageName)
                putExtra(V2RayNGReceiver.EXTRA_TEST_GUID, guid)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent test command for profile: $guid")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send test command", e)
            false
        }
    }

    /**
     * Tests all stored profiles.
     * @return True if test command was sent successfully.
     */
    fun testAllProfiles(): Boolean {
        return try {
            val intent = Intent(V2RayNGReceiver.ACTION_NEKOX_TEST_ALL_PROFILES).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Sent test all profiles command")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send test all command", e)
            false
        }
    }

    /**
     * Registers the broadcast receiver for receiving results from v2rayNG.
     */
    private fun registerResultReceiver() {
        if (isReceiverRegistered) {
            Log.w(TAG, "Result receiver already registered")
            return
        }

        try {
            resultReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val action = intent?.action ?: return
                    Log.d(TAG, "Received result: $action")

                    when (action) {
                        V2RayNGReceiver.ACTION_RESPONSE_STARTED -> {
                            val error = intent.getStringExtra(V2RayNGReceiver.EXTRA_ERROR_MESSAGE)
                            onStartResult?.invoke(error == null, error)
                        }

                        V2RayNGReceiver.ACTION_RESPONSE_STOPPED -> {
                            val error = intent.getStringExtra(V2RayNGReceiver.EXTRA_ERROR_MESSAGE)
                            onStopResult?.invoke(error == null, error)
                        }

                        V2RayNGReceiver.ACTION_RESPONSE_IMPORT_SUCCESS -> {
                            val guid = intent.getStringExtra(V2RayNGReceiver.EXTRA_TEST_GUID)
                            onImportResult?.invoke(true, guid, null)
                        }

                        V2RayNGReceiver.ACTION_RESPONSE_IMPORT_FAILURE -> {
                            val error = intent.getStringExtra(V2RayNGReceiver.EXTRA_ERROR_MESSAGE)
                            onImportResult?.invoke(false, null, error)
                        }

                        V2RayNGReceiver.ACTION_RESPONSE_TEST_RESULT -> {
                            val guid = intent.getStringExtra(V2RayNGReceiver.EXTRA_TEST_GUID) ?: ""
                            val delay = intent.getLongExtra(V2RayNGReceiver.EXTRA_TEST_DELAY, -1)
                            val result = intent.getStringExtra(V2RayNGReceiver.EXTRA_TEST_RESULT)
                            onTestResult?.invoke(guid, delay, result)
                        }

                        V2RayNGReceiver.ACTION_RESPONSE_PORT_CONFLICT -> {
                            val portsJson = intent.getStringExtra(V2RayNGReceiver.EXTRA_OCCUPIED_PORTS)
                            val ports = parsePortsJson(portsJson)
                            onPortConflict?.invoke(ports)
                        }
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(V2RayNGReceiver.ACTION_RESPONSE_STARTED)
                addAction(V2RayNGReceiver.ACTION_RESPONSE_STOPPED)
                addAction(V2RayNGReceiver.ACTION_RESPONSE_IMPORT_SUCCESS)
                addAction(V2RayNGReceiver.ACTION_RESPONSE_IMPORT_FAILURE)
                addAction(V2RayNGReceiver.ACTION_RESPONSE_TEST_RESULT)
                addAction(V2RayNGReceiver.ACTION_RESPONSE_PORT_CONFLICT)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.registerReceiver(
                    context,
                    resultReceiver!!,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.registerReceiver(resultReceiver, filter)
            }

            isReceiverRegistered = true
            Log.d(TAG, "Result receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register result receiver", e)
        }
    }

    /**
     * Unregisters the broadcast receiver.
     */
    private fun unregisterResultReceiver() {
        if (!isReceiverRegistered || resultReceiver == null) {
            return
        }

        try {
            context.unregisterReceiver(resultReceiver!!)
            isReceiverRegistered = false
            Log.d(TAG, "Result receiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister result receiver", e)
        }
    }

    /**
     * Parses the ports JSON array string.
     */
    private fun parsePortsJson(jsonString: String?): List<Int> {
        if (jsonString.isNullOrBlank()) {
            return emptyList()
        }

        return try {
            val array = org.json.JSONArray(jsonString)
            val ports = mutableListOf<Int>()
            for (i in 0 until array.length()) {
                ports.add(array.getInt(i))
            }
            ports
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ports JSON", e)
            emptyList()
        }
    }
}
