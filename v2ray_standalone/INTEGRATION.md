# V2RayNG Standalone - Integration Guide

## Overview

This document describes the complete integration between NekoX and v2ray_standalone module for automatic key management.

## Architecture Summary

```
NekoX App (TMessagesProj_App)          v2ray_standalone App
├── ConfigSubscrBotRequest.java        ├── V2RayNGReceiver.kt
├── V2RayNekoBridge.java                ├── ProfileImporter.kt
└── SharedConfig (MMKV)                 └── V2RayConfigManager.kt
         │                                       │
         └───────── IPC (Broadcast) ────────────┘
```

## Complete Workflow

### Step 1: User Configuration (NekoX)

**File**: `TMessagesProj/src/main/java/org/telegram/ui/ConfigSubscrBotRequest.java`

1. User navigates to: Settings → Passcode → V2Ray Subscription Settings
2. User selects bot by tapping "Подписка 1"
3. User fills request commands in fields:
   - `request1Row` → saved to `SharedConfig.v2rayRequest1`
   - `request2Row` → saved to `SharedConfig.v2rayRequest11`
   - `request3Row` → saved to `SharedConfig.v2rayRequest12`
   - `request4Row` → saved to `SharedConfig.v2rayRequest13`

### Step 2: Request Key from Bot

**User Action**: Taps "Получить ключ по запросу X" button

**Code Flow**:
```java
onUpdateKeyClicked(int index) {
    String requestText = request1; // or request2, request3, request4
    sendMessageToBot(botUsername, requestText, index);
    // Sets state to WAITING, starts 60-second timeout
}
```

### Step 3: Bot Response Processing

**Trigger**: `didReceivedNotification(NotificationCenter.didReceiveNewMessages)`

**Code Flow** (ConfigSubscrBotRequest.java:474-540):
```java
1. Check if message is from expected bot (waitingForBotDialogId)
2. Parse message for vless://... profile using parseVlessProfile()
3. Validate with isV2RayKey() - checks prefixes:
   - vless://
   - vmess://
   - ss://
   - trojan://
   - shadowsocks://
4. Save to SharedConfig:
   - index=1  → SharedConfig.v2rayKey1
   - index=11 → SharedConfig.v2rayKey11
   - index=12 → SharedConfig.v2rayKey12
   - index=13 → SharedConfig.v2rayKey13
5. Copy to clipboard: AndroidUtilities.addToClipboard(vlessProfile)
6. Call sendToV2Ray(vlessProfile) ← KEY INTEGRATION POINT
```

### Step 4: Send Key to V2Ray

**File**: `TMessagesProj/src/main/java/org/telegram/ui/ConfigSubscrBotRequest.java:473-530`

**Implementation**:
```java
private void sendToV2Ray(String key) {
    // Get V2RayNekoBridge instance
    V2RayNekoBridge v2rayBridge = V2RayNekoBridge.getInstance(getParentActivity());
    v2rayBridge.initialize();

    // Set response listener for callbacks
    v2rayBridge.setOnV2RayResponseListener(new OnV2RayResponseListener() {
        onImportResult(imported, working, total) {
            Toast: "V2Ray: Imported 1, 1/1 working"
        }
        onError(error), onNoV2Ray(), onNoAnswer() { ... }
    });

    // Send IPC command
    v2rayBridge.sendImportKeysCommand(key, "");
}
```

### Step 5: IPC Communication

**File**: `TMessagesProj/src/main/java/org/telegram/messenger/V2RayNekoBridge.java`

**sendImportKeysCommand() method**:
```java
Intent intent = new Intent("com.v2ray.ang.action.NEKOX_COMMAND");
intent.setPackage(context.getPackageName());
intent.putExtra("command", CMD_IMPORT_KEYS); // 1002
intent.putExtra("key1", key);
intent.putExtra("key2", "");
context.sendBroadcast(intent);
// Starts 12-second timeout for response
```

### Step 6: V2Ray Receiver Processing

**File**: `v2ray/app/src/main/java/com/v2ray/ang/receiver/V2RayNGReceiver.kt`

**Process**: `:RunSoLibV2RayDaemon` (separate process)

**onReceive() flow**:
```kotlin
1. Receive broadcast intent
2. Extract command = CMD_IMPORT_KEYS (1002)
3. Call handleEnhancedCommand()
4. Call handleImportKeys(key1, key2)
   a. ProfileImporter.importProfile(key1)
      - Parses vless://... URL
      - Creates ServerConfig object
      - Validates server/port/encryption
   b. V2RayConfigManager.addProfile(serverConfig)
      - Adds to MMKV storage
      - Assigns unique GUID
   c. Return imported count
5. Call handleTestKeys() - optional ping test
6. Build response intent
7. sendBroadcast(responseIntent)
```

### Step 7: Response Callback

**File**: `TMessagesProj/src/main/java/org/telegram/messenger/V2RayNekoBridge.java`

**responseReceiver.onReceive()**:
```java
handleResponse(Intent intent) {
    int responseType = intent.getIntExtra("command", 0);

    if (responseType == RESP_IMPORT_RESULT) { // 2002
        int imported = intent.getIntExtra("imported_count", 0);
        int working = intent.getIntExtra("working_count", 0);
        int total = intent.getIntExtra("total_count", 0);

        responseListener.onImportResult(imported, working, total);
        // User sees toast: "V2Ray: Imported 1, 1/1 working"
    }
}
```

## IPC Protocol Constants

### Actions
```java
// Commands (NekoX → v2ray)
ACTION_V2RAY_COMMAND = "com.v2ray.ang.action.NEKOX_COMMAND"

// Responses (v2ray → NekoX)
ACTION_V2RAY_RESPONSE = "com.v2ray.ang.action.NEKOX_RESPONSE"
```

