# NekoX ↔ v2rayNG Inter-Process Communication (IPC) API Specification



## Common Overview

This document describes the complete API for inter-process communication between the **NekoX** Telegram messenger application and the **v2rayNG** standalone VPN/proxy application. The integration allows NekoX to:

- Import V2Ray profiles (vless://, vmess://, trojan://) into v2rayNG
- Test profile connectivity and receive delay measurements
- Start/stop v2rayNG service remotely
- Detect port conflicts with other VPN applications
- Receive real-time feedback on profile status

### Architecture Summary

```
┌─────────────────────────────────────────────────────────────┐
│                     NekoX Application                        │
│  Package: org.telegram.messenger                            │
│  Process: :main (default)                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ V2RayNekoBridge.java                                  │   │
│  │ - Broadcast sender (commands)                         │   │
│  │ - Response receiver (callbacks)                       │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                            │
                            │ Android Broadcast Intents
                            │ (Package-scoped, not exported)
                            ▼
┌─────────────────────────────────────────────────────────────┐
│              v2rayNG Application                             │
│  Package: com.v2ray.ang                                     │
│  Process: :RunSoLibV2RayDaemon (separate process)           │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ V2RayNGReceiver.kt                                    │   │
│  │ - Broadcast receiver (command handler)                │   │
│  │ - Response sender                                     │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ MMKV Storage (Shared)                                 │   │
│  │ - ProfileItem list (server configs)                   │   │
│  │ - Selected server GUID                                │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 1. Communication Mechanism

### 1.1 Transport Layer

- **Protocol**: Android Broadcast Intents
- **Scope**: Package-scoped (not exported to external apps)
- **Process**: v2rayNG runs in separate process `:RunSoLibV2RayDaemon`
- **Security**: `Intent.setPackage()` ensures same-application communication

### 1.2 Message Format

All messages use `android.content.Intent` with extras:

```kotlin
// Command message (NekoX → v2rayNG)
Intent(ACTION_V2RAY_COMMAND).apply {
    setPackage(context.packageName)
    putExtra(EXTRA_COMMAND, commandCode)
    putExtra(EXTRA_KEY1, "vless://...")
    putExtra(EXTRA_KEY2, "vmess://...")
}

// Response message (v2rayNG → NekoX)
Intent(ACTION_V2RAY_RESPONSE).apply {
    setPackage(context.packageName)
    putExtra(EXTRA_COMMAND, responseCode)
    putExtra(EXTRA_WORKING_COUNT, 5)
    putExtra(EXTRA_TOTAL_COUNT, 10)
}
```

---

## 2. Command Protocol (NekoX → v2rayNG)

### 2.1 Broadcast Action

```kotlin
const val ACTION_NEKOX_COMMAND = "com.v2ray.ang.action.NEKOX_COMMAND"
const val ACTION_V2RAY_COMMAND = "com.v2ray.ang.action.NEKOX_COMMAND"
```

### 2.2 Command Codes

| Command | Code | Description | Required Extras |
|---------|------|-------------|-----------------|
| `CMD_TEST_KEYS` | 1001 | Test all stored profiles | None |
| `CMD_IMPORT_KEYS` | 1002 | Import keys and test | `EXTRA_KEY1`, `EXTRA_KEY2` |
| `CMD_START_V2RAY` | 1003 | Start v2rayNG service | None |
| `CMD_STOP_V2RAY` | 1004 | Stop v2rayNG service | None |

### 2.3 Command Details

#### 2.3.1 CMD_TEST_KEYS (1001)

**Purpose**: Test all stored profiles and return count of working keys.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_TEST_KEYS);
context.sendBroadcast(intent);
```

**v2rayNG Processing**:
1. Retrieve all profile GUIDs from MMKV: `MmkvManager.decodeServerList()`
2. For each profile:
   - Decode config: `MmkvManager.decodeServerConfig(guid)`
   - Extract server and port
   - Run TCP socket test: `SpeedtestManager.socketConnectTime(server, port)`
   - Count as "working" if delay > 0 and < 10000ms
3. Send response with counts

**Response**: `RESP_TEST_RESULT` (2001)

---

#### 2.3.2 CMD_IMPORT_KEYS (1002)

**Purpose**: Import one or two profile URLs and test all profiles.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_IMPORT_KEYS);
intent.putExtra(EXTRA_KEY1, "vless://uuid@host:port?...");
intent.putExtra(EXTRA_KEY2, "vmess://..."); // Optional
context.sendBroadcast(intent);
```

**Extras**:
- `EXTRA_KEY1` (String, required): First profile URL (vless/vmess/trojan)
- `EXTRA_KEY2` (String, optional): Second profile URL

**v2rayNG Processing**:
1. For each non-empty key:
   - Call `ProfileImporter.importProfile(context, keyUrl)`
   - Parse URL using `AngConfigManager.importBatchConfigJava()`
   - Save to MMKV with new GUID
2. Test all profiles (including newly imported)
3. Send response with import count and test results

**Response**: `RESP_IMPORT_RESULT` (2002)

---

#### 2.3.3 CMD_START_V2RAY (1003)

**Purpose**: Start v2rayNG VPN/proxy service.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_START_V2RAY);
context.sendBroadcast(intent);
```

**v2rayNG Processing**:
1. Check port conflicts: `PortChecker.getOccupiedPorts()`
2. If ports 10808 or 10853 are occupied:
   - Send `ACTION_RESPONSE_PORT_CONFLICT`
   - Do NOT start service
3. If no conflicts:
   - Call `V2RayServiceManager.startVServiceFromToggle(context)`
   - Start V2Ray core loop
   - Send `ACTION_RESPONSE_STARTED`

**Responses**:
- Success: `ACTION_RESPONSE_STARTED`
- Port conflict: `ACTION_RESPONSE_PORT_CONFLICT`
- Error: `ACTION_RESPONSE_STARTED` with `EXTRA_ERROR_MESSAGE`

---

#### 2.3.4 CMD_STOP_V2RAY (1004)

**Purpose**: Stop v2rayNG service.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_STOP_V2RAY);
context.sendBroadcast(intent);
```

**v2rayNG Processing**:
1. Call `V2RayServiceManager.stopVService(context)`
2. Send `ACTION_RESPONSE_STOPPED`

**Response**: `ACTION_RESPONSE_STOPPED`

---

## 3. Response Protocol (v2rayNG → NekoX)

### 3.1 Broadcast Action

```kotlin
const val ACTION_V2RAY_RESPONSE = "com.v2ray.ang.action.NEKOX_RESPONSE"
```

### 3.2 Response Codes

| Response | Code | Description | Returned Extras |
|----------|------|-------------|-----------------|
| `RESP_TEST_RESULT` | 2001 | Test results | `EXTRA_WORKING_COUNT`, `EXTRA_TOTAL_COUNT` |
| `RESP_IMPORT_RESULT` | 2002 | Import + test results | `EXTRA_IMPORTED_COUNT`, `EXTRA_WORKING_COUNT`, `EXTRA_TOTAL_COUNT` |
| `RESP_NO_V2RAY` | 2999 | v2rayNG not available | None |
| `RESP_NO_ANSWER` | 2998 | Timeout/no response | None |

### 3.3 Response Details

#### 3.3.1 RESP_TEST_RESULT (2001)

**Triggered by**: `CMD_TEST_KEYS`

**Extras**:
```java
intent.putExtra(EXTRA_COMMAND, V2RayNGReceiver.RESP_TEST_RESULT);
intent.putExtra(EXTRA_WORKING_COUNT, 5);  // Working profiles
intent.putExtra(EXTRA_TOTAL_COUNT, 10);   // Total profiles
```

**NekoX Handling**:
```java
// In V2RayNekoBridge.handleResponse()
case RESP_TEST_RESULT:
    int working = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
    int total = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0);
    if (responseListener != null) {
        responseListener.onTestResult(working, total);
    }
    break;
