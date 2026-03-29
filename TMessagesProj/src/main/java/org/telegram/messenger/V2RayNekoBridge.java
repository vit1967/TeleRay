package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.telegram.tgnet.ConnectionsManager;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for NekoX to communicate with v2rayNG module.
 * Provides methods for:
 * - Checking v2rayNG availability
 * - Testing keys
 * - Importing keys
 * - Receiving test results
 */
public class V2RayNekoBridge {

    private static final String TAG = "V2RayNekoBridge";

    // Command enums (must match v2rayNG side)
    public static final int CMD_TEST_KEYS = 1001;
    public static final int CMD_IMPORT_KEYS = 1002;
    public static final int CMD_START_V2RAY = 1003;
    public static final int CMD_STOP_V2RAY = 1004;
    public static final int CMD_STATUS_CHECK = 1005;
    public static final int CMD_SELECT_BEST_KEY = 1006;
    public static final int CMD_DEDUP_PROFILES = 1007;
    public static final int CMD_TEST_REAL_PING  = 1008;
    public static final int CMD_SORT_BY_TEST    = 1009;

    // Response codes
    public static final int RESP_TEST_RESULT        = 2001;
    public static final int RESP_IMPORT_RESULT      = 2002;
    public static final int RESP_NO_V2RAY           = 2999;
    public static final int RESP_NO_ANSWER          = 2998;
    public static final int RESP_STATUS             = 2003;
    public static final int RESP_SELECT_BEST_KEY    = 2004;
    public static final int RESP_TEST_REAL_PING_DONE = 2005;

    private static final String EXTRA_TRUST_STORED = "trust_stored";

    // Broadcast actions
    private static final String ACTION_V2RAY_COMMAND = "com.v2ray.ang.action.NEKOX_COMMAND";
    private static final String ACTION_V2RAY_RESPONSE = "com.v2ray.ang.action.NEKOX_RESPONSE";
    private static final String ACTION_RESPONSE_STARTED = "com.v2ray.ang.action.RESPONSE_STARTED";

    // v2rayNG package names (for IPC between separate apps)
    private static final String V2RAY_PACKAGE_NAME = "com.v2ray.ang";
    private static final String V2RAY_DEBUG_PACKAGE_NAME = "com.v2ray.ang.debug";

    /**
     * Get the package name of the installed v2rayNG app.
     * Checks for both production and debug versions.
     */
    private String getV2RayPackage() {
        try {
            context.getPackageManager().getPackageInfo(V2RAY_DEBUG_PACKAGE_NAME, 0);
            return V2RAY_DEBUG_PACKAGE_NAME;
        } catch (Exception e) {
            try {
                context.getPackageManager().getPackageInfo(V2RAY_PACKAGE_NAME, 0);
                return V2RAY_PACKAGE_NAME;
            } catch (Exception e2) {
                return V2RAY_PACKAGE_NAME; // Fallback to default
            }
        }
    }

    // Extra keys
    private static final String EXTRA_COMMAND = "command";
    private static final String EXTRA_KEYS = "keys";
    private static final String EXTRA_KEY1 = "key1";
    private static final String EXTRA_KEY2 = "key2";
    private static final String EXTRA_IMPORTED_COUNT = "imported_count";
    private static final String EXTRA_WORKING_COUNT = "working_count";
    private static final String EXTRA_TOTAL_COUNT = "total_count";
    private static final String EXTRA_ERROR = "error";
    private static final String EXTRA_ERROR_MESSAGE = "error_message";
    private static final String EXTRA_IS_RUNNING   = "is_running";
    private static final String EXTRA_LAST_PING_MS = "last_ping_ms";
    private static final String EXTRA_SELECTED_KEY_GUID = "selected_key_guid";

    private static V2RayNekoBridge instance;
    private Context context;
    private BroadcastReceiver responseReceiver;
    private boolean isRegistered = false;
    private Handler mainHandler;

    // Callbacks
    private OnV2RayResponseListener responseListener;

    public interface OnV2RayResponseListener {
        void onTestResult(int working, int total);
        void onImportResult(int imported, int working, int total);
        void onError(String error);
        void onNoV2Ray();
        void onNoAnswer();
        void onStatusResult(boolean isRunning, long lastPingMs, String errorMsg);
        void onStarted(boolean success, String errorMsg);
        void onSelectBestKeyResult(int working, String selectedGuid, long pingMs);
        void onTestRealPingDone(int working, int total);
    }

