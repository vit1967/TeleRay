package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.extension.toast
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.service.V2RayServiceManager
import com.v2ray.ang.util.MessageUtil
import com.v2ray.ang.util.PortChecker
import com.v2ray.ang.util.Utils
import go.Seq
import libv2ray.Libv2ray
import org.json.JSONObject

/**
 * Broadcast receiver for handling inter-process communication between NekoX and v2rayNG.
 * Supports:
 * - Profile import from NekoX
 * - Start/Stop commands
 * - Port conflict detection
 * - Test commands and results
 */
class V2RayNGReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = AppConfig.TAG

        // Command enums (must match NekoX side)
        const val CMD_TEST_KEYS = 1001
        const val CMD_IMPORT_KEYS = 1002
        const val CMD_START_V2RAY = 1003
        const val CMD_STOP_V2RAY = 1004
        const val CMD_STATUS_CHECK = 1005
        const val CMD_SELECT_BEST_KEY = 1006
        const val CMD_DEDUP_PROFILES = 1007
        const val CMD_TEST_REAL_PING  = 1008  // TCP-ping all profiles, store results → RESP_TEST_REAL_PING_DONE
        const val CMD_SORT_BY_TEST    = 1009  // sort MMKV server list by testDelayMillis (fire-and-forget)

        // Response codes
        const val RESP_TEST_RESULT = 2001
        const val RESP_IMPORT_RESULT = 2002
        const val RESP_NO_V2RAY = 2999
        const val RESP_NO_ANSWER = 2998
        const val RESP_STATUS = 2003
        const val RESP_SELECT_BEST_KEY = 2004
        const val RESP_TEST_REAL_PING_DONE = 2005  // all TCP pings done, results in MMKV

        const val EXTRA_TRUST_STORED = "trust_stored"  // SELECT_BEST_KEY: skip re-test, use stored delays

        // Action constants for NekoX ↔ v2rayNG communication
        const val ACTION_NEKOX_START_V2RAY = "com.v2ray.ang.action.NEKOX_START"
        const val ACTION_NEKOX_STOP_V2RAY = "com.v2ray.ang.action.NEKOX_STOP"
        const val ACTION_NEKOX_IMPORT_PROFILE = "com.v2ray.ang.action.NEKOX_IMPORT_PROFILE"
        const val ACTION_NEKOX_TEST_PROFILE = "com.v2ray.ang.action.NEKOX_TEST_PROFILE"
        const val ACTION_NEKOX_TEST_ALL_PROFILES = "com.v2ray.ang.action.NEKOX_TEST_ALL"
        const val ACTION_NEKOX_COMMAND = "com.v2ray.ang.action.NEKOX_COMMAND"

        // Response actions
        const val ACTION_RESPONSE_STARTED = "com.v2ray.ang.action.RESPONSE_STARTED"
        const val ACTION_RESPONSE_STOPPED = "com.v2ray.ang.action.RESPONSE_STOPPED"
        const val ACTION_RESPONSE_IMPORT_SUCCESS = "com.v2ray.ang.action.RESPONSE_IMPORT_SUCCESS"
        const val ACTION_RESPONSE_IMPORT_FAILURE = "com.v2ray.ang.action.RESPONSE_IMPORT_FAILURE"
        const val ACTION_RESPONSE_TEST_RESULT = "com.v2ray.ang.action.RESPONSE_TEST_RESULT"
        const val ACTION_RESPONSE_PORT_CONFLICT = "com.v2ray.ang.action.RESPONSE_PORT_CONFLICT"
        const val ACTION_V2RAY_RESPONSE = "com.v2ray.ang.action.NEKOX_RESPONSE"

        // Extra keys
        const val EXTRA_PROFILE_URL = "profile_url"
        const val EXTRA_PROFILE_REMARKS = "profile_remarks"
        const val EXTRA_TEST_GUID = "test_guid"
        const val EXTRA_TEST_REMARKS = "test_remarks"
        const val EXTRA_TEST_RESULT = "test_result"
        const val EXTRA_TEST_DELAY = "test_delay"
        const val EXTRA_ERROR_MESSAGE = "error_message"
        const val EXTRA_OCCUPIED_PORTS = "occupied_ports"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_KEYS = "keys"
        const val EXTRA_KEY1 = "key1"
        const val EXTRA_KEY2 = "key2"
        const val EXTRA_IMPORTED_COUNT = "imported_count"
        const val EXTRA_WORKING_COUNT = "working_count"
        const val EXTRA_TOTAL_COUNT = "total_count"
        const val EXTRA_IS_RUNNING   = "is_running"
        const val EXTRA_LAST_PING_MS = "last_ping_ms"
        const val EXTRA_SELECTED_KEY_GUID = "selected_key_guid"
    }

    /**
     * Handles received broadcast messages.
     * @param context The context in which the receiver is running.
     * @param intent The intent being received.
     */
    override fun onReceive(ctx: Context?, intent: Intent?) {
        val context = ctx ?: return
        val action = intent?.action ?: return

        // Initialize MMKV in the separate process (if not already initialized)
        try {
            com.tencent.mmkv.MMKV.initialize(context.applicationContext)
        } catch (e: Exception) {
            // MMKV might already be initialized
        }

        Log.d(TAG, "V2RayNGReceiver received action: $action in process ${getCurrentProcessName(context)}")

        when (action) {
            ACTION_NEKOX_START_V2RAY -> {
                Log.d(TAG, "Handling NEKOX_START command")
                handleStartV2Ray(context)
            }

            ACTION_NEKOX_STOP_V2RAY -> {
                Log.d(TAG, "Handling NEKOX_STOP command")
                handleStopV2Ray(context)
            }

            ACTION_NEKOX_IMPORT_PROFILE -> {
                val profileUrl = intent.getStringExtra(EXTRA_PROFILE_URL)
                val remarks = intent.getStringExtra(EXTRA_PROFILE_REMARKS)
                Log.d(TAG, "Handling NEKOX_IMPORT_PROFILE: ${profileUrl?.take(30)}...")
                handleImportProfile(context, profileUrl, remarks)
            }

            ACTION_NEKOX_TEST_PROFILE -> {
                val guid = intent.getStringExtra(EXTRA_TEST_GUID)
                Log.d(TAG, "Handling NEKOX_TEST_PROFILE: $guid")
                handleTestProfile(context, guid)
            }

            ACTION_NEKOX_TEST_ALL_PROFILES -> {
                Log.d(TAG, "Handling NEKOX_TEST_ALL command")
                handleTestAllProfiles(context)
            }

            ACTION_NEKOX_COMMAND -> {
                // Handle new command enum-based communication
                val command = intent.getIntExtra(EXTRA_COMMAND, 0)
                Log.d(TAG, "Handling NEKOX_COMMAND: $command")
                val resId = context.resources.getIdentifier("v2ray_cmd_$command", "string", context.packageName)
                if (resId != 0) {
                    context.toast(context.getString(resId))
                } else {
                    context.toast("V2Ray: Received command $command")
                }
                when (command) {
                    CMD_TEST_KEYS -> {
                        // Runs TCP tests for every key — must not block onReceive() main thread
                        Log.d(TAG, "Executing TEST_KEYS command (async)")
                        val pending = goAsync()
                        Thread {
                            try { handleTestKeysCommand(context) }
                            finally { pending.finish() }
                        }.start()
                    }
                    CMD_IMPORT_KEYS -> {
                        val key1 = intent.getStringExtra(EXTRA_KEY1)
                        val key2 = intent.getStringExtra(EXTRA_KEY2)
                        Log.d(TAG, "Executing IMPORT_KEYS command (async): key1=${key1?.take(30)}..., key2=${key2?.take(30)}...")
                        val pending = goAsync()
                        Thread {
                            try { handleImportKeysCommand(context, key1, key2) }
                            finally { pending.finish() }
                        }.start()
                    }
                    CMD_START_V2RAY -> {
                        Log.d(TAG, "Executing START_V2RAY command")
                        handleStartV2Ray(context)
                    }
                    CMD_STOP_V2RAY -> {
                        Log.d(TAG, "Executing STOP_V2RAY command")
                        handleStopV2Ray(context)
                    }
                    CMD_STATUS_CHECK -> {
                        Log.d(TAG, "Executing STATUS_CHECK command")
                        handleStatusCheckCommand(context)
                    }
                    CMD_SELECT_BEST_KEY -> {
                        val trustStored = intent.getBooleanExtra(EXTRA_TRUST_STORED, false)
                        Log.d(TAG, "Executing SELECT_BEST_KEY command (async, trustStored=$trustStored)")
                        val pending = goAsync()
                        Thread {
                            try { handleSelectBestKeyCommand(context, trustStored) }
                            finally { pending.finish() }
                        }.start()
                    }
                    CMD_DEDUP_PROFILES -> {
                        Log.d(TAG, "Executing DEDUP_PROFILES command")
                        handleDedupProfilesCommand(context)
                    }
                    CMD_TEST_REAL_PING -> {
                        Log.d(TAG, "Executing TEST_REAL_PING command (async parallel)")
                        val pending = goAsync()
                        Thread {
                            try { handleTestRealPingCommand(context) }
                            finally { pending.finish() }
                        }.start()
                    }
                    CMD_SORT_BY_TEST -> {
                        Log.d(TAG, "Executing SORT_BY_TEST command")
                        handleSortByTestCommand(context)
                    }
                }
            }
        }
    }

    /**
     * Gets the current process name for debugging.
     */
    private fun getCurrentProcessName(context: Context): String {
        return try {
            val pid = android.os.Process.myPid()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            activityManager?.runningAppProcesses?.find { it.pid == pid }?.processName ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Handles the start V2Ray command from NekoX.
     * Checks for port conflicts before starting.
     */
    private fun handleStartV2Ray(context: Context) {
        try {
            // Check for port conflicts
            val occupiedPorts = PortChecker.getOccupiedPorts()
            if (occupiedPorts.isNotEmpty()) {
                // Port conflict detected - send response and don't start
                sendPortConflictResponse(context, occupiedPorts)
                return
            }

            // Check VPN permission — BroadcastReceiver cannot show permission dialog,
            // so fall back to proxy-only mode if VPN permission is not yet granted.
            val currentMode = MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) ?: AppConfig.VPN
            if (currentMode == AppConfig.VPN) {
                val vpnPermissionIntent = android.net.VpnService.prepare(context)
                if (vpnPermissionIntent != null) {
                    Log.w(TAG, "VPN permission not granted, switching to proxy-only mode for IPC start")
                    context.toast("v2ray: VPN не разрешён, режим proxy-only")
                    MmkvManager.encodeSettings(AppConfig.PREF_MODE, "Proxy")
                }
            }

            // Start the service
            val started = V2RayServiceManager.startVServiceFromToggle(context)
            if (started) {
                sendResponse(context, ACTION_RESPONSE_STARTED, null)
            } else {
                sendResponse(
                    context,
                    ACTION_RESPONSE_STARTED,
                    mapOf(EXTRA_ERROR_MESSAGE to "Failed to start V2Ray service")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting V2Ray", e)
            sendResponse(
                context,
                ACTION_RESPONSE_STARTED,
                mapOf(EXTRA_ERROR_MESSAGE to e.message)
            )
        }
    }

    /**
     * Handles the stop V2Ray command from NekoX.
     */
    private fun handleStopV2Ray(context: Context) {
        try {
            V2RayServiceManager.stopVService(context)
            sendResponse(context, ACTION_RESPONSE_STOPPED, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping V2Ray", e)
            sendResponse(
                context,
                ACTION_RESPONSE_STOPPED,
                mapOf(EXTRA_ERROR_MESSAGE to e.message)
            )
        }
    }

    /**
     * Handles profile import from NekoX.
     * @param context The context.
     * @param profileUrl The v2ray profile URL (vless://, vmess://, etc.).
     * @param remarks Optional remarks for the profile.
     */
    private fun handleImportProfile(context: Context, profileUrl: String?, remarks: String?) {
        if (profileUrl.isNullOrBlank()) {
            sendResponse(
                context,
                ACTION_RESPONSE_IMPORT_FAILURE,
                mapOf(EXTRA_ERROR_MESSAGE to "Profile URL is empty")
            )
            return
        }

        try {
            // Import the profile using the existing ProfileImporter
            val guid = com.v2ray.ang.helper.ProfileImporter.importProfile(context, profileUrl)

            if (!guid.isNullOrBlank()) {
                // Optionally set remarks
                if (!remarks.isNullOrBlank()) {
                    val config = MmkvManager.decodeServerConfig(guid)
                    config?.let {
                        it.remarks = remarks
                        MmkvManager.encodeServerConfig(guid, it)
                    }
                }

                // Select this profile
                MmkvManager.setSelectServer(guid)

                sendResponse(
                    context,
                    ACTION_RESPONSE_IMPORT_SUCCESS,
                    mapOf(EXTRA_TEST_GUID to guid)
                )
            } else {
                sendResponse(
                    context,
                    ACTION_RESPONSE_IMPORT_FAILURE,
                    mapOf(EXTRA_ERROR_MESSAGE to "Failed to import profile")
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error importing profile", e)
            sendResponse(
                context,
                ACTION_RESPONSE_IMPORT_FAILURE,
                mapOf(EXTRA_ERROR_MESSAGE to e.message)
            )
        }
    }

    /**
     * Handles test profile command from NekoX.
     * @param context The context.
     * @param guid The profile GUID to test.
     */
    private fun handleTestProfile(context: Context, guid: String?) {
        if (guid.isNullOrBlank()) {
            sendResponse(
                context,
                ACTION_RESPONSE_TEST_RESULT,
                mapOf(EXTRA_ERROR_MESSAGE to "Profile GUID is empty")
            )
            return
        }

        try {
            // Use the existing SpeedtestManager to test the profile
            val config = MmkvManager.decodeServerConfig(guid)
            if (config == null) {
                sendResponse(
                    context,
                    ACTION_RESPONSE_TEST_RESULT,
                    mapOf(EXTRA_ERROR_MESSAGE to "Profile not found")
                )
                return
            }

            // Perform delay test using TCP socket connection time
            val server = config.server
            val portStr = config.serverPort
            if (server.isNullOrBlank() || portStr.isNullOrBlank()) {
                sendResponse(
                    context,
                    ACTION_RESPONSE_TEST_RESULT,
                    mapOf(EXTRA_ERROR_MESSAGE to "Invalid server or port")
                )
                return
            }

            val port = portStr.toIntOrNull() ?: run {
                sendResponse(
                    context,
                    ACTION_RESPONSE_TEST_RESULT,
                    mapOf(EXTRA_ERROR_MESSAGE to "Invalid port number: $portStr")
                )
                return
            }

            val delay = runDelayTest(server, port)

            sendResponse(
                context,
                ACTION_RESPONSE_TEST_RESULT,
                mapOf(
                    EXTRA_TEST_GUID to guid,
                    EXTRA_TEST_DELAY to delay,
                    EXTRA_TEST_RESULT to if (delay > 0) "OK" else "Failed"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error testing profile", e)
            sendResponse(
                context,
                ACTION_RESPONSE_TEST_RESULT,
                mapOf(
                    EXTRA_TEST_GUID to guid,
                    EXTRA_ERROR_MESSAGE to e.message
                )
            )
        }
    }

    /**
     * Handles test all profiles command from NekoX.
     * This will test all stored profiles and return results.
     */
    private fun handleTestAllProfiles(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            val results = mutableListOf<Map<String, Any>>()

            for (guid in serverList) {
                val config = MmkvManager.decodeServerConfig(guid)
                if (config != null) {
                    val server = config.server
                    val portStr = config.serverPort
                    if (server.isNullOrBlank() || portStr.isNullOrBlank()) {
                        results.add(
                            mapOf(
                                EXTRA_TEST_GUID to guid,
                                EXTRA_TEST_REMARKS to (config.remarks ?: "Unknown"),
                                EXTRA_ERROR_MESSAGE to "Invalid server or port"
                            )
                        )
                        continue
                    }

                    val port = portStr.toIntOrNull()
                    if (port == null) {
                        results.add(
                            mapOf(
                                EXTRA_TEST_GUID to guid,
                                EXTRA_TEST_REMARKS to (config.remarks ?: "Unknown"),
                                EXTRA_ERROR_MESSAGE to "Invalid port: $portStr"
                            )
                        )
                        continue
                    }

                    try {
                        val delay = runDelayTest(server, port)
                        results.add(
                            mapOf(
                                EXTRA_TEST_GUID to guid,
                                EXTRA_TEST_REMARKS to (config.remarks ?: "Unknown"),
                                EXTRA_TEST_DELAY to delay,
                                EXTRA_TEST_RESULT to if (delay > 0 && delay < 10000) "OK" else "Failed"
                            )
                        )
                    } catch (e: Exception) {
                        results.add(
                            mapOf(
                                EXTRA_TEST_GUID to guid,
                                EXTRA_TEST_REMARKS to (config.remarks ?: "Unknown"),
                                EXTRA_ERROR_MESSAGE to (e.message ?: "Unknown error")
                            )
                        )
                    }
                }
            }

            // Send results as JSON array
            val jsonResults = org.json.JSONArray()
            for (result in results) {
                val jsonResult = JSONObject()
                for ((key, value) in result) {
                    jsonResult.put(key, value)
                }
                jsonResults.put(jsonResult)
            }

            sendResponse(
                context,
                ACTION_RESPONSE_TEST_RESULT,
                mapOf("results" to jsonResults.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error testing all profiles", e)
            sendResponse(
                context,
                ACTION_RESPONSE_TEST_RESULT,
                mapOf(EXTRA_ERROR_MESSAGE to e.message)
            )
        }
    }

    /**
     * Sends a port conflict response.
     * @param context The context.
     * @param occupiedPorts List of occupied port numbers.
     */
    private fun sendPortConflictResponse(context: Context, occupiedPorts: List<Int>) {
        val portsArray = org.json.JSONArray()
        for (port in occupiedPorts) {
            portsArray.put(port)
        }

        sendResponse(
            context,
            ACTION_RESPONSE_PORT_CONFLICT,
            mapOf(
                EXTRA_ERROR_MESSAGE to "Ports are already in use by another application",
                EXTRA_OCCUPIED_PORTS to portsArray.toString()
            )
        )
    }

    /**
     * Sends a broadcast response.
     * @param context The context.
     * @param action The response action.
     * @param data Optional data to include in the response.
     */
    private fun sendResponse(context: Context, action: String, data: Map<String, Any?>?) {
        val intent = Intent(action).apply {
            data?.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
        context.sendBroadcast(intent)
    }

    /**
     * Runs a TCP delay test on the given server and port.
     * Uses SpeedtestManager.socketConnectTime for testing.
     * @param server The server hostname or IP.
     * @param port The server port.
     * @return The delay in milliseconds, or -1 if failed.
     */
    private fun runDelayTest(server: String, port: Int): Long {
        return try {
            // Use the existing socketConnectTime method from SpeedtestManager
            com.v2ray.ang.handler.SpeedtestManager.socketConnectTime(server, port)
        } catch (e: Exception) {
            Log.e(TAG, "Delay test failed", e)
            -1L
        }
    }

    /**
     * Handles the TEST_KEYS command from NekoX.
     * Tests all stored profiles and returns count of working keys.
     */
    private fun handleTestKeysCommand(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            var workingCount = 0
            val totalCount = serverList.size

            for (guid in serverList) {
                val config = MmkvManager.decodeServerConfig(guid)
                if (config != null) {
                    val server = config.server
                    val portStr = config.serverPort
                    if (!server.isNullOrBlank() && !portStr.isNullOrBlank()) {
                        val port = portStr.toIntOrNull()
                        if (port != null) {
                            val delay = runDelayTest(server, port)
                            if (delay > 0 && delay < 10000) {
                                workingCount++
                            }
                        }
                    }
                }
            }

            if (workingCount > 0) {
                selectBestAndStart(context)
            }

            // Send response
            sendCommandResponse(
                context,
                RESP_TEST_RESULT,
                mapOf(
                    EXTRA_WORKING_COUNT to workingCount,
                    EXTRA_TOTAL_COUNT to totalCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in test keys command", e)
            sendCommandResponse(
                context,
                RESP_TEST_RESULT,
                mapOf(
                    EXTRA_WORKING_COUNT to 0,
                    EXTRA_TOTAL_COUNT to 0,
                    EXTRA_ERROR_MESSAGE to e.message
                )
            )
        }
    }

    /**
     * Handles the IMPORT_KEYS command from NekoX.
     * Imports the provided keys and tests them.
     * key1 and key2 may contain multiple newline-separated URLs.
     */
    private fun handleImportKeysCommand(context: Context, key1: String?, key2: String?) {
        try {
            var importedCount = 0
            val keysToImport = mutableListOf<String>()

            // Split each key string by newlines to support multiple URLs per field
            key1?.split("\n")?.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) keysToImport.add(trimmed)
            }
            key2?.split("\n")?.forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) keysToImport.add(trimmed)
            }

            if (keysToImport.isEmpty()) {
                sendCommandResponse(
                    context,
                    RESP_IMPORT_RESULT,
                    mapOf(
                        EXTRA_IMPORTED_COUNT to 0,
                        EXTRA_WORKING_COUNT to 0,
                        EXTRA_TOTAL_COUNT to 0,
                        EXTRA_ERROR_MESSAGE to "No keys provided"
                    )
                )
                return
            }

            // Import each key
            val importedGuids = mutableListOf<String>()
            for (keyUrl in keysToImport) {
                try {
                    val guid = com.v2ray.ang.helper.ProfileImporter.importProfile(context, keyUrl)
                    if (!guid.isNullOrBlank()) {
                        importedCount++
                        importedGuids.add(guid)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to import key", e)
                }
            }

            // Test only the imported keys to avoid timeout if server list is large
            var workingCount = 0
            for (guid in importedGuids) {
                val config = MmkvManager.decodeServerConfig(guid)
                if (config != null) {
                    val server = config.server
                    val portStr = config.serverPort
                    if (!server.isNullOrBlank() && !portStr.isNullOrBlank()) {
                        val port = portStr.toIntOrNull()
                        if (port != null) {
                            val delay = runDelayTest(server, port)
                            if (delay > 0 && delay < 10000) {
                                workingCount++
                            }
                        }
                    }
                }
            }

            if (workingCount > 0) {
                selectBestAndStart(context)
            }

            val totalCount = MmkvManager.decodeServerList().size

            // Send response
            Log.d(TAG, "Import keys result: imported=$importedCount, working=$workingCount, total=$totalCount")
            context.toast("V2Ray: Imported $importedCount, working $workingCount")
            sendCommandResponse(
                context,
                RESP_IMPORT_RESULT,
                mapOf(
                    EXTRA_IMPORTED_COUNT to importedCount,
                    EXTRA_WORKING_COUNT to workingCount,
                    EXTRA_TOTAL_COUNT to totalCount
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in import keys command", e)
            sendCommandResponse(
                context,
                RESP_IMPORT_RESULT,
                mapOf(
                    EXTRA_IMPORTED_COUNT to 0,
                    EXTRA_WORKING_COUNT to 0,
                    EXTRA_TOTAL_COUNT to 0,
                    EXTRA_ERROR_MESSAGE to e.message
                )
            )
        }
    }

    /**
     * Handles the STATUS_CHECK command from NekoX.
     * Returns whether the v2ray service is running and the last TCP ping to the selected server.
     */
    private fun handleStatusCheckCommand(context: Context) {
        // Return running state immediately — no TCP test.
        // TCP test via socketConnectTime blocks the main thread for up to 3000ms, which combined
        // with process wakeup latency can exceed TeleRay's 5s STATUS_CHECK timeout.
        // The ping value isn't used by TeleRay's decision logic anyway (only isRunning matters).
        val isRunning = V2RayServiceManager.isRunning()
        sendCommandResponse(
            context,
            RESP_STATUS,
            mapOf(
                EXTRA_IS_RUNNING   to isRunning,
                EXTRA_LAST_PING_MS to -1L
            )
        )
    }

    /**
     * Handles the SELECT_BEST_KEY command from NekoX.
     *
     * Selection strategy:
     *
     * Step 1 — Build a priority-sorted candidate list from stored testDelayMillis:
     *   keys with positive stored delay first (sorted fastest), untested (0) second, failed (-ve) last.
     *   This ordering maximises the chance of finding a working key on the first real ping.
     *
     * Step 2 — Live real proxy test via Libv2ray.measureOutboundDelay (runs every cycle).
     *   Stored values can be stale (server worked before, now dead), so always re-validate.
     *   TCP ping is useless here — v2ray servers are typically ISP-blocked at TCP level;
     *   measureOutboundDelay tests through the obfuscated v2ray tunnel instead.
     *   Tests keys in priority order, breaks on the first working key (saves ~10s per remaining key).
     *   Stores fresh results in MMKV so the v2rayTg UI reflects them.
     *   Switches to the new key via MSG_STATE_RESTART when the service is already running
     *   (startVServiceFromToggle does nothing while isRunning=true).
     *
     * Step 3 — Optimistic fallback: switch to any untested key (testDelayMillis == 0).
     *   Triggered when all real pings fail.
     *   Does NOT report working > 0 to TeleRay (TeleRay falls back to MTProxy as backup)
     *   but starts v2ray with the new key so it may connect in the background.
     *
     * Response RESP_SELECT_BEST_KEY: EXTRA_WORKING_COUNT, EXTRA_SELECTED_KEY_GUID, EXTRA_LAST_PING_MS.
     */
    private fun handleSelectBestKeyCommand(context: Context, trustStored: Boolean = false) {
        // Two-path strategy depending on whether v2ray VPN is currently active:
        //
        // PATH A — v2ray IS running:
        //   measureOutboundDelay() is useless here: the test traffic routes THROUGH the active VPN.
        //   A bad active key means the VPN tunnel can't forward traffic → all keys appear broken,
        //   even working ones. Solution: select the best candidate using STORED testDelayMillis
        //   (skipping the current bad key), switch, restart. Report working=1 if stored delay > 0
        //   (optimistic — TeleRay disables proxy and connects via v2ray; next cycle verifies).
        //
        // PATH B — v2ray is NOT running:
        //   Traffic goes through the real internet — measureOutboundDelay() is accurate.
        //   Use it to verify up to MAX_REAL_PING_KEYS candidates (priority-sorted by stored delay).
        //   Break on first working key to stay within the 60s goAsync window.
        try {
            val serverList = MmkvManager.decodeServerList()
            val currentGuid = MmkvManager.getSelectServer()
            val isRunning = V2RayServiceManager.isRunning()

            // Priority-sort candidates: positive stored delay first (fastest), untested (0) second,
            // previously-failed (-ve) last. Shorter stored ping = better position within each tier.
            data class Candidate(val guid: String, val storedDelay: Long)
            val candidates = serverList.map { guid ->
                Candidate(guid, MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L)
            }.sortedWith(compareBy(
                { if (it.storedDelay > 0) 0 else if (it.storedDelay == 0L) 1 else 2 },
                { if (it.storedDelay > 0) it.storedDelay else Long.MAX_VALUE }
            ))

            var bestGuid: String? = null
            var bestPing = Long.MAX_VALUE
            var workingCount = 0

            // PATH A: VPN active — only use if current key has a positive stored delay,
            // meaning it was recently confirmed working. If current key is bad/untested
            // (storedDelay ≤ 0), stop VPN immediately and do PATH B real tests.
            var pathAHandled = false
            if (isRunning) {
                val currentStoredDelay = candidates.firstOrNull { it.guid == currentGuid }?.storedDelay ?: 0L
                if (currentStoredDelay > 0) {
                    // Current key looks good by stored data — try to switch to best alternative
                    val best = candidates.firstOrNull { it.storedDelay > 0 && it.guid != currentGuid }
                        ?: candidates.firstOrNull { it.storedDelay == 0L && it.guid != currentGuid }

                    if (best != null) {
                        // ── PATH A success: switch using stored delay ──
                        bestGuid = best.guid
                        bestPing = if (best.storedDelay > 0) best.storedDelay else Long.MAX_VALUE
                        workingCount = if (best.storedDelay > 0) 1 else 0
                        MmkvManager.setSelectServer(bestGuid)
                        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_RESTART, "")
                        val pingText = if (best.storedDelay > 0) "${best.storedDelay}мс (saved)" else "нет данных"
                        Log.d(TAG, "SELECT_BEST_KEY pathA: switched to ${bestGuid!!.take(8)} stored=$pingText")
                        context.toast("V2Ray: ключ переключён, ping $pingText")
                        pathAHandled = true
                    } else {
                        // No alternative — stop VPN, fall through to PATH B
                        Log.d(TAG, "SELECT_BEST_KEY pathA: no alternative candidate, stopping VPN for PATH B real test")
                        MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
                        Thread.sleep(2000)
                    }
                } else {
                    // Current key is bad/untested (storedDelay=$currentStoredDelay) — stop VPN,
                    // use PATH B for fresh real measurements instead of oscillating via stale data.
                    Log.d(TAG, "SELECT_BEST_KEY pathA: current key bad (storedDelay=$currentStoredDelay), stopping VPN for PATH B")
                    MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
                    Thread.sleep(2000)
                }
            }

            if (!pathAHandled) {
                if (trustStored) {
                    // ── PATH C: trust_stored=true — TEST_REAL_PING already ran, results are fresh ──
                    // Candidates are sorted by storedDelay; just pick the first with positive delay.
                    val best = candidates.firstOrNull { it.storedDelay > 0 }
                    if (best != null) {
                        bestGuid = best.guid
                        bestPing = best.storedDelay
                        workingCount = 1
                        MmkvManager.setSelectServer(bestGuid)
                        V2RayServiceManager.startVServiceFromToggle(context)
                        val pingText = "${bestPing}мс"
                        context.toast("V2Ray: лучший ключ, ping $pingText")
                        Log.d(TAG, "SELECT_BEST_KEY pathC (trust_stored): ${bestGuid!!.take(8)} ping=$pingText")
                    } else {
                        // No tested keys — optimistic: try first untested
                        val untested = candidates.firstOrNull { it.storedDelay == 0L }
                        if (untested != null) {
                            bestGuid = untested.guid
                            MmkvManager.setSelectServer(bestGuid)
                            V2RayServiceManager.startVServiceFromToggle(context)
                            Log.d(TAG, "SELECT_BEST_KEY pathC fallback: untested ${bestGuid!!.take(8)}")
                        }
                    }
                } else {
                    // ── PATH B: v2ray not running (or PATH A stopped it) — real proxy test via measureOutboundDelay ──
                    val MAX_REAL_PING_KEYS = 12 // test up to 12 candidates; stops early on first working key (break)
                    try {
                        Seq.setContext(context.applicationContext)
                        Libv2ray.initV2Env(
                            Utils.userAssetPath(context),
                            Utils.getDeviceIdForXUDPBaseKey()
                        )
                        val testUrl = SettingsManager.getDelayTestUrl()
                        val limit = minOf(candidates.size, MAX_REAL_PING_KEYS)
                        for (i in 0 until limit) {
                            val guid = candidates[i].guid
                            val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
                            if (!configResult.status) {
                                Log.d(TAG, "SELECT_BEST_KEY pathB: no config for ${guid.take(8)}")
                                continue
                            }
                            val delay = try {
                                Libv2ray.measureOutboundDelay(configResult.content, testUrl)
                            } catch (e: Exception) {
                                Log.e(TAG, "SELECT_BEST_KEY pathB: measureOutboundDelay failed", e)
                                -1L
                            }
                            MmkvManager.encodeServerTestDelayMillis(guid, delay)
                            Log.d(TAG, "SELECT_BEST_KEY pathB: ${guid.take(8)} freshPing=${delay}ms")
                            if (delay > 0) {
                                workingCount++
                                bestPing = delay
                                bestGuid = guid
                                break
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "SELECT_BEST_KEY pathB: Libv2ray init/test failed", e)
                    }

                    // Optimistic fallback: switch to first untested key (workingCount stays 0 →
                    // TeleRay uses MTProxy as backup while v2ray restarts with the new key)
                    if (bestGuid == null) {
                        val untested = candidates.firstOrNull { it.storedDelay == 0L && it.guid != currentGuid }
                        if (untested != null) {
                            bestGuid = untested.guid
                            MmkvManager.setSelectServer(bestGuid)
                            V2RayServiceManager.startVServiceFromToggle(context)
                            Log.d(TAG, "SELECT_BEST_KEY pathB fallback: switched to untested ${bestGuid.take(8)}")
                        }
                    } else {
                        MmkvManager.setSelectServer(bestGuid)
                        V2RayServiceManager.startVServiceFromToggle(context)
                        val pingText = if (bestPing < Long.MAX_VALUE) "${bestPing}мс" else "неизвестно"
                        context.toast("V2Ray: лучший ключ выбран, ping $pingText")
                        MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_SUCCESS, "")
                        Log.d(TAG, "SELECT_BEST_KEY pathB: selected ${bestGuid.take(8)} ping=$pingText")
                    }
                }
            }

            sendCommandResponse(
                context,
                RESP_SELECT_BEST_KEY,
                mapOf(
                    EXTRA_WORKING_COUNT to workingCount,
                    EXTRA_SELECTED_KEY_GUID to (bestGuid ?: ""),
                    EXTRA_LAST_PING_MS to (if (bestPing < Long.MAX_VALUE) bestPing else -1L)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error in select best key command", e)
            sendCommandResponse(
                context,
                RESP_SELECT_BEST_KEY,
                mapOf(
                    EXTRA_WORKING_COUNT to 0,
                    EXTRA_SELECTED_KEY_GUID to "",
                    EXTRA_LAST_PING_MS to -1L,
                    EXTRA_ERROR_MESSAGE to e.message
                )
            )
        }
    }

    /**
     * TCP-pings ALL profiles in parallel (3s timeout each), stores results in MMKV testDelayMillis,
     * then sends RESP_TEST_REAL_PING_DONE with working/total counts.
     * This is the IPC equivalent of v2rayNG UI "Время отклика профилей группы".
     */
    private fun handleTestRealPingCommand(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            val totalCount = serverList.size
            val workingCount = java.util.concurrent.atomic.AtomicInteger(0)

            // Run all TCP pings in parallel — socketConnectTime uses 3s timeout per socket
            val threads = serverList.mapNotNull { guid ->
                val config = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                val server = config.server?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val port   = config.serverPort?.trim()?.toIntOrNull() ?: return@mapNotNull null
                Thread {
                    val delay = runDelayTest(server, port)
                    MmkvManager.encodeServerTestDelayMillis(guid, delay)
                    Log.d(TAG, "TEST_REAL_PING: ${guid.take(8)} ping=${delay}ms")
                    if (delay > 0) workingCount.incrementAndGet()
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(5000L) }   // parallel → max ~5s total

            val working = workingCount.get()
            Log.d(TAG, "TEST_REAL_PING done: $working/$totalCount working")
            context.toast("V2Ray: пинг профилей: $working/$totalCount рабочих")
            sendCommandResponse(context, RESP_TEST_REAL_PING_DONE, mapOf(
                EXTRA_WORKING_COUNT to working,
                EXTRA_TOTAL_COUNT   to totalCount
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Error in TEST_REAL_PING command", e)
            sendCommandResponse(context, RESP_TEST_REAL_PING_DONE, mapOf(
                EXTRA_WORKING_COUNT   to 0,
                EXTRA_TOTAL_COUNT     to 0,
                EXTRA_ERROR_MESSAGE   to e.message
            ))
        }
    }

    /**
     * Sorts the MMKV server list by testDelayMillis ascending (best/fastest first).
     * Profiles with delay ≤ 0 (failed or untested) move to the end.
     * Fire-and-forget: no response sent.
     * IPC equivalent of v2rayNG UI "Сортировать по результатам теста".
     */
    private fun handleSortByTestCommand(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            data class Entry(val guid: String, val delay: Long)
            val entries = serverList.map { guid ->
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                Entry(guid, if (delay > 0L) delay else Long.MAX_VALUE)
            }.sortedBy { it.delay }

            val sorted = ArrayList(entries.map { it.guid })
            MmkvManager.encodeServerList(sorted)
            Log.d(TAG, "SORT_BY_TEST: sorted ${sorted.size} profiles")
            context.toast("V2Ray: профили отсортированы по пингу")
        } catch (e: Exception) {
            Log.e(TAG, "Error in SORT_BY_TEST command", e)
        }
    }

    /**
     * Removes duplicate profiles from MMKV storage.
     * Two profiles are considered duplicates if they share the same server+serverPort combination.
     * Among duplicates, keeps the one with the best positive stored delay (or the most recently
     * added if all delays are ≤ 0). Never removes the currently selected profile.
     */
    private fun handleDedupProfilesCommand(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            if (serverList.size < 2) return
            val currentGuid = MmkvManager.getSelectServer()

            // Build map: dedup key → best guid to keep
            val keepMap = mutableMapOf<String, String>() // dedupKey → guid to keep
            val keepDelay = mutableMapOf<String, Long>() // dedupKey → stored delay of kept entry
            val keepTime  = mutableMapOf<String, Long>() // dedupKey → addedTime of kept entry

            for (guid in serverList) {
                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                val server = config.server?.trim() ?: continue
                val port   = config.serverPort?.trim() ?: continue
                if (server.isBlank() || port.isBlank()) continue

                val key = "$server:$port"
                val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                val time  = config.addedTime

                val existingGuid = keepMap[key]
                if (existingGuid == null) {
                    keepMap[key] = guid
                    keepDelay[key] = delay
                    keepTime[key] = time
                } else {
                    val existDelay = keepDelay[key] ?: 0L
                    // Prefer: positive delay (best wins) > untested (0) > failed (-ve) > newer added
                    val prefer = when {
                        delay > 0 && existDelay > 0 -> delay < existDelay // both tested: keep faster
                        delay > 0 && existDelay <= 0 -> true              // new is tested, old is not
                        delay == 0L && existDelay < 0 -> true             // new is untested, old failed
                        delay == existDelay && time > (keepTime[key] ?: 0L) -> true // same state: newer
                        else -> false
                    }
                    if (prefer) {
                        keepMap[key] = guid
                        keepDelay[key] = delay
                        keepTime[key] = time
                    }
                }
            }

            val guidsToKeep = keepMap.values.toSet()
            var removed = 0
            for (guid in serverList) {
                if (guid !in guidsToKeep && guid != currentGuid) {
                    MmkvManager.removeServer(guid)
                    removed++
                }
            }
            if (removed > 0) {
                Log.d(TAG, "DEDUP_PROFILES: removed $removed duplicate profiles")
                context.toast("V2Ray: удалено $removed дублирующихся профилей")
            } else {
                Log.d(TAG, "DEDUP_PROFILES: no duplicates found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in dedup profiles command", e)
        }
    }

    /**
     * Selects the best key (minimum ping) and starts the v2ray service.
     */
    private fun selectBestAndStart(context: Context) {
        try {
            val serverList = MmkvManager.decodeServerList()
            var bestGuid: String? = null
            var bestPing = Long.MAX_VALUE

            for (guid in serverList) {
                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                val server = config.server
                val portStr = config.serverPort
                if (server.isNullOrBlank() || portStr.isNullOrBlank()) continue
                val port = portStr.toIntOrNull() ?: continue
                val delay = runDelayTest(server, port)
                if (delay in 1..9999) {
                    if (delay < bestPing) {
                        bestPing = delay
                        bestGuid = guid
                    }
                }
            }

            if (bestGuid != null) {
                val currentGuid = MmkvManager.getSelectServer()
                MmkvManager.setSelectServer(bestGuid)
                if (bestGuid != currentGuid || !V2RayServiceManager.isRunning()) {
                    V2RayServiceManager.startVServiceFromToggle(context)
                }
                Log.d(TAG, "selectBestAndStart: selected $bestGuid ping=${bestPing}ms")
                // Notify UI to refresh the selected marker
                MessageUtil.sendMsg2UI(context, AppConfig.MSG_STATE_START_SUCCESS, "")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in selectBestAndStart", e)
        }
    }

    /**
     * Sends a command response to NekoX.
     * @param context The context.
     * @param command The response command code.
     * @param data Optional data to include in the response.
     */
    private fun sendCommandResponse(context: Context, command: Int, data: Map<String, Any?>?) {
        val intent = Intent(ACTION_V2RAY_RESPONSE).apply {
            putExtra(EXTRA_COMMAND, command)
            data?.forEach { (key, value) ->
                when (value) {
                    is String -> putExtra(key, value)
                    is Int -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }
        Log.d(TAG, "Sending command response: $command to action $ACTION_V2RAY_RESPONSE")
        context.sendBroadcast(intent)
    }
}