```

**UI Display**: `"working 5 fr 10"`

---

#### 3.3.2 RESP_IMPORT_RESULT (2002)

**Triggered by**: `CMD_IMPORT_KEYS`

**Extras**:
```java
intent.putExtra(EXTRA_COMMAND, V2RayNGReceiver.RESP_IMPORT_RESULT);
intent.putExtra(EXTRA_IMPORTED_COUNT, 2);   // Newly imported
intent.putExtra(EXTRA_WORKING_COUNT, 7);    // Total working
intent.putExtra(EXTRA_TOTAL_COUNT, 12);     // Total profiles
```

**NekoX Handling**:
```java
case RESP_IMPORT_RESULT:
    int imported = intent.getIntExtra(EXTRA_IMPORTED_COUNT, 0);
    int working = intent.getIntExtra(EXTRA_WORKING_COUNT, 0);
    int total = intent.getIntExtra(EXTRA_TOTAL_COUNT, 0);
    if (responseListener != null) {
        responseListener.onImportResult(imported, working, total);
    }
    break;
```

**UI Display**: `"Fr 2 imported 7 work"`

---

#### 3.3.3 RESP_NO_V2RAY (2999)

**Triggered by**: v2rayNG not available/not installed

**NekoX Handling**:
```java
case RESP_NO_V2RAY:
    if (responseListener != null) {
        responseListener.onNoV2Ray();
    }
    break;
```

**UI Display**: `"No V2Ray"` (localized: `R.string.V2RayNoV2Ray`)

---

#### 3.3.4 RESP_NO_ANSWER (2998)

**Triggered by**: Timeout waiting for response (8-12 seconds)

**NekoX Handling**:
```java
// Timeout scheduled via Handler.postDelayed()
pendingTimeoutRunnable = () -> {
    if (responseListener != null) {
        responseListener.onNoAnswer();
    }
};
mainHandler.postDelayed(pendingTimeoutRunnable, 8000);
```

**UI Display**: `"V2Ray No Answer"` (localized: `R.string.V2RayNoAnswer`)

---

## 4. Legacy Broadcast API (v2rayNG Helper)

### 4.1 Start v2rayNG

**Action**: `ACTION_NEKOX_START_V2RAY`

```kotlin
const val ACTION_NEKOX_START_V2RAY = "com.v2ray.ang.action.NEKOX_START"
```

**Response**: `ACTION_RESPONSE_STARTED`

---

### 4.2 Stop v2rayNG

**Action**: `ACTION_NEKOX_STOP_V2RAY`

```kotlin
const val ACTION_NEKOX_STOP_V2RAY = "com.v2ray.ang.action.NEKOX_STOP"
```

**Response**: `ACTION_RESPONSE_STOPPED`

---

### 4.3 Import Profile

**Action**: `ACTION_NEKOX_IMPORT_PROFILE`

```kotlin
const val ACTION_NEKOX_IMPORT_PROFILE = "com.v2ray.ang.action.NEKOX_IMPORT_PROFILE"
const val EXTRA_PROFILE_URL = "profile_url"
const val EXTRA_PROFILE_REMARKS = "profile_remarks"
```

**Request**:
```java
Intent intent = new Intent(ACTION_NEKOX_IMPORT_PROFILE);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_PROFILE_URL, "vless://...");
intent.putExtra(EXTRA_PROFILE_REMARKS, "My Profile");
context.sendBroadcast(intent);
```

**Response**: `ACTION_RESPONSE_IMPORT_SUCCESS` or `ACTION_RESPONSE_IMPORT_FAILURE`

---

### 4.4 Test Profile

**Action**: `ACTION_NEKOX_TEST_PROFILE`

```kotlin
const val ACTION_NEKOX_TEST_PROFILE = "com.v2ray.ang.action.NEKOX_TEST_PROFILE"
const val EXTRA_TEST_GUID = "test_guid"
```

**Request**:
```java
Intent intent = new Intent(ACTION_NEKOX_TEST_PROFILE);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_TEST_GUID, "profile-guid-here");
context.sendBroadcast(intent);
```

**Response**: `ACTION_RESPONSE_TEST_RESULT`

**Extras**:
- `EXTRA_TEST_GUID`: Profile GUID
- `EXTRA_TEST_DELAY`: Delay in milliseconds (-1 if failed)
- `EXTRA_TEST_RESULT`: "OK" or "Failed"

---

### 4.5 Test All Profiles

**Action**: `ACTION_NEKOX_TEST_ALL_PROFILES`

```kotlin
const val ACTION_NEKOX_TEST_ALL_PROFILES = "com.v2ray.ang.action.NEKOX_TEST_ALL"
```

**Response**: `ACTION_RESPONSE_TEST_RESULT`

**Extras**:
- `results`: JSON array of test results

**JSON Format**:
```json
[
  {
    "test_guid": "abc123",
    "test_remarks": "Profile 1",
    "test_delay": 250,
    "test_result": "OK"
  },
  {
    "test_guid": "def456",
    "test_remarks": "Profile 2",
    "test_delay": -1,
    "test_result": "Failed"
  }
]
```

---

### 4.6 Port Conflict Response

**Action**: `ACTION_RESPONSE_PORT_CONFLICT`

**Triggered by**: Port conflict detected in `handleStartV2Ray()`

**Extras**:
- `EXTRA_ERROR_MESSAGE`: "Ports are already in use by another application"
- `EXTRA_OCCUPIED_PORTS`: JSON array of occupied ports

**JSON Format**:
```json
[10808, 10853]
```

**NekoX Handling**:
```java
// Show dialog: "Ports 10808, 10853 are in use by another VPN app"
```

---

## 5. Source Code Reference

### 5.1 NekoX Side (Java)

#### V2RayNekoBridge.java

**Location**: `TMessagesProj/src/main/java/org/telegram/messenger/V2RayNekoBridge.java`

**Key Methods**:
```java
// Singleton instance
public static V2RayNekoBridge getInstance(Context context)

// Initialize (register broadcast receiver)
public void initialize()

// Send commands
public void sendTestKeysCommand()
public void sendImportKeysCommand(String key1, String key2)
public boolean isV2RayAvailable()

// Callbacks
public void setOnV2RayResponseListener(OnV2RayResponseListener listener)

// Cleanup
public void release()
```

**Callback Interface**:
```java
public interface OnV2RayResponseListener {
    void onTestResult(int working, int total);
    void onImportResult(int imported, int working, int total);
    void onError(String error);
    void onNoV2Ray();
    void onNoAnswer();
}
```

**Usage Example**:
```java
// In V2rayConfigFragment.java
v2rayBridge = V2RayNekoBridge.getInstance(getParentActivity());
v2rayBridge.initialize();
v2rayBridge.setOnV2RayResponseListener(new V2RayNekoBridge.OnV2RayResponseListener() {
    @Override
    public void onTestResult(int working, int total) {
        updateV2RayAnswer("working " + working + " fr " + total);
    }

    @Override
    public void onImportResult(int imported, int working, int total) {
        updateV2RayAnswer("Fr " + imported + " imported " + working + " work");
    }

    @Override
    public void onError(String error) {
        updateV2RayAnswer("Error: " + error);
    }

    @Override
    public void onNoV2Ray() {
        updateV2RayAnswer(LocaleController.getString("V2RayNoV2Ray", R.string.V2RayNoV2Ray));
    }

    @Override
    public void onNoAnswer() {
        updateV2RayAnswer(LocaleController.getString("V2RayNoAnswer", R.string.V2RayNoAnswer));
    }
});

// Send command
v2rayBridge.sendTestKeysCommand();
```

---

### 5.2 v2rayNG Side (Kotlin)

#### V2RayNGReceiver.kt

**Location**: `v2ray/app/src/main/java/com/v2ray/ang/receiver/V2RayNGReceiver.kt`

**Key Methods**:
```kotlin
override fun onReceive(ctx: Context?, intent: Intent?)

// Command handlers
private fun handleTestKeysCommand(context: Context)
private fun handleImportKeysCommand(context: Context, key1: String?, key2: String?)
private fun handleStartV2Ray(context: Context)
private fun handleStopV2Ray(context: Context)

