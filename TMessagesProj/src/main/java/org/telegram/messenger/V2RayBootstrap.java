package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.v2ray.ang.AngLibraryInitializer;
import com.v2ray.ang.helper.ProfileImporter;
import com.v2ray.ang.service.V2RayServiceManager;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class V2RayBootstrap {

    private static final String PREFS_NAME = "v2ray_bootstrap";
    private static final String PREF_LAST_IMPORTED_HASH = "last_imported_hash";
    private static final AtomicBoolean bootstrapExecuted = new AtomicBoolean(false);

    private V2RayBootstrap() {
    }

    public static void ensureRunningBeforeAuthorization(Context context) {
        android.util.Log.d("V2RayBootstrap", "ensureRunningBeforeAuthorization called, context=" + (context != null));
        if (context == null || !bootstrapExecuted.compareAndSet(false, true)) {
            android.util.Log.d("V2RayBootstrap", "Aborting: context=" + (context != null) + ", alreadyExecuted=" + !bootstrapExecuted.get());
            return;
        }
        try {
            final Context appContext = context.getApplicationContext();
            
            // Initialize MMKV first (required for v2ray in separate process)
            android.util.Log.d("V2RayBootstrap", "Initializing MMKV");
            try {
                com.v2ray.ang.AngLibraryInitializer.INSTANCE.initializeMmkvOnly(appContext);
                android.util.Log.d("V2RayBootstrap", "MMKV initialized successfully");
            } catch (Throwable e) {
                android.util.Log.e("V2RayBootstrap", "MMKV initialization failed", e);
            }
            
            android.util.Log.d("V2RayBootstrap", "Initializing AngLibraryInitializer");
            AngLibraryInitializer.INSTANCE.initialize(appContext);
            android.util.Log.d("V2RayBootstrap", "V2Ray library initialized (service auto-start disabled)");
            
            // Note: Service is no longer auto-started here to prevent crashes when no profile is configured
            // The service will be started by V2RayNGReceiver when needed
        } catch (Throwable t) {
            android.util.Log.e("V2RayBootstrap", "Error in ensureRunningBeforeAuthorization", t);
            FileLog.e(t);
        }
    }

    private static void syncProfilesFromSharedConfig(Context context) {
        String key1 = normalizeProfile(SharedConfig.v2rayKey1);
        String key2 = normalizeProfile(SharedConfig.v2rayKey2);
        int currentHash = Objects.hash(key1, key2);

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int previousHash = prefs.getInt(PREF_LAST_IMPORTED_HASH, Integer.MIN_VALUE);
        if (previousHash == currentHash) {
            return;
        }

        boolean imported = false;
        imported |= importAndSelectProfile(context, key1);
        imported |= importAndSelectProfile(context, key2);

        if (imported || (TextUtils.isEmpty(key1) && TextUtils.isEmpty(key2))) {
            prefs.edit().putInt(PREF_LAST_IMPORTED_HASH, currentHash).apply();
        }
    }

    private static boolean importAndSelectProfile(Context context, String profile) {
        if (TextUtils.isEmpty(profile)) {
            return false;
        }
        try {
            String guid = ProfileImporter.importProfile(context, profile);
            if (!TextUtils.isEmpty(guid)) {
                ProfileImporter.selectProfile(guid);
                return true;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return false;
    }

    private static String normalizeProfile(String profile) {
        if (profile == null) {
            return "";
        }
        String trimmed = profile.trim();
        if (trimmed.startsWith("vless://") || trimmed.startsWith("vmess://")
            || trimmed.startsWith("trojan://") || trimmed.startsWith("ss://")
            || trimmed.startsWith("socks://") || trimmed.startsWith("hysteria2://")
            || trimmed.startsWith("wireguard://")) {
            return trimmed;
        }
        return "";
    }

    private static boolean isV2RayRunning(Context context) {
        try {
            // First check if the V2Ray point is running
            if (V2RayServiceManager.INSTANCE.isRunning()) {
                return true;
            }
            
            // Also check for running processes (for standalone mode)
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager != null) {
                List<ActivityManager.RunningAppProcessInfo> runningProcesses = activityManager.getRunningAppProcesses();
                if (runningProcesses != null) {
                    for (ActivityManager.RunningAppProcessInfo processInfo : runningProcesses) {
                        if (processInfo == null || TextUtils.isEmpty(processInfo.processName)) {
                            continue;
                        }
                        String processName = processInfo.processName;
                        if (processName.contains("RunSoLibV2RayDaemon")
                            || processName.contains("com.v2ray.ang")
                            || processName.endsWith(":RunSoLibV2RayDaemon")) {
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return false;
    }
}