### Commands
```java
CMD_TEST_KEYS = 1001     // Test all stored keys
CMD_IMPORT_KEYS = 1002   // Import and test new keys
CMD_START_V2RAY = 1003   // Start VPN service
CMD_STOP_V2RAY = 1004    // Stop VPN service
```

### Responses
```java
RESP_TEST_RESULT = 2001    // Test completed
RESP_IMPORT_RESULT = 2002  // Import completed
RESP_NO_V2RAY = 2999       // V2Ray not available
RESP_NO_ANSWER = 2998      // Timeout (no response)
```

### Intent Extras
```java
EXTRA_COMMAND = "command"
EXTRA_KEY1 = "key1"
EXTRA_KEY2 = "key2"
EXTRA_IMPORTED_COUNT = "imported_count"
EXTRA_WORKING_COUNT = "working_count"
EXTRA_TOTAL_COUNT = "total_count"
EXTRA_ERROR = "error"
```

## Storage

### NekoX Side
**File**: `SharedConfig.java` (MMKV)
```java
SharedConfig.v2raySubscription1  // Bot username (@bot)
SharedConfig.v2rayRequest1       // Request command 1
SharedConfig.v2rayRequest11      // Request command 2
SharedConfig.v2rayRequest12      // Request command 3
SharedConfig.v2rayRequest13      // Request command 4
SharedConfig.v2rayKey1           // Received key 1
SharedConfig.v2rayKey11          // Received key 2
SharedConfig.v2rayKey12          // Received key 3
SharedConfig.v2rayKey13          // Received key 4
```

### V2Ray Side
**File**: `V2RayConfigManager.kt` (MMKV)
```kotlin
MMKV.defaultMMKV().encode("profile_list", profileList)
// Each profile has:
// - guid: unique identifier
// - serverConfig: vless/vmess/trojan config
// - subscriptionId: optional subscription link
```

## Testing Checklist

### Prerequisites
```bash
# Build and install both apps
./gradlew :TMessagesProj_App:installAfatDebug
./gradlew :v2ray_standalone:installDebug
```

### Test Steps

1. **Setup Bot**
   - Open NekoX → Settings → Passcode → V2Ray Subscription Settings
   - Tap "Подписка 1" → Select bot from contacts
   - Verify bot username appears (e.g., "@myv2raybot")

2. **Configure Request**
   - Tap "Запрос1 ключа" field
   - Enter command: `getkey1` or `start`
   - Field should show command text

3. **Request Key**
   - Tap "Получить ключ по запросу 1"
   - Status changes to "ожидаю" (red)
   - Wait for bot response (max 60 seconds)

4. **Verify Reception**
   - Check toast: "V2RayProfileReceived"
   - "Получить ключ" field shows vless://... URL
   - Check toast: "Sending key to V2Ray..."

5. **Verify Import**
   - Check toast: "V2Ray: Imported 1, 1/1 working"
   - Open v2ray_standalone app
   - Verify new profile in list

6. **Verify Connection**
   - In v2ray_standalone, tap profile
   - Tap "Test" button
   - Check ping result (should show latency in ms)

### Debug Logs

```bash
# Full IPC trace
adb logcat -s "ConfigSubscrBotRequest" -s "V2RayNekoBridge" -s "V2RayNGReceiver" -s "ProfileImporter"

# Key events only
adb logcat | grep -E "(sendToV2Ray|IMPORT_KEYS|onImportResult)"
```

## Troubleshooting

### No response from bot
- **Check**: Bot is online and responding
- **Check**: Telegram connection active
- **Fix**: Increase timeout in `onUpdateKeyClicked()` from 60s

### "V2Ray: No response (timeout)"
- **Check**: v2ray_standalone is installed
- **Check**: Package name matches (`com.v2ray.ang.debug` for debug builds)
- **Fix**: Reinstall v2ray_standalone

### Key not appearing in v2ray_standalone
- **Check**: Key format is valid (starts with vless://, vmess://, etc.)
- **Check**: ProfileImporter successfully parsed URL
- **Log**: `adb logcat -s "ProfileImporter"`

### IPC broadcast not received
- **Check**: Both apps have same base package (`com.v2ray.ang`)
- **Check**: V2RayNGReceiver is registered in manifest
- **Fix**: Verify `v2ray_standalone/src/main/AndroidManifest.xml` has:
  ```xml
  <receiver android:name=".receiver.V2RayNGReceiver" android:exported="false">
      <intent-filter>
          <action android:name="com.v2ray.ang.action.NEKOX_COMMAND" />
      </intent-filter>
  </receiver>
  ```

## Development Notes

### Adding New Request Fields

To add request5Row, request6Row, etc.:

1. Update `ConfigSubscrBotRequest.java`:
   ```java
   private int request5Row;
   private int updateKey5Row;
   private String request5 = SharedConfig.v2rayRequest14;
   private String key5 = SharedConfig.v2rayKey14;
   ```

2. Add to `SharedConfig.java`:
   ```java
   public static String v2rayRequest14 = "";
   public static String v2rayKey14 = "";
   ```

3. Update `updateRows()` and `ListAdapter`

4. Add case in `onUpdateKeyClicked()`

### Modifying Timeout Durations

**Bot response timeout** (ConfigSubscrBotRequest.java:421):
```java
handler.postDelayed(timeoutRunnables[index], 60000); // Change 60000ms
```

**IPC response timeout** (V2RayNekoBridge.java:215):
```java
mainHandler.postDelayed(pendingTimeoutRunnable, 12000); // Change 12000ms
```

## See Also

- `TgXRayAPIspecification.md` - Complete IPC protocol specification
- `v2ray_standalone/README.md` - Standalone app documentation
- `CLAUDE.md` - Project architecture overview