// Response senders
private fun sendCommandResponse(context: Context, command: Int, data: Map<String, Any?>?)
private fun sendResponse(context: Context, action: String, data: Map<String, Any?>?)
```

**Manifest Registration**:
```xml
<receiver
    android:name=".receiver.V2RayNGReceiver"
    android:exported="false"
    android:process=":RunSoLibV2RayDaemon">
    <intent-filter>
        <action android:name="com.v2ray.ang.action.NEKOX_COMMAND" />
        <action android:name="com.v2ray.ang.action.NEKOX_START" />
        <action android:name="com.v2ray.ang.action.NEKOX_STOP" />
        <action android:name="com.v2ray.ang.action.NEKOX_IMPORT_PROFILE" />
        <action android:name="com.v2ray.ang.action.NEKOX_TEST_PROFILE" />
        <action android:name="com.v2ray.ang.action.NEKOX_TEST_ALL" />
    </intent-filter>
</receiver>
```

---

#### ProfileImporter.java

**Location**: `v2ray/app/src/main/java/com/v2ray/ang/helper/ProfileImporter.java`

**Key Methods**:
```java
// Import any protocol (vless/vmess/trojan)
@Nullable
public static String importProfile(Context context, String profileUrl)

// Import vless specifically
@Nullable
public static String importVlessProfile(Context context, String vlessUrl)

// Get profile info
@Nullable
public static String getProfileRemarks(String guid)

// Select active profile
public static boolean selectProfile(String guid)
```

**Import Flow**:
```java
// 1. Parse URL
ProfileItem profileItem = VlessFmt.parse(vlessUrl);

// 2. Set subscription ID (empty for standalone)
profileItem.subscriptionId = "";

// 3. Save to MMKV
String guid = MmkvManager.encodeServerConfig("", profileItem);

// 4. Return GUID
return guid;
```

---

#### SpeedtestManager.kt (Delay Testing)

**Location**: `v2ray/app/src/main/java/com/v2ray/ang/handler/SpeedtestManager.kt`

**Key Method**:
```kotlin
fun socketConnectTime(server: String, port: Int): Long {
    // TCP socket connection test
    // Returns delay in milliseconds, -1 if failed
}
```

**Test Criteria**:
- Working: `delay > 0 && delay < 10000` (under 10 seconds)
- Failed: `delay <= 0` or `delay >= 10000`

---

#### PortChecker.kt

**Location**: `v2ray/app/src/main/java/com/v2ray/ang/util/PortChecker.kt`

**Key Methods**:
```kotlin
object PortChecker {
    fun isPortAvailable(port: Int): Boolean
    fun areV2rayPortsAvailable(socksPort: Int, dnsPort: Int): Boolean
    fun getOccupiedPorts(socksPort: Int, dnsPort: Int): List<Int>
}
```

**Default Ports**:
- SOCKS Proxy: `10808`
- Local DNS: `10853`

---

## 6. Data Storage (MMKV)

### 6.1 Profile Storage

**Manager**: `MmkvManager.kt`

**Key Methods**:
```kotlin
// Get all profile GUIDs
fun decodeServerList(): List<String>

// Get profile config
fun decodeServerConfig(guid: String): ProfileItem?

// Save profile
fun encodeServerConfig(guid: String, config: ProfileItem): String

// Set active profile
fun setSelectServer(guid: String)
```

### 6.2 ProfileItem Structure

```kotlin
data class ProfileItem(
    var configType: EConfigType,      // VLESS, VMESS, TROJAN, etc.
    var remarks: String,               // Profile name
    var server: String,                // Server hostname/IP
    var serverPort: String,            // Server port
    var id: String,                    // UUID (for VLESS/VMESS)
    var alterId: Int?,                 // Alter ID (for VMESS)
    var security: String,              // Security type
    var network: String,               // Transport (tcp/ws/grpc/h2)
    var headerType: String?,           // Header type
    var host: String?,                 // Host header
    var path: String?,                 // Path/URI
    var streamSecurity: String?,       // TLS/REALITY
    var sni: String?,                  // SNI
    var fingerprint: String?,          // TLS fingerprint
    var alpn: String?,                 // ALPN
    var flow: String?                  // Flow (for VLESS REALITY)
)
```

---

## 7. Complete Communication Flows

### 7.1 Key Import and Test Flow

```
┌──────────────┐                              ┌──────────────┐
│    NekoX     │                              │   v2rayNG    │
│              │                              │              │
│ 1. User      │                              │              │
│    clicks    │                              │              │
│    "SEND     │                              │              │
│    KEYS"     │                              │              │
│              │                              │              │
│ 2. Check     │                              │              │
│    keys      │                              │              │
│    in fields │                              │              │
│              │                              │              │
│ 3. Send      │                              │              │
│    CMD_TEST  │─────────────────────────────▶│              │
│    KEYS      │   Broadcast: ACTION_NEKOX_   │              │
│              │           COMMAND            │              │
│              │                              │              │
│              │                              │ 4. Get all   │
│              │                              │    profiles  │
│              │                              │              │
│              │                              │ 5. Test each │
│              │                              │    (TCP ping)│
│              │                              │              │
│ 6. Receive   │◀─────────────────────────────│ 6. Send      │
│    response  │   Broadcast: ACTION_NEKOX_   │    response  │
│              │           RESPONSE           │              │
│              │                              │              │
│ 7. Display:  │                              │              │
│    "working  │                              │              │
│    2 fr 10"  │                              │              │
│              │                              │              │
│ 8. Wait 6s   │                              │              │
│              │                              │              │
│ 9. Send      │                              │              │
│    CMD_      │─────────────────────────────▶│              │
│    IMPORT    │   Broadcast with key1, key2  │              │
│    KEYS      │                              │              │
│              │                              │ 10. Import   │
│              │                              │     each key │
│              │                              │              │
│              │                              │ 11. Test all │
│              │                              │     profiles │
│              │                              │              │
│ 12. Receive  │◀─────────────────────────────│ 12. Send     │
│     response │   Broadcast: ACTION_NEKOX_   │    response  │
│              │           RESPONSE           │              │
│              │                              │              │
│ 13. Display: │                              │              │
│     "Fr 2    │                              │              │
│     imported │                              │              │
│     5 work"  │                              │              │
└──────────────┘                              └──────────────┘
```

---

### 7.2 Profile Import Flow (Legacy API)

```
┌──────────────┐                              ┌──────────────┐
│    NekoX     │                              │   v2rayNG    │
│              │                              │              │
│ 1. User      │                              │              │
│    pastes    │                              │              │
│    vless://  │                              │              │
│    URL       │                              │              │
│              │                              │              │
│ 2. Call      │                              │              │
│    V2RayNG   │                              │              │
│    Helper    │                              │              │
│              │                              │              │
│ 3. Send      │                              │              │
│    IMPORT    │─────────────────────────────▶│              │
│    broadcast │   ACTION_NEKOX_IMPORT_       │              │
│              │           PROFILE            │              │
│              │                              │              │
│              │                              │ 4. Parse URL │
│              │                              │    (VlessFmt)│
│              │                              │              │
│              │                              │ 5. Create    │
│              │                              │    ProfileItem│
│              │                              │              │
│              │                              │ 6. Save to   │
│              │                              │    MMKV      │
│              │                              │              │
│              │                              │ 7. Return    │
│              │                              │    GUID      │
│              │                              │              │
│ 8. Receive   │◀─────────────────────────────│ 8. Send      │
│    success   │   ACTION_RESPONSE_IMPORT_    │    broadcast │
│              │           SUCCESS            │              │
│              │   + EXTRA_TEST_GUID          │              │
│              │                              │              │
│ 9. Store     │                              │              │
│    GUID,     │                              │              │
│    update UI │                              │              │
└──────────────┘                              └──────────────┘
```

---

### 7.3 Port Conflict Detection Flow

```
┌──────────────┐                              ┌──────────────┐
│    NekoX     │                              │   v2rayNG    │
│              │                              │              │
│ 1. User      │                              │              │
│    tries to  │                              │              │
│    start     │                              │              │
│    v2rayNG   │                              │              │
│              │                              │              │
│ 2. Send      │                              │              │
│    CMD_START │─────────────────────────────▶│              │
│              │                              │              │
│              │                              │ 3. Check     │
│              │                              │    ports:    │
│              │                              │    PortCheck-│
│              │                              │    er        │
│              │                              │              │
│              │                              │ 4. Ports     │
│              │                              │    occupied? │
│              │                              │    YES       │
│              │                              │              │
│              │                              │ 5. Send      │
│              │                              │    PORT_     │
│              │                              │    CONFLICT  │
│              │                              │              │
│ 6. Receive   │◀─────────────────────────────│              │
│    conflict  │   ACTION_RESPONSE_PORT_      │              │
│              │           CONFLICT           │              │
│              │   + occupied_ports: [10808,  │              │
│              │                  10853]      │              │
│              │                              │              │
│ 7. Show      │                              │              │
│    dialog:   │                              │              │
│    "Ports    │                              │              │
│    10808,    │                              │              │
│    10853 are │                              │              │
│    in use"   │                              │              │
│              │                              │              │
│ 8. User can  │                              │              │
│    close     │                              │              │
│    other VPN │                              │              │
│    or change │                              │              │
│    v2rayNG   │                              │              │
│    ports     │                              │              │
└──────────────┘                              └──────────────┘
```

---

## 8. Error Handling

### 8.1 Timeout Handling

**NekoX Side**:
```java
// Schedule timeout
pendingTimeoutRunnable = () -> {
    if (responseListener != null) {
        responseListener.onNoAnswer();
    }
    pendingTimeoutRunnable = null;
};

