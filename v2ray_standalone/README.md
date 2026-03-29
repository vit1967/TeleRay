# V2RayNG Standalone Application

This module is a standalone Android application version of V2RayNG that can run independently from NekoX while maintaining IPC communication capabilities.

## Purpose

- **Development & Testing**: Run V2RayNG as a separate app for debugging and testing
- **IPC Communication**: Maintains full compatibility with NekoX IPC protocol (see `TgXRayAPIspecification.md`)
- **Standalone Functionality**: Can be used as a regular V2RayNG VPN application

## Architecture

```
v2ray_standalone (Android Application)
├── Reuses source code from ../v2ray/app/src/main/java
├── Independent AndroidManifest.xml with launcher activity
├── Package ID: com.v2ray.ang
└── IPC Receiver: V2RayNGReceiver (receives commands from NekoX)
```

## Build & Run

### Build APK

```bash
# Debug build
./gradlew :v2ray_standalone:assembleDebug

# Release build
./gradlew :v2ray_standalone:assembleRelease

# Install to device
./gradlew :v2ray_standalone:installDebug
```

### Run Configurations

The project includes **3 pre-configured run configurations**:

#### 1. **v2ray_standalone** (Basic)
- Quick launch for everyday testing
- Auto debugger selection
- Standard deployment

#### 2. **v2ray_standalone [Debug]** (Full Debugging)
- Hybrid debugger (Java + Native)
- Clean logcat on start
- Clean build before run
- Best for deep debugging

#### 3. **v2ray_standalone [IPC Test]** (IPC Testing)
- Optimized for IPC communication testing
- Java-only debugger (faster)
- Apply Changes enabled
- Best for testing NekoX ↔ V2Ray communication

**How to Use**:
1. Open IntelliJ IDEA / Android Studio
2. Select configuration from dropdown (top toolbar)
3. Click ▶ Run or 🐞 Debug button

**Keyboard Shortcuts**:
- Run: **Shift+F10**
- Debug: **Shift+F9**
- Select Config: **Alt+Shift+F10**

See [RUN_CONFIGURATIONS.md](RUN_CONFIGURATIONS.md) for detailed guide.

The app will appear as "V2RayNG" in the launcher with its own icon.

## Configuration

### Application ID

- **Debug**: `com.v2ray.ang.debug`
- **Release**: `com.v2ray.ang`

### Build Configuration

- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 34 (Android 14)
- **Compile SDK**: 34
- **JDK**: 17

### Key Features

- ✅ Runs in separate process `:RunSoLibV2RayDaemon`
- ✅ IPC Broadcast Receiver configured for NekoX communication
- ✅ Full V2RayNG UI (MainActivity, SettingsActivity, etc.)
- ✅ VPN Service with system-level integration
- ✅ MMKV shared storage with NekoX (same package)
- ✅ Profile import/export capabilities
- ✅ **Automatic key import**: `ConfigSubscrBotRequest.sendToV2Ray()` → `V2RayNekoBridge` → v2ray_standalone

## IPC Protocol

This standalone app implements the full NekoX ↔ V2RayNG IPC protocol:

### Data Flow (NekoX → v2ray_standalone)

```
┌─────────────────────────────────────────────────────────────────┐
│ User Action in NekoX                                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ ConfigSubscrBotRequest.java                                     │
│  1. User taps "Получить ключ по запросу X"                      │
│  2. sendMessageToBot() → sends command to Telegram bot          │
│  3. Bot responds with vless://... key                           │
│  4. didReceivedNotification() receives key                      │
│  5. parseVlessProfile() validates key format                    │
│  6. Saves key to SharedConfig.v2rayKeyX                         │
│  7. Copies key to clipboard                                     │
│  8. Calls sendToV2Ray(key) ───────────────────────┐             │
└───────────────────────────────────────────────────┼─────────────┘
                                                    │
                                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ V2RayNekoBridge.java                                            │
│  1. getInstance(context)                                        │
│  2. initialize() - register BroadcastReceiver                   │
│  3. sendImportKeysCommand(key, "")                              │
│     - Creates Intent(ACTION_V2RAY_COMMAND)                      │
│     - putExtra(EXTRA_COMMAND, CMD_IMPORT_KEYS)                  │
│     - putExtra(EXTRA_KEY1, key)                                 │
│     - sendBroadcast(intent) ─────────────────────┐              │
└───────────────────────────────────────────────────┼─────────────┘
                                                    │
                    ════════════════════════════════╪═════════════
                    IPC Boundary (Broadcast)        │
                    ════════════════════════════════╪═════════════
                                                    │
                                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ v2ray_standalone/V2RayNGReceiver.kt                             │
│  1. onReceive(intent) in :RunSoLibV2RayDaemon process           │
│  2. handleEnhancedCommand()                                     │
│  3. handleImportKeys(key1, key2)                                │
│     - ProfileImporter.importProfile(key1)                       │
│     - V2RayConfigManager.addProfile()                           │
│     - MMKV.encode() - save to storage                           │
│  4. handleTestKeys() - test imported profile                    │
│  5. Send response broadcast ──────────────────────┐             │
│     - ACTION_V2RAY_RESPONSE                       │             │
│     - EXTRA_COMMAND = RESP_IMPORT_RESULT          │             │
│     - EXTRA_IMPORTED_COUNT = 1                    │             │
│     - EXTRA_WORKING_COUNT = 1                     │             │
└───────────────────────────────────────────────────┼─────────────┘
                                                    │
                    ════════════════════════════════╪═════════════
                    IPC Boundary (Broadcast)        │
                    ════════════════════════════════╪═════════════
                                                    │
                                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│ V2RayNekoBridge.java (NekoX)                                    │
│  1. responseReceiver.onReceive()                                │
│  2. handleResponse(intent)                                      │
│  3. onImportResult(imported=1, working=1, total=1)              │
│  4. Toast: "V2Ray: Imported 1, 1/1 working"                     │
└─────────────────────────────────────────────────────────────────┘
```