    private V2RayNekoBridge(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized V2RayNekoBridge getInstance(Context context) {
        if (instance == null) {
            instance = new V2RayNekoBridge(context);
        }
        return instance;
    }

    /**
     * Initialize the bridge and register broadcast receiver.
     */
    public void initialize() {
        if (isRegistered) {
            return;
        }

        responseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                handleResponse(intent);
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_V2RAY_RESPONSE);
        filter.addAction(ACTION_RESPONSE_STARTED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(context, responseReceiver, filter, ContextCompat.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(responseReceiver, filter);
        }
        isRegistered = true;
        Log.d(TAG, "V2RayNekoBridge initialized");
    }

    /**
     * Release resources.
     */
    public void release() {
        if (isRegistered && responseReceiver != null) {
            try {
                context.unregisterReceiver(responseReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver", e);
            }
            isRegistered = false;
        }
    }

    public void setOnV2RayResponseListener(OnV2RayResponseListener listener) {
        this.responseListener = listener;
    }

    // Pending timeout runnable
    private Runnable pendingTimeoutRunnable;

    /**
     * Check if v2rayNG is running and ready.
     * @return true if v2rayNG is available
     */
    public boolean isV2RayAvailable() {
        try {
            // Send a ping command (CMD_START_V2RAY with no action)
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_START_V2RAY);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent availability check command to " + intent.getPackage());

            // Note: Actual availability will be determined by response callback
            // This method just sends the ping
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error checking v2ray availability", e);
            return false;
        }
    }

    /**
     * Check if v2rayNG receiver is registered in the system.
     * This is a synchronous check that doesn't rely on callbacks.
     * @return true if the receiver component exists
     */
    public boolean isV2RayReceiverRegistered() {
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            String pkg = getV2RayPackage();
            intent.setPackage(pkg);
            // Check if there's any receiver for this action
            if (context.getPackageManager().queryBroadcastReceivers(intent, 0).size() > 0) {
                return true;
            }
            // Fallback: check if package is installed at all
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "V2Ray receiver not available", e);
            return false;
        }
    }

    /**
     * Send test keys command to v2rayNG.
     */
    public void sendTestKeysCommand() {
        // Cancel any pending timeout
        if (pendingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(pendingTimeoutRunnable);
        }

        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_TEST_KEYS);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent TEST_KEYS command to " + intent.getPackage());

            // Schedule timeout for no answer
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) {
                    responseListener.onNoAnswer();
                }
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 8000); // 8 second timeout

        } catch (Exception e) {
            Log.e(TAG, "Error sending test keys command", e);
            if (responseListener != null) {
                responseListener.onError(e.getMessage());
            }
        }
    }

    /**
     * Send import keys command to v2rayNG.
     * @param key1 First key (vless/vmess/trojan URL)
     * @param key2 Second key (vless/vmess/trojan URL)
     */
    public void sendImportKeysCommand(String key1, String key2) {
        // Cancel any pending timeout
        if (pendingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(pendingTimeoutRunnable);
        }

        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_IMPORT_KEYS);
            intent.putExtra(EXTRA_KEY1, key1);
            intent.putExtra(EXTRA_KEY2, key2);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent IMPORT_KEYS command to " + intent.getPackage());

            // Schedule timeout for no answer
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) {
                    responseListener.onNoAnswer();
                }
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 12000); // 12 second timeout for import + test

        } catch (Exception e) {
            Log.e(TAG, "Error sending import keys command", e);
            if (responseListener != null) {
                responseListener.onError(e.getMessage());
            }
        }
    }

    private void cancelPendingTimeout() {
        if (pendingTimeoutRunnable != null) {
            mainHandler.removeCallbacks(pendingTimeoutRunnable);
            pendingTimeoutRunnable = null;
        }
    }

    /**
     * Send STATUS_CHECK command to v2rayNG.
     * Callback: onStatusResult(isRunning, lastPingMs, errorMsg) or onNoAnswer() on timeout.
     */
    public void sendStatusCheckCommand() {
        cancelPendingTimeout();
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_STATUS_CHECK);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent STATUS_CHECK command to " + intent.getPackage());
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) responseListener.onNoAnswer();
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 5000);
        } catch (Exception e) {
            Log.e(TAG, "Error sending status check command", e);
            if (responseListener != null) responseListener.onError(e.getMessage());
        }
    }

    /**
     * Send START_V2RAY command to v2rayNG.
     * Callback: onStarted(success, errorMsg) or onStarted(false, "timeout") on timeout.
     */
    public void sendStartV2RayCommand() {
        cancelPendingTimeout();
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_START_V2RAY);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent START_V2RAY command to " + intent.getPackage());
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) responseListener.onStarted(false, "timeout");
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 10000);
        } catch (Exception e) {
            Log.e(TAG, "Error sending start v2ray command", e);
            if (responseListener != null) responseListener.onStarted(false, e.getMessage());
        }
    }

    /**
     * Send SELECT_BEST_KEY command to v2rayNG (no trust_stored — PATH B real test).
     */
    public void sendSelectBestKeyCommand() {
        sendSelectBestKeyCommand(false);
    }

    /**
     * Send SELECT_BEST_KEY command to v2rayNG.
     * trustStored=false: PATH B — real proxy test via measureOutboundDelay (slow, accurate).
     * trustStored=true:  PATH C — use stored testDelayMillis from last TEST_REAL_PING (fast).
     * Callback: onSelectBestKeyResult(working, selectedGuid, pingMs) or onNoAnswer() on timeout.
     */
    public void sendSelectBestKeyCommand(boolean trustStored) {
        cancelPendingTimeout();
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_SELECT_BEST_KEY);
            if (trustStored) intent.putExtra(EXTRA_TRUST_STORED, true);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent SELECT_BEST_KEY command (trustStored=" + trustStored + ") to " + intent.getPackage());
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) responseListener.onNoAnswer();
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 55000);
        } catch (Exception e) {
            Log.e(TAG, "Error sending select best key command", e);
            if (responseListener != null) responseListener.onError(e.getMessage());
        }
    }

    /**
     * Send TEST_REAL_PING command to v2rayNG.
     * v2rayNG will TCP-ping ALL profiles in parallel, store results in MMKV testDelayMillis,
     * and reply with RESP_TEST_REAL_PING_DONE.
     * Callback: onTestRealPingDone(working, total) or onNoAnswer() on timeout.
     */
    public void sendTestRealPingCommand() {
        cancelPendingTimeout();
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_TEST_REAL_PING);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent TEST_REAL_PING command to " + intent.getPackage());
            pendingTimeoutRunnable = () -> {
                if (responseListener != null) responseListener.onNoAnswer();
                pendingTimeoutRunnable = null;
            };
            mainHandler.postDelayed(pendingTimeoutRunnable, 15000); // 15s — parallel TCP pings (3s each)
        } catch (Exception e) {
            Log.e(TAG, "Error sending TEST_REAL_PING command", e);
            if (responseListener != null) responseListener.onError(e.getMessage());
        }
    }

    /**
     * Send SORT_BY_TEST command to v2rayNG (fire-and-forget, no response).
     * v2rayNG will sort the MMKV server list by testDelayMillis ascending.
     */
    public void sendSortByTestCommand() {
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_SORT_BY_TEST);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent SORT_BY_TEST command to " + intent.getPackage());
        } catch (Exception e) {
            Log.e(TAG, "Error sending SORT_BY_TEST command", e);
        }
    }

    /**
     * Send STOP_V2RAY command to v2rayNG (fire-and-forget, no response expected).
     */
    public void sendStopV2RayCommand() {
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_STOP_V2RAY);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent STOP_V2RAY command to " + intent.getPackage());
        } catch (Exception e) {
            Log.e(TAG, "Error sending stop v2ray command", e);
        }
    }

    /**
     * Send DEDUP_PROFILES command to v2rayNG (fire-and-forget, no response expected).
     * v2rayNG will remove duplicate profiles keeping the best per server:port.
     */
    public void sendDedupProfilesCommand() {
        try {
            Intent intent = new Intent(ACTION_V2RAY_COMMAND);
            intent.setPackage(getV2RayPackage());
            intent.putExtra(EXTRA_COMMAND, CMD_DEDUP_PROFILES);
            context.sendBroadcast(intent);
            Log.d(TAG, "Sent DEDUP_PROFILES command to " + intent.getPackage());
        } catch (Exception e) {
            Log.e(TAG, "Error sending dedup profiles command", e);
        }
    }

    /**
     * Disable all Telegram proxies and use direct connection (v2rayNG will handle it).
     */
    private void disableAllProxies() {
        if (SharedConfig.currentProxy != null) {
            SharedConfig.currentProxy = null;
            SharedConfig.saveProxyList();
            ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
            // Notify about proxy change
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
    }

    /**
     * Handle response from v2rayNG.
     */
    private void handleResponse(Intent intent) {
        if (intent == null) {
            return;
        }

        // Handle ACTION_RESPONSE_STARTED separately (not EXTRA_COMMAND based)
        if (ACTION_RESPONSE_STARTED.equals(intent.getAction())) {
            cancelPendingTimeout();
            String err = intent.getStringExtra(EXTRA_ERROR_MESSAGE);
            boolean success = (err == null);
            if (responseListener != null) {
                final String finalErr = err;
                mainHandler.post(() -> responseListener.onStarted(success, finalErr));
            }
            return;
        }

        int responseType = intent.getIntExtra(EXTRA_COMMAND, 0);
        Log.d(TAG, "Received response from v2rayNG: " + responseType);

        // Cancel any pending timeout
        cancelPendingTimeout();

        switch (responseType) {
            case RESP_TEST_RESULT:
                int working = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
                int total = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0);
                if (working > 0) {
                    disableAllProxies();
                }
                if (responseListener != null) {
                    mainHandler.post(() -> responseListener.onTestResult(working, total));
                }
                break;

            case RESP_IMPORT_RESULT:
                int imported = intent.getIntExtra(EXTRA_IMPORTED_COUNT, 0);
                working = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
                total = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0);
                if (working > 0) {
                    disableAllProxies();
                }
                if (responseListener != null) {
                    mainHandler.post(() -> responseListener.onImportResult(imported, working, total));
                }
                break;

            case RESP_NO_V2RAY:
                if (responseListener != null) {
                    mainHandler.post(() -> responseListener.onNoV2Ray());
                }
                break;

            case RESP_NO_ANSWER:
                // This shouldn't happen via broadcast, but handle it anyway
                if (responseListener != null) {
                    mainHandler.post(() -> responseListener.onNoAnswer());
                }
                break;

            case RESP_STATUS: {
                boolean isRunning = intent.getBooleanExtra(EXTRA_IS_RUNNING, false);
                long ping = intent.getLongExtra(EXTRA_LAST_PING_MS, -1L);
                String errMsg = intent.getStringExtra(EXTRA_ERROR_MESSAGE);
                if (responseListener != null) {
                    final boolean r = isRunning;
                    final long p = ping;
                    final String e = errMsg;
                    mainHandler.post(() -> responseListener.onStatusResult(r, p, e));
                }
                break;
            }

            case RESP_SELECT_BEST_KEY: {
                int w = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
                String guid = intent.getStringExtra(EXTRA_SELECTED_KEY_GUID);
                long pingMs = intent.getLongExtra(EXTRA_LAST_PING_MS, -1L);
                if (w > 0) {
                    disableAllProxies();
                }
                if (responseListener != null) {
                    final int fw = w;
                    final String fg = guid;
                    final long fp = pingMs;
                    mainHandler.post(() -> responseListener.onSelectBestKeyResult(fw, fg, fp));
                }
                break;
            }

            case RESP_TEST_REAL_PING_DONE: {
                int w = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
                int t = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0);
                if (responseListener != null) {
                    final int fw = w, ft = t;
                    mainHandler.post(() -> responseListener.onTestRealPingDone(fw, ft));
                }
                break;
            }

            default:
                String error = intent.getStringExtra(EXTRA_ERROR);
                if (error != null && responseListener != null) {
                    final String finalError = error;
                    mainHandler.post(() -> responseListener.onError(finalError));
                }
                break;
        }
    }
}