// Test keys: 8 second timeout
mainHandler.postDelayed(pendingTimeoutRunnable, 8000);

// Import keys: 12 second timeout (import + test takes longer)
mainHandler.postDelayed(pendingTimeoutRunnable, 12000);
```

**Cancellation on Response**:
```java
private void handleResponse(Intent intent) {
    // Cancel any pending timeout
    if (pendingTimeoutRunnable != null) {
        mainHandler.removeCallbacks(pendingTimeoutRunnable);
        pendingTimeoutRunnable = null;
    }
    // ... process response
}
```

---

### 8.2 Error Responses

| Error Scenario | Response | UI Message |
|----------------|----------|------------|
| v2rayNG not installed | `RESP_NO_V2RAY` | "No V2Ray" |
| v2rayNG not responding | Timeout → `onNoAnswer()` | "V2Ray No Answer" |
| Empty profile URL | `ACTION_RESPONSE_IMPORT_FAILURE` | "Profile URL is empty" |
| Invalid profile format | Import returns null | "Failed to import profile" |
| Port conflict | `ACTION_RESPONSE_PORT_CONFLICT` | "Ports are in use" |
| Profile not found (test) | `ACTION_RESPONSE_TEST_RESULT` + error | "Profile not found" |

---

### 8.3 Exception Handling

**v2rayNG Side**:
```kotlin
try {
    // Operation
} catch (e: Exception) {
    Log.e(TAG, "Error description", e)
    sendCommandResponse(
        context,
        RESPONSE_CODE,
        mapOf(EXTRA_ERROR_MESSAGE to e.message)
    )
}
```

**NekoX Side**:
```java
try {
    context.sendBroadcast(intent);
} catch (Exception e) {
    Log.e(TAG, "Error sending command", e);
    if (responseListener != null) {
        responseListener.onError(e.getMessage());
    }
}
```

---

## 9. Testing and Debugging

### 9.1 LogCat Tags

```bash
# Monitor all IPC communication
adb logcat -s "V2RayNekoBridge*" -s "V2RayNGReceiver*" -s "ProfileImporter*"

# Monitor v2rayNG service
adb logcat -s "V2RayServiceManager*" -s "SpeedtestManager*"

