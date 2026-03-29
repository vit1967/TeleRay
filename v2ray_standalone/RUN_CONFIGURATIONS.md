# V2RayNG Standalone - Run Configurations Guide

## Available Run Configurations

The project includes 3 pre-configured run configurations for v2ray_standalone module:

### 1. **v2ray_standalone** (Basic)
- **Purpose**: Standard run configuration for everyday use
- **Debugger**: Auto (automatically selects Java/Native)
- **Clear Logcat**: No
- **Use Case**: Quick launch and testing

**Features**:
- ✅ Automatic activity detection
- ✅ Shows logcat automatically
- ✅ Standard deployment

**How to Use**:
1. Open IntelliJ IDEA / Android Studio
2. Select **"v2ray_standalone"** from the run configurations dropdown (top toolbar)
3. Click ▶ Run button (or press Shift+F10)

---

### 2. **v2ray_standalone [Debug]** (Debugging)
- **Purpose**: Full debugging with Hybrid debugger (Java + Native)
- **Debugger**: Hybrid (Java + LLDB)
- **Clear Logcat**: Yes (starts with clean log)
- **Pre-build**: Runs `clean` task before build

**Features**:
- ✅ Java/Kotlin breakpoints supported
- ✅ Native C/C++ breakpoints supported
- ✅ Static variables visible
- ✅ Clean build each time
- ✅ Clear logcat on start

**How to Use**:
1. Set breakpoints in Java/Kotlin code (e.g., `V2RayNGReceiver.kt:onReceive()`)
2. Select **"v2ray_standalone [Debug]"** from dropdown
3. Click 🐞 Debug button (or press Shift+F9)
4. App will pause at breakpoints

**Useful Breakpoints**:
- `V2RayNGReceiver.kt:59` - onReceive() entry point
- `V2RayNGReceiver.kt:151` - handleImportKeys()
- `ProfileImporter.kt:45` - importProfile()
- `V2RayConfigManager.kt:120` - addProfile()

---

### 3. **v2ray_standalone [IPC Test]** (IPC Testing)
- **Purpose**: Specialized configuration for testing IPC communication with NekoX
- **Debugger**: Java only (for faster startup)
- **Clear Logcat**: Yes
- **Pre-build**: Assembles debug APK
- **Apply Changes**: Enabled (hot-swap code)

**Features**:
- ✅ Fast deployment
- ✅ Clean logcat for IPC message tracking
- ✅ Optimized for IPC debugging
- ✅ Apply Changes support (modify code without full rebuild)

**How to Use**:
1. Install NekoX app first: `./gradlew :TMessagesProj_App:installAfatDebug`
2. Select **"v2ray_standalone [IPC Test]"**
3. Click ▶ Run
4. In NekoX app: Settings → Passcode → V2Ray Subscription Settings
5. Send key request from NekoX
6. Monitor logcat for IPC messages

**Logcat Filters**:
```bash
# Inside IDE Logcat window, add filter:
V2RayNGReceiver|ProfileImporter|V2RayConfigManager

# Or use ADB:
adb logcat -s "V2RayNGReceiver" -s "ProfileImporter" -s "V2RayConfigManager"
```

---

## Configuration Files Location

```
.idea/runConfigurations/
├── v2ray_standalone.xml
├── v2ray_standalone__Debug_.xml
└── v2ray_standalone__IPC_Test_.xml
```

These files are tracked in git and shared across team members.

---

## Switching Between Configurations

### Via IDE Toolbar
1. Look for dropdown at top toolbar (next to ▶ Run / 🐞 Debug buttons)
2. Click dropdown
3. Select desired configuration
4. Click Run/Debug button

### Via Keyboard Shortcut
- **Alt + Shift + F10** (Linux/Windows)
- **Ctrl + Alt + R** (macOS)
- Then press number key (1-8) to select configuration

### Via Run Menu
1. Menu: Run → Edit Configurations...
2. Select configuration from list
3. Click OK
4. Menu: Run → Run 'v2ray_standalone'

---

## Customizing Configurations

### Edit Existing Configuration
1. Run → Edit Configurations...
2. Select configuration (e.g., "v2ray_standalone [Debug]")
3. Modify options:
   - **Deployment Target**: Choose device/emulator
   - **Debugger**: Auto / Java / Native / Hybrid
   - **Before launch**: Add tasks (e.g., custom Gradle tasks)
   - **Logcat**: Enable/disable auto-clear
4. Click Apply → OK

### Create New Configuration
1. Run → Edit Configurations...
2. Click **+** → Android App
3. Name: `v2ray_standalone [Custom]`
4. Module: `NekoX-IU3.v2ray_standalone`
5. Configure options as needed
6. Click Apply → OK

---

## Common Use Cases

### Scenario 1: Quick Testing
**Configuration**: `v2ray_standalone`
```
1. Make code changes
2. Select "v2ray_standalone"
3. Press Shift+F10 (Run)
4. Test in app
```

