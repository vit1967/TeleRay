# NekoX ↔ v2rayNG Inter-Process Communication (IPC) API Specification

## Overview

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
| `CMD_START_V2RAY` | 1003 | Start v2rayNG service (VPN or proxy-only) | None |
| `CMD_STOP_V2RAY` | 1004 | Stop v2rayNG service | None |
| `CMD_STATUS_CHECK` | 1005 | Query running state + last TCP ping to selected server | None |
| `CMD_SELECT_BEST_KEY` | 1006 | TCP-ping all keys, select key with min ping, restart v2ray | `EXTRA_TRUST_STORED` (optional bool) |
| `CMD_DEDUP_PROFILES` | 1007 | Remove duplicate profiles (same server:port), keep best per group | None |
| `CMD_TEST_REAL_PING`  | 1008 | Measure real outbound delay for all stored profiles, store results | None |
| `CMD_SORT_BY_TEST`    | 1009 | Sort stored profiles by stored testDelayMillis (ascending) | None |

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
   - Send `ACTION_RESPONSE_PORT_CONFLICT` — do NOT start service
3. Check VPN permission: `android.net.VpnService.prepare(context)`
   - If NOT granted: switch mode to proxy-only (`PREF_MODE = "Proxy"`) — cannot show permission dialog from BroadcastReceiver
4. Auto-select first server if none is selected: `MmkvManager.decodeServerList().first()`
5. Call `V2RayServiceManager.startVServiceFromToggle(context)`
6. Send `ACTION_RESPONSE_STARTED` (success or with `EXTRA_ERROR_MESSAGE`)

**Note on VPN permission**: The BroadcastReceiver runs in `:RunSoLibV2RayDaemon` process and cannot launch an Activity to show the system VPN permission dialog. If the user has never granted VPN permission, the service falls back to proxy-only mode (SOCKS5/HTTP proxy without VPN tunnel). Use the v2rayTg UI to grant VPN permission and switch back to VPN mode.

**Responses**:
- Success: `ACTION_RESPONSE_STARTED`
- Port conflict: `ACTION_RESPONSE_PORT_CONFLICT`
- Error: `ACTION_RESPONSE_STARTED` with `EXTRA_ERROR_MESSAGE`

---

#### 2.3.5 CMD_STATUS_CHECK (1005)