# Monitor MMKV storage
adb logcat -s "MmkvManager*"
```

### 9.2 Expected Log Output

**Test Keys Command**:
```
D/V2RayNekoBridge: Sent TEST_KEYS command to v2rayNG
D/V2RayNGReceiver: V2RayNGReceiver received action: com.v2ray.ang.action.NEKOX_COMMAND
D/V2RayNGReceiver: Handling NEKOX_COMMAND: 1001
D/V2RayNGReceiver: Executing TEST_KEYS command
I/SpeedtestManager: Testing profile: abc123 (server:port)
I/SpeedtestManager: Delay: 250ms - OK
I/SpeedtestManager: Delay: -1ms - Failed
D/V2RayNGReceiver: Sending response: 2001
D/V2RayNekoBridge: Received response from v2rayNG: 2001
D/V2RayNekoBridge: Test result: working=2, total=10
```

**Import Keys Command**:
```
D/V2RayNekoBridge: Sent IMPORT_KEYS command to v2rayNG
D/V2RayNGReceiver: Executing IMPORT_KEYS command
I/ProfileImporter: Successfully imported profile with GUID: xyz789
I/SpeedtestManager: Testing all profiles...
D/V2RayNGReceiver: Sending response: 2002
D/V2RayNekoBridge: Import result: imported=2, working=5, total=12
```

### 9.3 Manual Test Procedure

1. **Setup**:
   - Install NekoX with v2rayNG module
   - Ensure v2rayNG has launcher icon

2. **Test Availability**:
   ```
   Open NekoX → Settings → V2Ray Settings
   Click "SEND KEYS TO V2RAY"
   Expected: "No V2Ray" if not running, or test starts
   ```

3. **Test Import**:
   ```
   Enter vless:// URL in "key fr.1" field
   Click "SEND KEYS TO V2RAY"
   Expected: "Fr 1 imported X work"
   ```

4. **Test Timeout**:
   ```
   Force stop v2rayNG app
   Click "SEND KEYS TO V2RAY"
   Expected: "V2Ray No Answer" after 8-12 seconds
   ```

---

## 10. Security Considerations

### 10.1 Broadcast Security

- **Package-scoped**: All intents use `intent.setPackage(context.getPackageName())`
- **Not exported**: Receiver has `android:exported="false"`
- **Same signature**: Both apps must be signed with same certificate

### 10.2 Data Privacy

- Profile URLs contain sensitive server information
- Communication is local (same device)
- No network transmission of keys via IPC

### 10.3 Process Isolation

- v2rayNG runs in separate process: `:RunSoLibV2RayDaemon`
- MMKV storage is shared via same package name
- Service runs independently from NekoX lifecycle

---

## 11. Version Compatibility

### 11.1 API Versions

| Feature | Minimum Version | Notes |
|---------|-----------------|-------|
| Basic import | 1.0 | Legacy API |
| Command protocol | 2.0 | CMD_TEST_KEYS, CMD_IMPORT_KEYS |
| Port conflict detection | 2.0 | ACTION_RESPONSE_PORT_CONFLICT |
| Timeout handling | 2.0 | RESP_NO_ANSWER |

### 11.2 Backward Compatibility

- Legacy API (ACTION_NEKOX_*) still supported
- New command protocol (CMD_*) recommended for new features
- Both APIs can coexist

---

## 12. File Locations Summary

### NekoX (TMessagesProj)

| File | Path | Purpose |
|------|------|---------|
| `V2RayNekoBridge.java` | `TMessagesProj/src/main/java/org/telegram/messenger/` | IPC bridge |
| `V2rayConfigFragment.java` | `TMessagesProj/src/main/java/org/telegram/ui/` | Settings UI |
| `V2RayBootstrap.java` | `TMessagesProj/src/main/java/org/telegram/messenger/` | Auto-start |
| `SharedConfig.java` | `TMessagesProj/src/main/java/org/telegram/messenger/` | Config storage |

### v2rayNG Module

| File | Path | Purpose |
|------|------|---------|
| `V2RayNGReceiver.kt` | `v2ray/app/src/main/java/com/v2ray/ang/receiver/` | Broadcast receiver |
| `ProfileImporter.java` | `v2ray/app/src/main/java/com/v2ray/ang/helper/` | Profile import |
| `V2RayServiceManager.kt` | `v2ray/app/src/main/java/com/v2ray/ang/service/` | Service control |
| `SpeedtestManager.kt` | `v2ray/app/src/main/java/com/v2ray/ang/handler/` | Delay testing |
| `PortChecker.kt` | `v2ray/app/src/main/java/com/v2ray/ang/util/` | Port conflict |
| `MmkvManager.kt` | `v2ray/app/src/main/java/com/v2ray/ang/handler/` | Storage |
| `AngConfigManager.kt` | `v2ray/app/src/main/java/com/v2ray/ang/handler/` | Config parsing |

---

## 13. Glossary

| Term | Definition |
|------|------------|
| **GUID** | Globally Unique Identifier for a profile in MMKV |
| **MMKV** | Memory-mapped key-value storage (Tencent) |
| **ProfileItem** | Data class representing a V2Ray server configuration |
| **vless://** | VLESS protocol URL format (V2Ray) |
| **vmess://** | VMess protocol URL format (V2Ray) |
| **IPC** | Inter-Process Communication |
| **Broadcast Receiver** | Android component for receiving system-wide messages |

---

## 14. Changelog

### Version 2.0 (Current)
- Added command protocol (CMD_TEST_KEYS, CMD_IMPORT_KEYS)
- Added timeout handling (RESP_NO_ANSWER)
- Added port conflict detection
- Improved error handling
- Enhanced logging

### Version 1.0 (Legacy)
- Basic profile import
- Start/stop service
- Test individual profiles

---

**Document Version**: 2.0
**Last Updated**: 2026-03-15
**Maintained by**: NekoX-IU2 Development Team

---




## 15. Refactoring Plan: V2Ray Settings UI Simplification (upd-ed 2026-03-15 16.38)
    Краткое содержание плана:

    Проблема
     - Дублирование строк "Подписка 2" (видно на фото)
     - Буфер обмена не записывается в request1Row после возврата из ChatActivity
     - Конфликт переменных choosingForSubscription и choosingForRequestField

    Решение
     1. Удалить Подписку 2 из V2rayConfigFragment
     2. Создать новый фрагмент ConfigSubscrBotRequest.java для настройки Подписки 1
     3. Оставить одно кликабельное поле "Подписка 1" в главном окне

    Архитектура

     1 V2rayConfigFragment (главный экран)
     2   └─→ Подписка 1 (тап) → ConfigSubscrBotRequest
     3        └─→ subscription (тап) → DialogsActivity (выбор бота)
     4        └─→ request1/11/12/13 (тап) → ChatActivity (копирование команды)
     5        └─→ updateKey1 (тап) → отправка сообщения боту

    7 шагов реализации:
     1. Создать ConfigSubscrBotRequest.java
     2. Модифицировать V2rayConfigFragment.java (удалить subscription 2 со всеми связанными полями  request21Row,    request22Row,        request23Row ,        updateKey2Row ,        key2Row ) 
     3. В ConfigSubscrBotRequest особое внимание обратить на логику автозаполнения из буфера после вызова ChatActivity из полей 
    request1Row ,
        request11Row 
        request12Row 
        request13Row 
ЭТИ Поля в ConfigSubscrBotRequest должны работать так:
 1. Если поле request1Row чем-то заполнено и НЕ заканчивается на ";", то поле доступно в режиме текстового редактирования вручную.
 2. Если поле request1Row пусто ИЛИ заканчивается на ";", то при тапе открывается окно ChatActivity переписки с ботом.
 3. При возврате из ChatActivity происходит автозаполнение поля из буфера обмена. Если поле заканчивалось на ";", новый текст добавляется в конец через пробел.
 4. Аналогичная логика применяется для всех полей запросов и ключей.
 5. Добавлены кнопки "Запросить ключ" для каждой секции, инициирующие отправку команды боту.
 6. Полученные от бота ключи автоматически записываются в соответствующие редактируемые поля "Update Key".
 7. После получения ключа вызывается функция `sendToV2Ray` (ToDo).
1.6. редактируемое поле request1Row должно находиться в 1й строке (на 1м вертикальном уровне ) с заголовком "Запрос 1", но правее его (не загораживая заголовок собой)


     4. Исправить Focus Change Listener
     5. Обновить SharedConfig.java
     6. Обновить strings.xml
     7. Исправить адаптер

    Критерии успеха:
    ✅ Нет дублирования строк
    ✅ Буфер обмена работает корректно
    ✅ Все поля запросов работают независимо
    ✅ Сборка проходит без ошибок

### 15.1 Problem Statement

**Current Issues:**
1. Duplicate "Подписка 2" rows appear after returning from ChatActivity
2. Clipboard text not being pasted to request1Row after returning from ChatActivity
3. Complex UI with two subscriptions causes confusion and bugs
4. Conflict between `choosingForSubscription` and `choosingForRequestField` variables

**Root Cause:**
- Variable reuse conflict in `didSelectDialogs()` and `onResume()`
- `choosingForSubscription` used for both subscription selection (1, 2) and request field tracking (-1, -11, etc.)
- Multiple subscription sections cause row duplication in RecyclerView adapter

---

### 15.2 Solution Overview

**Goal:** Simplify V2Ray settings UI by removing Subscription 2 and creating a dedicated settings screen for Subscription 1.

**New Architecture:**
```
┌─────────────────────────────────────────────────────────────┐
│              V2rayConfigFragment (Main)                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Подписка 1 (Clickable) ──────────────────────────▶  │   │
│  │  [Opens ConfigSubscrBotRequest]                      │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  SEND KEYS TO V2RAY (Button)                         │   │
│  │  Answer from V2Ray: ...                              │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│         ConfigSubscrBotRequest (New Screen)                 │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Subscription Field (@bot username)                  │   │
│  │    - Tap empty → DialogsActivity (select bot)        │   │
│  │    - Tap filled → Edit manually                      │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Request Fields Section:                             │   │
│  │    - Запрос 1 (request1)                             │   │
│  │      • Empty → ChatActivity (copy command)           │   │
│  │      • Filled → Edit manually                        │   │
│  │    - Запрос 1.1 (request11)                          │   │
│  │    - Запрос 1.2 (request12)                          │   │
│  │    - Запрос 1.3 (request13)                          │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Key Section:                                        │   │
│  │    - key fr.1 (key1) - Read-only, from bot           │   │
│  │    - Обновить ключ (Update Key Button)               │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

### 15.3 Implementation Steps

#### Step 1: Create ConfigSubscrBotRequest Fragment

**File:** `TMessagesProj/src/main/java/org/telegram/ui/ConfigSubscrBotRequest.java`

**Purpose:** Dedicated screen for managing Subscription 1 settings.

**Fields:**
| Field | Type | Behavior |
|-------|------|----------|
| `subscription1` | EditTextSettingsCell | Tap empty → DialogsActivity, Tap filled → Edit |
| `request1` | EditTextSettingsCell | Empty → ChatActivity, Filled → Edit |
| `request11` | EditTextSettingsCell | Same as request1 |
| `request12` | EditTextSettingsCell | Same as request1 |
| `request13` | EditTextSettingsCell | Same as request1 |
| `key1` | EditTextSettingsCell | Read-only (from bot response) |
| `updateKey1` | TextSettingsCell | Button to send request to bot |

**Key Methods:**
```java
public class ConfigSubscrBotRequest extends BaseFragment 
    implements NotificationCenter.NotificationCenterDelegate,
               DialogsActivity.DialogsActivityDelegate {
    
    // Row definitions
    private int subscriptionRow;
    private int request1Row;
    private int request11Row;
    private int request12Row;
    private int request13Row;
    private int key1Row;
    private int updateKey1Row;
    
    // State tracking
    private int choosingForSubscription;  // For DialogsActivity
    private int choosingForRequestField;  // For ChatActivity (-1, -11, -12, -13)
    
    @Override
    public View createView(Context context);
    @Override
    public void onResume();  // Handle clipboard paste from ChatActivity
    @Override
    public boolean didSelectDialogs(...);  // Handle bot selection
    private void sendMessageToBot(...);  // Send request commands
}
```

**Navigation:**
- From `V2rayConfigFragment.subscription1Row` → Present `ConfigSubscrBotRequest`
- From `subscriptionRow` (empty) → Present `DialogsActivity`
- From `request*Row` (empty) → Present `ChatActivity`
- From `updateKey1Row` → Send message to bot

---

#### Step 2: Modify V2rayConfigFragment

**File:** `TMessagesProj/src/main/java/org/telegram/ui/V2rayConfigFragment.java`

**Changes:**

