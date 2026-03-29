# CLAUDE.md

## ToDo:
Проект успешно собирается и работает на Android.  
При затруднении соединения за счет блокировки(в РФ) Telegram и ВПН, польз-ль жмет кн. 
авто-обновления с запуском в цикла startLoopTstConnect(), AutoConnectController, startLoopTstConnect, и.т.д. которые должне действовать по след. общей логике:
1. Попытка сразу "починить" управляемый по IPC v2rayTg, проверив, отсортировав и выбрав лучший из 
   имеющихся в  v2rayTg профиль 
2. Если не помогло, - "поскрести по сусекам" ранее сохраненных профилей в базах 
  панелей  ConfigSubscrBotRequest.java , повторно запросить у ботов по хранимых тамже командам 
   запросов, все полученное -отправить в v2rayTg, снова протестировать в v2rayTg, отсортировать, 
   включить лучший.
3. Если не сработали 1,2, - работа с прокси, т.е. 3.1. включение proxyList , если не помогли 
   имеющиеся таам, то 3.2. добавление    туда (с проверкой и включением)  прокси, сохраненных ранее (в фазах резервирования) в сохраненных 
   базах панелей ConfigMtpBotRequest.java , ConfigMTProxySubscr.java  (накапливаемом при 
   резервировании в сумм.кол-ве, указанном на панели SubscriptionsConfigFragment.java  в поле  
   "мин. ко-во MTProxy".
4. Если и это не помогло, попытка прочесть еще видимые последние сообщения в каналах MTProxy, 
   ранее запомненных в панелях ConfigMTProxySubscr, и попытка запросить прокси у ботов из 
   панелей ConfigMtpBotRequest. Добавление в proxyList Telegram.
5. Как только восстановилось подключение (например после действий предыдущ. цикла, с прокси, 
   т.е. скорей всего глючное ),  -снова п.2.,т.е. получение от ботов и обновление профилей в 
   v2rayTg.
6. Если коннект через ВПН (v2rayTg) и в порядке , -переход к резервированию.

Если эту логику можно еще улучшить для скорейшего(за меньше циклов)) достижение коннекта без 
сильного усложнения (ибо чем сложней, тем больше багов;), то улучши, и проверь эту ли лгикку 
реализует код 

## сверься еще раз с  ToDo, Поругай непонятные и противоречивые п. (исправлю), покажи план. потом реализуй.
При непонятках, сомнениях и отходе от задач этого ToDo сначала останавливайся и уточняй - задавай уточняющие вопросы!!!
fix build errors






## Project Overview

TeleRay is a fork of the official Telegram Android client (NekoX-based) with integrated v2rayNG proxy functionality. The app package is `com.teleray.messenger`. The project combines Telegram's messaging code with a bundled v2rayNG VPN module that communicates via IPC.