**Purpose**: Check whether v2rayNG service is currently running and get last TCP ping to selected server.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_STATUS_CHECK);
context.sendBroadcast(intent);
```

**v2rayNG Processing**:
1. Check `V2RayServiceManager.isRunning()`
2. If running: run TCP socket test `SpeedtestManager.socketConnectTime()` on selected server
3. Send `RESP_STATUS` with `is_running` and `last_ping_ms`

**Response**: `RESP_STATUS` (2003)
**Timeout**: 5 seconds → `onNoAnswer()` callback

---

#### 2.3.6 CMD_SELECT_BEST_KEY (1006)

**Purpose**: TCP-ping all stored keys, select the one with minimum latency, switch v2rayNG to it and restart the service if needed. Returns selected key GUID and ping.

**Request**:
```java
Intent intent = new Intent(ACTION_V2RAY_COMMAND);
intent.setPackage(context.getPackageName());
intent.putExtra(EXTRA_COMMAND, V2RayNekoBridge.CMD_SELECT_BEST_KEY);
context.sendBroadcast(intent);
```

**Processing in v2rayNG**:
1. Iterate `MmkvManager.decodeServerList()`, TCP-ping each key with `SpeedtestManager.socketConnectTime()`
2. Find the key with minimum ping (> 0 && < 10000 ms)
3. `MmkvManager.setSelectServer(bestGuid)` — switch to best key
4. If best key changed or service not running: `V2RayServiceManager.startVServiceFromToggle(context)`
5. Send `RESP_SELECT_BEST_KEY` with results

**Response**: `RESP_SELECT_BEST_KEY` (2004)

**Extras in response**:
```java
intent.putExtra(EXTRA_WORKING_COUNT, workingCount);      // Int: number of keys with ping < 10s
intent.putExtra(EXTRA_SELECTED_KEY_GUID, bestGuid);      // String: GUID of selected key, "" if none
intent.putExtra(EXTRA_LAST_PING_MS, bestPing);           // Long: ping of selected key, -1 if none
```

**Callback**: `onSelectBestKeyResult(int working, String selectedGuid, long pingMs)`
- `working == 0` or `selectedGuid.isEmpty()` → no working key found → proceed to MTProxy
- `working > 0` → best key selected, VPN (re)started → check Telegram connectivity via `checkForeignConnectivity()`

**Timeout**: 30 seconds → `onNoAnswer()` callback (large timeout to allow pinging many keys)

**PATH A/B/C logic** (since IU06):
- PATH A (VPN running): uses stored delays to pick best alternative without disrupting tunnel.
  If no good alternative exists (all stored delays ≤ 0), stops VPN and falls through to PATH B.
- PATH B (VPN stopped): runs `Libv2ray.measureOutboundDelay()` real tests, starts v2ray with winner.
- PATH C (`EXTRA_TRUST_STORED=true`): skips re-measurement; uses stored `testDelayMillis` from a
  prior `CMD_TEST_REAL_PING`. Activates only when `trustStored=true` (set by AutoConnectController
  after receiving `RESP_TEST_REAL_PING_DONE` → sort → 500ms delay → select).

---

#### 2.3.7 CMD_DEDUP_PROFILES (1007)

**Purpose**: Remove duplicate profiles from v2rayNG storage (same `server:serverPort` key).
Fire-and-forget — no response sent back to TeleRay. Called at the start of each auto-connect cycle.

**Request**:
```java
v2rayBridge.sendDedupProfilesCommand(); // no timeout, no callback
```

**v2rayNG Processing**:
1. Load all GUIDs from MMKV: `MmkvManager.decodeServerList()`
2. Group by `server:serverPort` key
3. Within each group, keep the profile with the best positive stored delay (or most recent if untied)
4. Never remove the currently selected profile
5. Delete duplicates via `MmkvManager.removeServer(guid)`
6. Toast with count of removed profiles if any

**Response**: None (fire-and-forget)

---

#### 2.3.8 CMD_TEST_REAL_PING (1008)

**Purpose**: Measure real outbound delay for every stored profile and persist results in MMKV
(`ServerAffiliationInfo.testDelayMillis`). Run before `CMD_SORT_BY_TEST` + `CMD_SELECT_BEST_KEY`
(PATH C) to get fresh per-profile latency without running a full VPN cycle.

**Request**:
```java
v2rayBridge.sendTestRealPingCommand(); // timeout: 15 s
```

**v2rayNG Processing**:
1. Load all GUIDs: `MmkvManager.decodeServerList()`
2. For each profile, spawn a thread:
   - Decode server + port from config
   - TCP socket test: `SpeedtestManager.socketConnectTime(server, port)` (3 s timeout)
   - Store result: `MmkvManager.encodeServerTestDelayMillis(guid, delay)`
   - Count as working if delay > 0
3. Join all threads (max 5 s per thread)
4. Send `RESP_TEST_REAL_PING_DONE` (2005) with working/total counts

**Response**: `RESP_TEST_REAL_PING_DONE` (2005)
**Timeout**: 15 seconds → `onNoAnswer()` callback

---

#### 2.3.9 CMD_SORT_BY_TEST (1009)

**Purpose**: Sort the stored profile list by ascending `testDelayMillis` (profiles with no stored
result go to the end). Fire-and-forget — no response.

**Request**:
```java
v2rayBridge.sendSortByTestCommand(); // no callback
```

**v2rayNG Processing**:
1. Load all GUIDs and their stored `testDelayMillis`
2. Sort ascending (`Long.MAX_VALUE` for untested profiles)
3. Persist sorted list: `MmkvManager.encodeServerList(sortedGuids)`
4. Toast confirmation

**Response**: None (fire-and-forget)

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
| `RESP_STATUS` | 2003 | Service running state + last ping | `EXTRA_IS_RUNNING`, `EXTRA_LAST_PING_MS` |
| `RESP_SELECT_BEST_KEY` | 2004 | Result of best key selection | `EXTRA_WORKING_COUNT`, `EXTRA_SELECTED_KEY_GUID`, `EXTRA_LAST_PING_MS` |
| `RESP_TEST_REAL_PING_DONE` | 2005 | All profiles pinged, results stored in MMKV | `EXTRA_WORKING_COUNT`, `EXTRA_TOTAL_COUNT` |
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

#### 3.3.3 RESP_STATUS (2003)

**Triggered by**: `CMD_STATUS_CHECK`

**Extras**:
```java
intent.putExtra(EXTRA_COMMAND, V2RayNGReceiver.RESP_STATUS);
intent.putExtra(EXTRA_IS_RUNNING, true);          // Boolean
intent.putExtra(EXTRA_LAST_PING_MS, 123L);        // Long, -1 if not running or test failed
intent.putExtra(EXTRA_ERROR_MESSAGE, "...");      // Optional
```

**NekoX Handling**: `onStatusResult(isRunning, lastPingMs, errorMsg)` callback

**UI Display in SubscriptionsConfigFragment**:
- Running: `"v2ray: работает, ping: 123мс"`
- Not running: `"v2ray: не запущен"`
- No answer (timeout): `"v2ray: нет ответа (завис?)"`

---

#### 3.3.4 RESP_SELECT_BEST_KEY (2004)

**Triggered by**: `CMD_SELECT_BEST_KEY`

**Extras**:
```java
intent.putExtra(EXTRA_COMMAND, V2RayNGReceiver.RESP_SELECT_BEST_KEY);
intent.putExtra(EXTRA_WORKING_COUNT, 3);              // Int: keys with ping < 10s
intent.putExtra(EXTRA_SELECTED_KEY_GUID, "abc-123");  // String: GUID of best key, "" if none
intent.putExtra(EXTRA_LAST_PING_MS, 87L);             // Long: best ping, -1 if no working key
intent.putExtra(EXTRA_ERROR_MESSAGE, "...");           // Optional, only on error
```

**NekoX Handling**: `onSelectBestKeyResult(working, selectedGuid, pingMs)` callback
- `working > 0 && !selectedGuid.isEmpty()` → VPN restarted with best key → run `checkForeignConnectivity()`
- `working == 0` → no working keys → run `testAllmtProxy()`

---

#### 3.3.5 RESP_TEST_REAL_PING_DONE (2005)

**Triggered by**: `CMD_TEST_REAL_PING`

**Extras**:
```java
intent.putExtra(EXTRA_COMMAND, V2RayNGReceiver.RESP_TEST_REAL_PING_DONE);
intent.putExtra(EXTRA_WORKING_COUNT, 4);  // Profiles with ping > 0
intent.putExtra(EXTRA_TOTAL_COUNT, 6);   // Total profiles tested
```

**NekoX Handling**: `onTestRealPingDone(working, total)` callback
- Typical flow in `AutoConnectController`:
  1. Update UI: `"Пинг v2ray: 4/6 рабочих. Сортирую..."`
  2. Send `CMD_SORT_BY_TEST` (fire-and-forget)
  3. Wait 500 ms, then send `CMD_SELECT_BEST_KEY(trustStored=true)` (PATH C)

**PATH C** (`EXTRA_TRUST_STORED=true` in `CMD_SELECT_BEST_KEY`):
- Skips real re-measurement (results already in MMKV from CMD_TEST_REAL_PING)
- Takes profile with best positive `testDelayMillis` from the (now sorted) list
- Starts VPN immediately with that profile

---

#### 3.3.6 RESP_NO_V2RAY (2999)

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
public void sendTestKeysCommand()                          // timeout: 8s
public void sendImportKeysCommand(String key1, String key2) // timeout: 12s
public void sendStatusCheckCommand()                       // timeout: 5s
public void sendStartV2RayCommand()                        // timeout: 10s → onStarted(false, "timeout")
public boolean isV2RayAvailable()
public boolean isV2RayReceiverRegistered()

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
    void onStatusResult(boolean isRunning, long lastPingMs, String errorMsg);
    void onStarted(boolean success, String errorMsg);
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