**2.1 Remove Subscription 2 Fields:**
```java
// REMOVE these fields:
// - subscription2HeaderRow, subscription2Row
// - request2Row, request21Row, request22Row, request23Row
// - updateKey2Row, key2Row

// REMOVE from updateRows():
// subscription2HeaderRow = rowCount++;
// subscription2Row = rowCount++;
// request2Row = rowCount++;
// request21Row = rowCount++;
// request22Row = rowCount++;
// request23Row = rowCount++;
// updateKey2Row = rowCount++;
// key2Row = rowCount++;
```

**2.2 Simplify UI to Show Only:**
- `subscription1Row` (clickable, opens ConfigSubscrBotRequest)
- `v2raySendKeyBtn` (test/import keys)
- `v2rayAnswer` (response display)
- `lastProfileRow` (last received profile)

**2.3 Update onItemClick:**
```java
listView.setOnItemClickListener((view, position, x, y) -> {
    if (position == subscription1Row) {
        // Open ConfigSubscrBotRequest fragment
        presentFragment(new ConfigSubscrBotRequest());
    } else if (position == v2raySendKeyBtn) {
        onV2RaySendKeyClicked();
    }
});
```

**2.4 Remove Conflicting Variables:**
```java
// REMOVE: choosingForSubscription, choosingForRequestField
// These are now only in ConfigSubscrBotRequest
```

---

#### Step 3: Fix Clipboard Auto-Fill Logic

**In ConfigSubscrBotRequest.onResume():**

```java
@Override
public void onResume() {
    super.onResume();
    
    if (choosingForRequestField != 0) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip()) {
                ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                if (item != null && item.getText() != null) {
                    String clipboardText = item.getText().toString().trim();
                    
                    if (!TextUtils.isEmpty(clipboardText)) {
                        // Fill the correct field based on choosingForRequestField
                        if (choosingForRequestField == -1) {
                            if (TextUtils.isEmpty(SharedConfig.v2rayRequest1)) {
                                SharedConfig.v2rayRequest1 = clipboardText;
                                SharedConfig.saveConfig();
                            }
                        } else if (choosingForRequestField == -11) {
                            if (TextUtils.isEmpty(request11)) {
                                request11 = clipboardText;
                                SharedConfig.saveConfig();
                            }
                        }
                        // ... etc for -12, -13
                        
                        Toast.makeText(getContext(), "Text pasted", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        choosingForRequestField = 0; // Reset
    }
}
```

---

#### Step 4: Fix Focus Change Listener

**In ConfigSubscrBotRequest onBindViewHolder():**

```java
editCell.getTextView().setOnFocusChangeListener((v, hasFocus) -> {
    if (hasFocus && requestFieldManuallyTouched) {
        String requestText = "";
        if (focusPos == request1Row) {
            requestText = SharedConfig.v2rayRequest1;
        } else if (focusPos == request11Row) {
            requestText = request11;
        }
        // ... etc
        
        if (TextUtils.isEmpty(requestText)) {
            // Open ChatActivity to copy command
            choosingForRequestField = -1; // or -11, -12, -13
            openChatToViewMessages(SharedConfig.v2raySubscription1);
            AndroidUtilities.hideKeyboard(editCell.getTextView());
        }
    }
});
```

---

#### Step 5: Update SharedConfig

**File:** `TMessagesProj/src/main/java/org/telegram/messenger/SharedConfig.java`

**Keep existing fields:**
```java
public static String v2raySubscription1 = "";
public static String v2rayRequest1 = "";
public static String v2rayKey1 = "";
// Keep request11-13 as transient (not saved to config)
```

**Remove or deprecate:**
```java
// @Deprecated - No longer used
// public static String v2raySubscription2 = "";
// public static String v2rayRequest2 = "";
// public static String v2rayKey2 = "";
```

---

#### Step 6: Update String Resources

**File:** `TMessagesProj/src/main/res/values/strings.xml`

**Add new strings:**
```xml
<string name="V2RaySubscription1Settings">Subscription 1 Settings</string>
<string name="V2RayBotUsername">Bot Username</string>
<string name="V2RayRequestCommands">Request Commands</string>
<string name="V2RayReceivedKey">Received Key</string>
<string name="V2RayUpdateKey">Update Key</string>
<string name="V2RayTapToSelectBot">Tap to select bot</string>
<string name="V2RayTapToCopyCommand">Tap to copy command</string>
```

---

#### Step 7: Fix Row Count and Adapter

**In ConfigSubscrBotRequest.updateRows():**

```java
private void updateRows() {
    rowCount = 0;
    
    // Subscription section
    subscriptionRow = rowCount++;
    
    // Request section
    request1Row = rowCount++;
    request11Row = rowCount++;
    request12Row = rowCount++;
    request13Row = rowCount++;
    
    // Key section
    updateKey1Row = rowCount++;
    key1Row = rowCount++;
}
```

**In ListAdapter.getItemViewType():**

```java
@Override
public int getItemViewType(int position) {
    if (position == updateKey1Row) {
        return 3; // Button
    }
    if (position == key1Row) {
        return 2; // Read-only edit
    }
    if (position == subscriptionRow || 
        position == request1Row || position == request11Row || 
        position == request12Row || position == request13Row) {
        return 2; // Edit text
    }
    return 0; // Text cell
}
```

---

### 15.4 Testing Checklist

#### Test Case 1: Open Subscription Settings
- [ ] Tap on "Подписка 1" in main screen
- [ ] ConfigSubscrBotRequest opens
- [ ] All fields displayed correctly (no duplicates)

#### Test Case 2: Select Bot
- [ ] Tap on empty subscription field
- [ ] DialogsActivity opens
- [ ] Select a bot (e.g., @hlvpnbot)
- [ ] Return to ConfigSubscrBotRequest
- [ ] Subscription field shows "@hlvpnbot"

#### Test Case 3: Copy Command from Chat
- [ ] Tap on empty "Запрос 1" field
- [ ] ChatActivity opens
- [ ] Copy command text (e.g., "/us1")
- [ ] Press back button
- [ ] Return to ConfigSubscrBotRequest
- [ ] "Запрос 1" field shows "/us1"
- [ ] No duplicate rows appear

#### Test Case 4: Edit Request Manually
- [ ] Tap on filled "Запрос 1" field
- [ ] Keyboard appears
- [ ] Can edit text
- [ ] Long-press shows select/copy/paste menu
- [ ] Changes saved automatically

#### Test Case 5: Update Key from Bot
- [ ] Fill request fields
- [ ] Tap "Обновить ключ"
- [ ] Message sent to bot
- [ ] Bot responds with vless:// URL
- [ ] Key field auto-fills
- [ ] Key copied to clipboard

#### Test Case 6: Send Keys to V2Ray
- [ ] Go back to main V2rayConfigFragment
- [ ] Tap "SEND KEYS TO V2RAY"
- [ ] v2rayNG receives and tests keys
- [ ] Response shows "Fr X imported Y work"

---

### 15.5 File Changes Summary

| File | Action | Description |
|------|--------|-------------|
| `ConfigSubscrBotRequest.java` | CREATE | New fragment for subscription 1 settings |
| `V2rayConfigFragment.java` | MODIFY | Remove subscription 2, simplify UI |
| `SharedConfig.java` | MODIFY | Deprecate v2raySubscription2/Request2/Key2 |
| `strings.xml` | MODIFY | Add new UI strings |
| `QWEN.md` | UPDATE | This specification |

---

### 15.6 Migration Path

**For Existing Users:**
- Subscription 2 data remains in SharedConfig (deprecated but not deleted)
- No data loss - Subscription 1 data unchanged
- Users with Subscription 2 configured can manually migrate to Subscription 1

**Future Enhancement:**
- If needed, add "Subscription 2" as a separate ConfigSubscrBotRequest instance
- Or implement multiple subscriptions as a list (Subscription 1, 2, 3...)

---

### 15.7 Success Criteria

✅ **No duplicate rows** in V2Ray settings  
✅ **Clipboard auto-fill works** when returning from ChatActivity  
✅ **Correct field receives** clipboard text (based on which field was tapped)  
✅ **Single subscription screen** is clean and intuitive  
✅ **All request fields** (1, 1.1, 1.2, 1.3) work independently  
✅ **Bot selection** works via DialogsActivity  
✅ **Command copying** works via ChatActivity  
✅ **Key update** sends message to bot and receives response  
✅ **Build passes** without errors  