**Base**: Official Telegram Android (https://github.com/DrKLO/Telegram)
**Key Addition**: v2rayNG integration for VPN/proxy support with Telegram bot-based key subscription

## Build System

- **Gradle**: 8.6.1 (`./gradlew` wrapper)
- **Android Gradle Plugin**: 8.6.1
- **JDK**: 17 (required)
- **NDK**: r21.4.7075529 (must match exactly — specified in gradle.properties)
- **CMake**: 3.10.2
- **Compile/Target SDK**: 35, **Min SDK**: 21
- **App Version**: 12.4.1 (code: 6510)

### Module Structure

```
TMessagesProj          # Core library (messaging, MTProto, UI)
TMessagesProj_App      # Main application (com.teleray.messenger)
TMessagesProj_AppHuawei    # Huawei HMS variant
TMessagesProj_AppHockeyApp # Beta testing variant
TMessagesProj_AppStandalone # No Firebase
TMessagesProj_AppTests # TL schema validation tests
v2ray                  # V2rayNG proxy as Android library module
v2ray_standalone       # V2rayNG as a standalone runnable app (shares sources with v2ray)
```

### Common Build Commands

```bash
# Main app - debug
./gradlew :TMessagesProj_App:assembleAfatDebug

# Main app - release
./gradlew :TMessagesProj_App:assembleAfatRelease

# V2Ray standalone app
./gradlew :v2ray_standalone:assembleDebug
./gradlew :v2ray_standalone:installDebug

# Install both for IPC testing
./gradlew :TMessagesProj_App:installAfatDebug
./gradlew :v2ray_standalone:installDebug

# Clean build
./gradlew clean :TMessagesProj_App:assembleAfatDebug
```

### Build Variants

- `afat`: arm-v7a, arm64-v8a, x86, x86_64 (min SDK 21) — standard APK
- `bundleAfat`: All ABIs — Play Store AAB
- `bundleAfat_SDK23`: All ABIs, min SDK 23 — Play Store AAB (newer devices)

Build types: `debug` (adds `.beta` suffix), `release` (ProGuard), `standalone` (no Firebase, adds `.web` suffix)

### Required Setup

1. Keystore: `TMessagesProj/config/release.keystore`
2. Firebase: `google-services.json` in `TMessagesProj/` and `TMessagesProj_App/`
3. API credentials in `TMessagesProj/src/main/java/org/telegram/messenger/BuildVars.java`: `APP_ID`, `APP_HASH`

## Architecture

### Core Packages (TMessagesProj)

```
org.telegram.messenger/
├── MessagesController.java   # Central message handling and state
├── MessagesStorage.java      # SQLite database operations
├── NotificationCenter.java   # Event bus (observer pattern for all state changes)
├── UserConfig.java           # User settings and session
├── ApplicationLoader.java    # Application entry point
├── V2RayBootstrap.java       # Auto-starts v2rayNG before NekoX authorization
└── V2RayNekoBridge.java      # IPC bridge: sends commands to v2ray, receives callbacks

org.telegram.ui/
├── ConfigSubscrBotRequest.java  # UI: bot subscription settings + key request/receive
├── ProxyListActivity.java       # Telegram proxy settings UI
└── ConfigMTProxySubscr.java     # MTProto proxy subscription UI
```

### State Management

`NotificationCenter` is the event bus — all state changes flow through it. When modifying state, always post appropriate notifications so UI updates. `MessagesController` and `UserConfig` hold global application state.

### V2Ray Integration Architecture

Two separate v2ray modules exist:
- **`v2ray`** (library module): Source code, consumed by TMessagesProj_App as a dependency
- **`v2ray_standalone`** (application module): Reuses `v2ray/app/src/main/java` sources via `srcDirs`, adds its own `AndroidManifest.xml` with a launcher activity. Use this for standalone testing.

The v2ray process runs as `:RunSoLibV2RayDaemon` (separate Android process), allowing it to continue independently of NekoX lifecycle.

**Key V2Ray Files:**
- `v2ray/app/src/main/java/com/v2ray/ang/receiver/V2RayNGReceiver.kt`: IPC command handler
- `v2ray/app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt`: Profile management
- `v2ray/app/src/main/java/com/v2ray/ang/handler/MmkvManager.kt`: MMKV persistent storage
- `v2ray/app/src/main/java/com/v2ray/ang/fmt/`: Protocol parsers (VlessFmt, VmessFmt, TrojanFmt, etc.)

### IPC Protocol (NekoX ↔ v2ray_standalone)

Communication via package-scoped Android Broadcast Intents. `Intent.setPackage(context.packageName)` keeps broadcasts within the same application.

**Broadcast Actions:**
```
NekoX → v2ray:  "com.v2ray.ang.action.NEKOX_COMMAND"
v2ray → NekoX:  "com.v2ray.ang.action.NEKOX_RESPONSE"
```

**Command codes (EXTRA_COMMAND):**
```
CMD_TEST_KEYS   = 1001  (no extras required)
CMD_IMPORT_KEYS = 1002  (EXTRA_KEY1 = "vless://...", EXTRA_KEY2 = optional second key)
CMD_START_V2RAY = 1003
CMD_STOP_V2RAY  = 1004
```

**Response codes:**
```
RESP_TEST_RESULT   = 2001  (EXTRA_WORKING_COUNT, EXTRA_TOTAL_COUNT)
RESP_IMPORT_RESULT = 2002  (EXTRA_IMPORTED_COUNT, EXTRA_WORKING_COUNT, EXTRA_TOTAL_COUNT)
RESP_NO_V2RAY      = 2999  (v2ray_standalone not installed)
RESP_NO_ANSWER     = 2998  (IPC timeout — default 12 seconds in V2RayNekoBridge)
```

**Bot subscription key flow** (`ConfigSubscrBotRequest.java`):
1. User configures bot username (`SharedConfig.v2raySubscription1`) and request commands (`SharedConfig.v2rayRequest1` etc.)
2. User taps "Получить ключ по запросу X" → sends message to configured Telegram bot
3. `didReceivedNotification(NotificationCenter.didReceiveNewMessages)` detects bot reply
4. `parseVlessProfile()` extracts key (validates `vless://`, `vmess://`, `ss://`, `trojan://` prefixes)
5. Key saved to `SharedConfig.v2rayKey1` (or key11/12/13) and clipboard
6. `sendToV2Ray(key)` → `V2RayNekoBridge.sendImportKeysCommand()` → broadcasts `CMD_IMPORT_KEYS`
7. V2RayNGReceiver imports to MMKV storage, runs connectivity test, sends `RESP_IMPORT_RESULT`
8. User sees toast: "V2Ray: Imported 1, 1/1 working"

Bot response timeout: 60 seconds (in `ConfigSubscrBotRequest.java`)
IPC response timeout: 12 seconds (in `V2RayNekoBridge.java`)

**SharedConfig v2ray fields:**
```java
v2raySubscription1  // Telegram bot username (@bot)
v2rayRequest1/11/12/13  // Request commands sent to bot
v2rayKey1/11/12/13      // Received VPN keys
```

### Native Libraries (TMessagesProj/jni/)

Built via CMake (`jni/CMakeLists.txt`): tgnet (MTProto C++), ffmpeg, boringssl, voip (libtgvoip/libtgcalls), sqlite, rlottie, opus, mozjpeg.

## Debugging

```bash
# Full IPC trace
adb logcat -s "ConfigSubscrBotRequest" -s "V2RayNekoBridge" -s "V2RayNGReceiver" -s "ProfileImporter"

# App crashes
adb logcat -s "AndroidRuntime"

# Native crashes
adb logcat -s "DEBUG"
```

Port conflicts (v2ray): Ports 10808 and 10853 must be free.
Check: `adb shell netstat -tuln | grep -E '10808|10853'`

## Key Documentation Files

- `v2RayTgTeleRayAPIspecification.md`: Complete IPC protocol specification
- `v2ray_standalone/INTEGRATION.md`: Full bot subscription → key import workflow with code traces
- `v2ray_standalone/README.md`: v2ray_standalone module details and run configurations
- `QWEN.md`: Extended v2ray integration documentation and testing procedures
- `CurrentPlan.md`: Current development plan and feature work in progress

## Common Issues

1. **NDK version mismatch**: Must use exactly r21.4.7075529
2. **JDK version**: Requires JDK 17+
3. **v2ray_standalone minSdk**: Requires minSdk 24 (editorkit dependency), unlike main app (minSdk 21)
4. **IPC not working**: Verify both apps share package name (`com.v2ray.ang` for release, `com.v2ray.ang.debug` for debug builds)
5. **Missing google-services.json**: Debug builds fail without Firebase config