### Supported Commands (from NekoX)

- `CMD_TEST_KEYS` (1001): Test all stored profiles
- `CMD_IMPORT_KEYS` (1002): Import and test keys
- `CMD_START_V2RAY` (1003): Start VPN service
- `CMD_STOP_V2RAY` (1004): Stop VPN service

### Broadcast Actions

**Receives**:
- `com.v2ray.ang.action.NEKOX_COMMAND`
- `com.v2ray.ang.action.NEKOX_START`
- `com.v2ray.ang.action.NEKOX_STOP`
- `com.v2ray.ang.action.NEKOX_IMPORT_PROFILE`
- `com.v2ray.ang.action.NEKOX_TEST_PROFILE`

**Sends**:
- `com.v2ray.ang.action.NEKOX_RESPONSE`
- `com.v2ray.ang.action.RESPONSE_*`

See `TgXRayAPIspecification.md` for complete protocol documentation.

## Testing IPC Communication

### 1. Install both apps

```bash
# Install NekoX
./gradlew :TMessagesProj_App:installAfatDebug

# Install V2RayNG standalone
./gradlew :v2ray_standalone:installDebug
```

### 2. Test from NekoX

1. Open NekoX
2. Go to Settings → Passcode → V2Ray Subscription Settings
3. Select bot (tap "Подписка 1" field)
4. Fill request commands (e.g., "getkey1", "getkey2")
5. Tap "Получить ключ по запросу X" button
6. Wait for bot response
7. **Automatic**: When key is received, `sendToV2Ray()` automatically sends it to v2ray_standalone via IPC
8. Check toast notifications for import results

### 3. Monitor logs

```bash
# Full IPC communication logs
adb logcat -s "V2RayNGReceiver*" -s "V2RayNekoBridge*" -s "ProfileImporter*" -s "ConfigSubscrBotRequest*"

# V2Ray service logs
adb logcat -s "V2RayVpnService*" -s "V2RayProxyOnlyService*"
```

### 4. Verify key import

1. Open v2ray_standalone app
2. Check if new profile appears in the list
3. Tap profile to view details
4. Use "Test" button to verify connectivity

## Differences from Library Module

| Feature | v2ray (library) | v2ray_standalone (app) |
|---------|----------------|------------------------|
| Plugin type | `com.android.library` | `com.android.application` |
| Can run standalone | ❌ No | ✅ Yes |
| Has launcher icon | ❌ No | ✅ Yes |
| Application ID | N/A | `com.v2ray.ang` |
| Manifest | Library manifest | Full app manifest |
| Source reuse | Original sources | Reuses library sources |

## Development Notes

### Source Code Reuse

The module reuses sources from the v2ray library:

```gradle
sourceSets {
    main {
        java.srcDirs = ['../v2ray/app/src/main/java']
        res.srcDirs = ['../v2ray/app/src/main/res', 'src/main/res']
        jniLibs.srcDirs = ['../v2ray/app/libs']
    }
}
```

Changes to `v2ray/app/src/main/java` automatically affect both modules.

### Separate Manifest

The standalone module has its own `AndroidManifest.xml` with:
- Launcher activity declaration
- Application-level settings
- Full service and receiver registration

### BuildConfig Fields

Required BuildConfig fields for compatibility:
```gradle
buildConfigField "String", "VERSION_NAME", "\"1.9.45\""
buildConfigField "String", "APPLICATION_ID", "\"com.v2ray.ang\""
buildConfigField "String", "FLAVOR", "\"fdroid\""
```

## Troubleshooting

### Port Conflicts

If ports 10808 or 10853 are in use:
- Stop other VPN applications
- Check with `adb shell netstat -tuln | grep -E '10808|10853'`

### IPC Not Working

1. Verify both apps are installed
2. Check package names match (`com.v2ray.ang`)
3. Monitor logcat for broadcast messages
4. Ensure receiver is registered in manifest

### Build Errors

**minSdk error**: V2RayNG requires minSdk 24 due to editorkit dependency

**FLAVOR error**: Ensure BuildConfig fields are defined in `defaultConfig`

## License

Same as V2RayNG and NekoX projects.

## See Also

- `../v2ray/` - Original V2RayNG library module
- `TgXRayAPIspecification.md` - Complete IPC protocol documentation
- `CLAUDE.md` - Project architecture documentation