---

### 15.8 Timeline Estimate

| Step | Estimated Time | Dependencies |
|------|---------------|--------------|
| Step 1: Create ConfigSubscrBotRequest | 2 hours | - |
| Step 2: Modify V2rayConfigFragment | 1 hour | Step 1 |
| Step 3: Fix clipboard logic | 1 hour | Step 1 |
| Step 4: Fix focus listener | 1 hour | Step 1 |
| Step 5: Update SharedConfig | 30 min | - |
| Step 6: Update strings.xml | 30 min | - |
| Step 7: Fix adapter | 1 hour | Step 1, 2 |
| Testing & bug fixes | 2 hours | All steps |
| **Total** | **8 hours** | |

---

**Specification Version:** 1.0
**Created:** 2026-03-15
**Status:** Ready for Implementation

---

## 16. V2Ray Settings UI Simplification - Implementation Status (Updated 2026-03-16)

### 16.1 Current Implementation Status

**Completed:**
✅ `ConfigSubscrBotRequest.java` - Created with full functionality
✅ `V2rayConfigFragment.java` - Simplified to show only Subscription 1 entry point
✅ `SharedConfig.java` - Contains v2raySubscription1, v2rayRequest1, v2rayKey1 fields
✅ `strings.xml` - Added required string resources
✅ Build passes without errors

### 16.2 Current Architecture

```
┌─────────────────────────────────────────────────────────────┐
│              V2rayConfigFragment (Main)                     │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Подписка 1 (Clickable) ──────────────────────────▶  │   │
│  │  [Opens ConfigSubscrBotRequest]                      │   │
│  └──────────────────────────────────────────────────────┘   │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  SEND KEYS TO V2RAY (Button)                         │   │
│  │  Answer from V2Ray: ...                              │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│         ConfigSubscrBotRequest (Subscription 1 Settings)    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Bot Username (@bot)                                 │   │
│  │    - Tap empty → DialogsActivity (select bot)        │   │
│  │    - Tap filled → Edit manually                      │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Request Commands Section:                           │   │
│  │    - Запрос 1 (request1)                             │   │
│  │      • Empty → ChatActivity (copy command)           │   │
│  │      • Filled → Edit manually                        │   │
│  │    - Запрос 1.1 (request11)                          │   │
│  │    - Запрос 1.2 (request12)                          │   │
│  │    - Запрос 1.3 (request13)                          │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │  Key Section:                                        │   │
│  │    - key fr.1 (key1) - Read-only, from bot           │   │
│  │    - Обновить ключ (Update Key Button)               │   │
│  └──────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 16.3 Implemented Behavior

#### Subscription Field (`subscriptionRow`)
- **Tap on empty field**: Opens `DialogsActivity` to select a bot
- **Tap on filled field**: Allows manual text editing
- **After selection**: Username saved with "@" prefix (e.g., "@hlvpnbot")
- **After cancel**: Field cleared

#### Request Fields (`request1Row`, `request11Row`, `request12Row`, `request13Row`)
- **Tap on empty field**: Opens `ChatActivity` with bot to copy command
- **Tap on filled field**: Allows manual text editing
- **After return from ChatActivity**: Auto-fills from clipboard
- **Manual edit**: Standard Android text editing (select/copy/paste)

#### Update Key Button (`updateKey1Row`)
- Sends all request commands to bot
- Waits for bot response with vless:// profile
- Saves received profile to `key1` field
- Copies profile to clipboard

### 16.4 Key Implementation Details

**State Tracking Variables:**
```java
private int choosingForSubscription;  // 1 = selecting for subscription
private int choosingForRequestField;  // -1, -11, -12, -13 for request fields
private boolean requestFieldManuallyTouched;
```

**Clipboard Auto-Fill Logic (in `onResume()`):**
```java
if (choosingForRequestField != 0) {
    // Get clipboard text
    // Fill appropriate field based on choosingForRequestField value
    // Reset choosingForRequestField to 0
}
```

**Focus Change Listener:**
```java
editCell.getTextView().setOnFocusChangeListener((v, hasFocus) -> {
    if (hasFocus && requestFieldManuallyTouched) {
        if (focusPos is request field && text is empty) {
            choosingForRequestField = -1 (or -11, -12, -13)
            openChatToViewMessages(botUsername)
        }
    }
});
```

### 16.5 Testing Checklist

#### Test Case 1: Open Subscription Settings
- [ ] Tap on "Подписка 1" in main screen
- [ ] ConfigSubscrBotRequest opens
- [ ] All fields displayed correctly (no duplicates)

#### Test Case 2: Select Bot
- [ ] Tap on empty subscription field
- [ ] DialogsActivity opens
- [ ] Select a bot (e.g., @hlvpnbot)
- [ ] Return to ConfigSubscrBotRequest
- [ ] Subscription field shows "@hlvpnbot"

#### Test Case 3: Copy Command from Chat
- [ ] Fill subscription field first
- [ ] Tap on empty "Запрос 1" field
- [ ] ChatActivity opens with bot
- [ ] Copy command text (e.g., "/us1")
- [ ] Press back button
- [ ] Return to ConfigSubscrBotRequest
- [ ] "Запрос 1" field shows "/us1"
- [ ] No duplicate rows appear

#### Test Case 4: Edit Request Manually
- [ ] Tap on filled "Запрос 1" field
- [ ] Keyboard appears
- [ ] Can edit text
- [ ] Long-press shows select/copy/paste menu
- [ ] Changes saved automatically

#### Test Case 5: Update Key from Bot
- [ ] Fill request fields
- [ ] Tap "Обновить ключ"
- [ ] Message sent to bot
- [ ] Bot responds with vless:// URL
- [ ] Key field auto-fills
- [ ] Key copied to clipboard

#### Test Case 6: Send Keys to V2Ray
- [ ] Go back to main V2rayConfigFragment
- [ ] Tap "SEND KEYS TO V2RAY"
- [ ] v2rayNG receives and tests keys
- [ ] Response shows "Fr X imported Y work"

### 16.6 Known Issues / To Verify

1. **Clipboard auto-fill timing**: Ensure clipboard is read after returning from ChatActivity
2. **Focus listener behavior**: Verify it only triggers on short taps, not long-press editing
3. **Bot username validation**: Ensure @ prefix is added correctly
4. **Request field state**: Ensure empty fields trigger ChatActivity, filled fields allow editing

### 16.7 Next Steps

According to `.qwen/speciification.md` current ToDo:

1. ✅ In `V2rayConfigFragment`: Tap on `subscription1Row` opens `ConfigSubscrBotRequest`
2. ⚠️ If `subscription1Row` was empty when tapped → Open `DialogsActivity` immediately after opening `ConfigSubscrBotRequest`
   - This behavior needs to be implemented in `ConfigSubscrBotRequest.onResume()`
   - Check if `SharedConfig.v2raySubscription1` is empty
   - If yes, automatically open `DialogsActivity` for bot selection
   - Use similar logic to old `V2rayConfigFragment` implementation

### 16.8 Implementation Plan for Remaining Task

**Task:** Auto-open DialogsActivity when subscription field is empty

**Location:** `ConfigSubscrBotRequest.java` → `onResume()` method

**Implementation:**
```java
@Override
public void onResume() {
    super.onResume();
    
    // Check if subscription is empty and we haven't opened dialog yet
    if (TextUtils.isEmpty(SharedConfig.v2raySubscription1) && !wasDialogOpened) {
        wasDialogOpened = true;
        openDialogsActivity();
        return;
    }
    
    // ... existing clipboard auto-fill logic ...
}

