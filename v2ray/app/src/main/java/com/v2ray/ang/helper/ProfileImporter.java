package com.v2ray.ang.helper;

import android.content.Context;
import android.util.Log;

import com.v2ray.ang.AppConfig;
import com.v2ray.ang.handler.AngConfigManager;
import com.v2ray.ang.handler.MmkvManager;
import com.v2ray.ang.dto.ProfileItem;
import com.v2ray.ang.fmt.VlessFmt;

import org.jetbrains.annotations.Nullable;

/**
 * Helper class for importing V2Ray profiles from external sources.
 * This class provides Java-compatible static methods for profile import.
 */
public class ProfileImporter {
    
    private static final String TAG = "ProfileImporter";
    
    /**
     * Imports a V2Ray profile from a vless:// URL.
     * 
     * @param context The Android context
     * @param vlessUrl The vless:// URL to import
     * @return The GUID of the imported profile, or null if import failed
     */
    @Nullable
    public static String importVlessProfile(Context context, String vlessUrl) {
        try {
            if (vlessUrl == null || vlessUrl.isEmpty()) {
                Log.e(TAG, "Empty vless URL");
                return null;
            }
            
            // Parse the vless URL into a ProfileItem
            ProfileItem profileItem = VlessFmt.parse(vlessUrl);
            if (profileItem == null) {
                Log.e(TAG, "Failed to parse vless URL");
                return null;
            }
            
            // Set a default subscription ID (empty for standalone profiles)
            profileItem.subscriptionId = "";
            
            // Encode and save the profile
            String guid = MmkvManager.encodeServerConfig("", profileItem);
            
            Log.i(TAG, "Successfully imported profile with GUID: " + guid);
            return guid;
            
        } catch (Exception e) {
            Log.e(TAG, "Error importing vless profile", e);
            return null;
        }
    }
    
    /**
     * Imports a V2Ray profile from any supported protocol URL.
     *
     * @param context The Android context
     * @param profileUrl The profile URL (vless://, vmess://, trojan://, etc.)
     * @return The GUID of the imported profile, or null if import failed
     */
    @Nullable
    public static String importProfile(Context context, String profileUrl) {
        try {
            if (profileUrl == null || profileUrl.isEmpty()) {
                Log.e(TAG, "Empty profile URL");
                return null;
            }

            // Use the batch import method from AngConfigManager
            // This handles all protocol types automatically
            android.util.Pair<Integer, Integer> result =
                AngConfigManager.importBatchConfigJava(profileUrl, "", false);

            int count = result.first;
            if (count > 0) {
                Log.i(TAG, "Successfully imported " + count + " profile(s)");
                // Return the first/newest server from the list
                java.util.List<String> serverList = MmkvManager.decodeServerList();
                if (!serverList.isEmpty()) {
                    return serverList.get(0);
                }
            } else {
                Log.e(TAG, "Failed to import profile");
            }

            return null;

        } catch (Exception e) {
            Log.e(TAG, "Error importing profile", e);
            return null;
        }
    }
    
    /**
     * Gets the remarks (name) of a profile by its GUID.
     * 
     * @param guid The profile GUID
     * @return The profile remarks, or null if not found
     */
    @Nullable
    public static String getProfileRemarks(String guid) {
        try {
            ProfileItem profile = MmkvManager.decodeServerConfig(guid);
            if (profile != null) {
                return profile.remarks;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting profile remarks", e);
        }
        return null;
    }
    
    /**
     * Sets a profile as the currently selected/active profile.
     * 
     * @param guid The profile GUID to select
     * @return true if successful, false otherwise
     */
    public static boolean selectProfile(String guid) {
        try {
            MmkvManager.setSelectServer(guid);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error selecting profile", e);
            return false;
        }
    }
}