### Scenario 2: Debugging IPC Issues
**Configuration**: `v2ray_standalone [IPC Test]`
```
1. Set breakpoint in V2RayNGReceiver.kt:handleImportKeys()
2. Select "v2ray_standalone [IPC Test]"
3. Press Shift+F9 (Debug)
4. In NekoX, send key from ConfigSubscrBotRequest
5. Breakpoint hits → inspect intent extras
```

### Scenario 3: Native Debugging (C++ libv2ray)
**Configuration**: `v2ray_standalone [Debug]`
```
1. Set breakpoint in native code (if sources available)
2. Select "v2ray_standalone [Debug]"
3. Press Shift+F9 (Debug)
4. Wait for symbols to load
5. Trigger native code path
```

### Scenario 4: Both Apps Running
**Steps**:
```bash
# Terminal 1: Run NekoX
./gradlew :TMessagesProj_App:installAfatDebug
adb shell am start -n org.telegram.messenger.beta/org.telegram.ui.LaunchActivity

# IDE: Run v2ray_standalone [IPC Test]
# Now both apps are running, test IPC communication
```

---

## Debugging Tips

### Enable Verbose Logging
Add to `v2ray/app/src/main/java/com/v2ray/ang/receiver/V2RayNGReceiver.kt`:
```kotlin
private const val DEBUG = true

fun onReceive(context: Context, intent: Intent) {
    if (DEBUG) Log.d(TAG, "onReceive: action=${intent.action}, extras=${intent.extras}")
    // ... rest of code
}
```

### Filter Logcat for IPC Only
In IDE Logcat window:
- **Package**: `com.v2ray.ang`
- **Log Level**: Debug
- **Filter Text**: `V2RayNG|NEKOX_COMMAND|IMPORT_KEYS`

### Attach Debugger to Running Process
If v2ray_standalone is already running:
1. Run → Attach Debugger to Android Process
2. Select `com.v2ray.ang.debug`
3. Choose process: `com.v2ray.ang.debug:RunSoLibV2RayDaemon`
4. Set breakpoints and trigger events

---

## Troubleshooting

### Configuration Not Appearing
**Problem**: Run configuration dropdown is empty
**Solution**:
1. File → Sync Project with Gradle Files
2. Wait for sync to complete
3. Refresh run configurations: Run → Edit Configurations → OK

### Module Not Found Error
**Problem**: `Module 'NekoX-IU3.v2ray_standalone' not found`
**Solution**:
1. Check `settings.gradle` includes: `include ':v2ray_standalone'`
2. File → Invalidate Caches → Invalidate and Restart
3. Re-open project

### APK Not Installing
**Problem**: Installation fails with INSTALL_FAILED_*
**Solution**:
```bash
# Uninstall existing apps
adb uninstall com.v2ray.ang
adb uninstall com.v2ray.ang.debug

# Clean and rebuild
./gradlew clean :v2ray_standalone:assembleDebug

# Reinstall via IDE or:
./gradlew :v2ray_standalone:installDebug
```

### Debugger Not Hitting Breakpoints
**Problem**: Breakpoints show ✓ but never trigger
**Solution**:
1. Verify you're using correct configuration (Debug or IPC Test)
2. Check module sources are attached (File → Project Structure → Modules)
3. Ensure code hasn't been obfuscated (debug builds should have minifyEnabled=false)
4. Try: Run → Reload Classes (after code changes)

---

## Performance Tips

### Faster Incremental Builds
Use **Apply Changes** feature (Lightning bolt icon):
- **Apply Changes and Restart Activity**: Ctrl+F10 (Linux/Win), Cmd+F10 (Mac)
- **Apply Code Changes**: Ctrl+Alt+F10 (Linux/Win), Cmd+Shift+F10 (Mac)

### Skip Build Cache
For troubleshooting only:
```bash
./gradlew :v2ray_standalone:assembleDebug --rerun-tasks
```

### Parallel Builds
Already enabled in `gradle.properties`:
```properties
org.gradle.parallel=true
org.gradle.jvmargs=-Xmx4096M
```

---

## See Also

- **v2ray_standalone/README.md** - Module overview
- **v2ray_standalone/INTEGRATION.md** - IPC integration details
- **TgXRayAPIspecification.md** - Complete IPC protocol spec
- **CLAUDE.md** - Project architecture

---

## Quick Reference Card

| Configuration | Use For | Debugger | Clean Logcat | Speed |
|---------------|---------|----------|--------------|-------|
| v2ray_standalone | Daily testing | Auto | ❌ | ⚡⚡⚡ Fast |
| v2ray_standalone [Debug] | Deep debugging | Hybrid | ✅ | ⚡⚡ Medium |
| v2ray_standalone [IPC Test] | IPC testing | Java | ✅ | ⚡⚡⚡ Fast |

**Keyboard Shortcuts**:
- **Run**: Shift+F10
- **Debug**: Shift+F9
- **Select Configuration**: Alt+Shift+F10
- **Apply Changes**: Ctrl+F10
- **Stop**: Ctrl+F2