private void openDialogsActivity() {
    wasDialogSelected = false;
    choosingForSubscription = 1;
    Bundle args = new Bundle();
    args.putBoolean("onlySelect", true);
    args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
    DialogsActivity dialogsActivity = new DialogsActivity(args);
    dialogsActivity.setDelegate(ConfigSubscrBotRequest.this);
    presentFragment(dialogsActivity);
}
```

**Success Criteria:**
✅ When tapping empty "Подписка 1" in main screen → ConfigSubscrBotRequest opens → DialogsActivity automatically opens
✅ User selects bot → Returns to ConfigSubscrBotRequest → Subscription field shows "@username"
✅ User cancels (back button) → Returns to ConfigSubscrBotRequest → Subscription field remains empty
✅ No infinite loops or duplicate dialog openings

---

**Status Update:** 2026-03-16
**Build Status:** ✅ SUCCESSFUL
**Implementation:** ✅ COMPLETE (100%)

### 16.9 Completed Implementation

**Auto-Open DialogsActivity Feature:**
- Added `wasDialogOpened` flag to prevent infinite loops
- Modified `onResume()` to check if subscription is empty
- If empty and not opened yet → automatically open `DialogsActivity`
- Added `openDialogsActivity()` helper method

**Code Changes in `ConfigSubscrBotRequest.java`:**
```java
// New field
private boolean wasDialogOpened = false;

// In onResume()
if (TextUtils.isEmpty(SharedConfig.v2raySubscription1) && !wasDialogOpened) {
    wasDialogOpened = true;
    openDialogsActivity();
    return;
}

// New helper method
private void openDialogsActivity() {
    wasDialogSelected = false;
    choosingForSubscription = 1;
    Bundle args = new Bundle();
    args.putBoolean("onlySelect", true);
    args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
    DialogsActivity dialogsActivity = new DialogsActivity(args);
    dialogsActivity.setDelegate(ConfigSubscrBotRequest.this);
    presentFragment(dialogsActivity);
}
```

### 16.10 Final Testing Checklist

#### Complete Flow Test:
1. [ ] Clear all V2Ray settings (subscription, requests, keys)
2. [ ] Open V2rayConfigFragment from settings
3. [ ] Tap on empty "Подписка 1"
4. [ ] ConfigSubscrBotRequest opens
5. [ ] DialogsActivity automatically opens (because subscription is empty)
6. [ ] Select a bot (e.g., @hlvpnbot)
7. [ ] Return to ConfigSubscrBotRequest
8. [ ] Subscription field shows "@hlvpnbot"
9. [ ] Tap on empty "Запрос 1"
10. [ ] ChatActivity opens with bot
11. [ ] Copy command (e.g., "/us1")
12. [ ] Return to ConfigSubscrBotRequest
13. [ ] "Запрос 1" shows "/us1" (from clipboard)
14. [ ] Tap "Обновить ключ"
15. [ ] Message sent to bot
16. [ ] Bot responds with vless:// profile
17. [ ] Profile saved to "key fr.1"
18. [ ] Go back to V2rayConfigFragment
19. [ ] Tap "SEND KEYS TO V2RAY"
20. [ ] v2rayNG tests and imports keys
21. [ ] Response shows results

---

**All requirements from `.qwen/speciification.md` have been implemented.**

### 17. Multi-Request Implementation in ConfigSubscrBotRequest

**Task:** Expand request fields and add dedicated "Request Key" buttons for each.

**Implementation Details:**
- Added support for 4 request variants: Request 1, Request 1.1, Request 1.2, Request 1.3.
- Added dedicated buttons for each request field: "Update Key" (keyHeaderRow + updateKey1Row).
- Integrated with `SharedConfig` for all 4 request/key pairs (v2rayRequest1, v2rayKey1, v2rayRequest11, v2rayKey11, etc.). ✓
- Refactored UI to prevent "messed fields" issue:
    - Removed all View (Cell) references from class fields to avoid errors during RecyclerView recycling.
    - Switched to using `holder.getAdapterPosition()` in `TextWatcher` for accurate data binding.
    - Ensured strict order of rows: Request N followed by Update Key N.
    - Standardized labels and hints to "Запрос N" and "Запрос N ключа". ✓
- Updated `didReceivedNotification` to record the received key into the corresponding field based on the last clicked "Update Key" button.
- Implemented `sendToV2Ray(String key)` placeholder (ToDo).

**Code Changes:**
- `SharedConfig.java`: Added v2rayRequest11, v2rayKey11, v2rayRequest12, v2rayKey12, v2rayRequest13, v2rayKey13 and updated `saveConfig()`/`loadConfig()`.
- `ConfigSubscrBotRequest.java`:
    - Expanded row variables to include all 4 sets of request/key UI elements.
    - Updated `updateRows()` to place "Request Key" buttons immediately after each request field.
    - Modified `onUpdateKeyClicked(int index)` to handle specific request indices.
    - Updated `ListAdapter` to handle all new rows and types.
    - Updated `didReceivedNotification` to handle responses for all request indices and call `sendToV2Ray`.

**Status:** ✅ COMPLETE
**Build:** ✅ SUCCESSFUL

---

### 20. Fixed Subscription 1 Field in ConfigSubscrBotRequest

**Task:** Restore correct behavior for "Подписка 1" (subscription1Row) to allow bot selection via `DialogsActivity`.

**Implementation Details:**
- Changed `subscriptionRow` cell type from `EditTextSettingsCell` to `TextSettingsCell` to ensure standard clickable behavior.
- Updated `onBindViewHolder()` to handle `subscriptionRow` using `TextSettingsCell.setTextAndValue()`:
    - Displays label "Подписка 1".
    - Displays current bot username (value) or "None" if empty.
- Updated `getItemViewType()` to return type 0 (header/button) for `subscriptionRow`.
- Verified `didSelectDialogs()`:
    - Correctly extracts username from selected user/chat.
    - Saves "@username" to `SharedConfig.v2raySubscription1`.
    - Clears the field if nothing is selected (empty `username`).
    - Triggers UI update with `listAdapter.notifyDataSetChanged()`.
- Added `clearSubscriptionField()` method.
- Verified `onItemClickListener`:
    - Tapping on `subscriptionRow` opens `DialogsActivity` for chat selection.
- Maintained "Auto-open DialogsActivity" feature if subscription is empty.

**Status:** ✅ COMPLETE
**Build:** ✅ SUCCESSFUL

**Task:** Disable manual editing for `updateKey*Row` and update their behavior.

**Implementation Details:**
- Changed `updateKey*Row` cell type to `TextSettingsCell` (type 0) to prevent manual text editing.
- Updated `ListAdapter.getItemViewType()` to return type 0 for `updateKey*Row`.
- **Dynamic Status Display (RED):**
    - Initial: "Получить ключ по запросу [N]" (Red).
    - If command is missing: "нет запроса" (Red).
    - During request: "ожидаю" (Red).
    - On timeout: "Получить ключ по запросу [N] повторно" (Red).
- **Key Display:**
    - After receiving a valid v2ray key, it is displayed as the cell value in normal color.
- **Behavior:**
    - Clicking on `updateKey*Row` now triggers `onUpdateKeyClicked(index)`.
    - If the corresponding `request*Row` is empty, the status changes to "нет запроса" (Red).
    - Status "нет запроса" is reset to `IDLE` when user types in the `request*Row` field.
    - Successfully received keys are automatically passed to `sendToV2Ray(key)`.

**Status:** ✅ COMPLETE
**Build:** ✅ SUCCESSFUL

---

### 21. Fixed UI duplication and Message receiving in ConfigSubscrBotRequest

**Task:** Remove redundant labels from `updateKey*Row` and fix v2ray key capture from bot responses.

**Implementation Details:**
- **UI Refinement:**
    - Changed `updateKey*Row` (TextSettingsCell) to use `setText(value, true)` instead of `setTextAndValue(label, value, true)`.
    - This removes the redundant "Запрос[N] ключа" label from the left side, leaving only the status/key text.
- **Message Reception Fix:**
    - Added `NotificationCenter.didReceiveNewMessages` and `NotificationCenter.messagesDidLoad` to the observers in `onFragmentCreate`.
    - Updated `didReceivedNotification` to handle both notification types, correctly extracting the message list from `args[1]` (for new messages) or `args[2]` (for loaded messages).
    - Improved `parseVlessProfile` regex to support multiple v2ray-style protocols: `vless`, `vmess`, `ss`, `trojan`, `shadowsocks` (case-insensitive).
- **Cleanup:**
    - Removed unused `label` variable from `onBindViewHolder`.
    - Ensured `onFragmentDestroy` removes both observers.

**Status:** ✅ COMPLETE
**Build:** ✅ SUCCESSFUL


