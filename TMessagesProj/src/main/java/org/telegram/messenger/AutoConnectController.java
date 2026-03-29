/*
 * AutoConnectController — per-account singleton that owns the auto-connect loop.
 *
 * Survives SubscriptionsConfigFragment lifecycle: the loop keeps running when the
 * user navigates away. The fragment registers itself as UIDelegate while active;
 * when it leaves it calls setDelegate(null) without stopping the loop.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestTimeDelegate;
import org.telegram.tgnet.TLRPC;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoConnectController implements NotificationCenter.NotificationCenterDelegate {
    public static final int MESSAGE_LOAD_COUNT = 40;

    // ---- Singleton (per-account) ----

    private static volatile AutoConnectController[] instances =
            new AutoConnectController[UserConfig.MAX_ACCOUNT_COUNT];

    public static AutoConnectController getInstance(int account) {
        AutoConnectController local = instances[account];
        if (local == null) {
            synchronized (AutoConnectController.class) {
                local = instances[account];
                if (local == null) {
                    instances[account] = local = new AutoConnectController(account);
                }
            }
        }
        return local;
    }

    // ---- UIDelegate — implemented by the fragment while it is active ----

    public interface UIDelegate {
        void onAnswerUpdate(String text);
        void onTimerUpdate(String text);
        void onLoopStateChanged();
    }

    private UIDelegate uiDelegate;

    public void setDelegate(UIDelegate d) {
        uiDelegate = d;
    }

    // ---- Constants ----

    private static final long MIN_TIME_BETWEEN_REQUEST_TG = 4000L;
    private static final int  MAX_BOT_REQUESTS_PER_CYCLE  = 9;
    /** Safety watchdog: abort a stuck cycle after this many ms and schedule the next one. */
    private static final long MAX_CYCLE_DURATION_MS = 150_000L; // 2.5 minutes

    // ---- Core fields ----

    private final int     currentAccount;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private V2RayNekoBridge v2rayBridge;

    // Cached display strings — fragment reads these on resume to fill UI immediately
    private String v2rayAnswerText = "";
    private String v2rayTimerText  = "";

    // ---------- Main auto loop (v2ray + MTProxy) ----------
    private boolean       autoLoopRunning  = false;
    private boolean       autoLoopHadError = false;
    private CountDownTimer autoLoopTimer;
    private CountDownTimer cycleWatchdogTimer;       // counts down MAX_CYCLE_DURATION_MS; aborts hung cycles
    private int            cycleCount                = 0;  // incremented each testAllConnections() call
    private int            consecutiveNoConnectCycles = 0; // resets on success; triggers v2ray restart hint

    // ---------- MTProxy-only auto loop ----------
    private boolean        mtproxyLoopRunning = false;
    private CountDownTimer timerMtproxy;

    private Runnable autoTestChannelTimeoutRunnable;

    // Auto-test phase:
    // MODE 1 — finding connection (connectionFound=false):
    //   0=idle  1=waiting_v2ray_selectBestKey  3=testing_proxyList
    //   35=testing_stored_mtproxy(ConfigMTProxySubscr)  36=testing_cached_mtpbot_proxies(ConfigMtpBotRequest)
    //   37=testing_cached_channel_proxies(offline channel cache)
    // MODE 2 — accumulating reserves (connectionFound=true, requires Telegram connection):
    //   4=loading_channel_msgs  45=requesting_mtpbot_proxies  5=requesting_v2ray_bot_keys
    // 2=checking_connectivity(unused)  6=done
    private int autoTestPhase    = 0;
    private int autoTestPrePhase = 0; // 0=none, 1=waiting_status, 2=waiting_start

    private int     autoTestMTProxyIndex        = 0;
    private int     autoTestStoredRequestIndex  = 0;
    private boolean connectionFound             = false;
    // true when v2ray reported a working key this cycle; prevents enableProxy() from
    // overriding v2ray with MTProxy if a late proxy test succeeds after v2ray wins
    private boolean connectionFoundViaV2Ray     = false;
    // set when a full cycle ends with no connection; cleared at start of each new cycle
    private boolean flagNoConnect               = false;

    private int  autoTestSubIndex        = 0;
    private int  autoTestWorkingProxies  = 0;
    private long autoTestLoadingChatId   = 0;

    private int    autoTestBotCmdIndex      = 0;
    private int    autoTestV2RaySubIndex    = 0;
    private int    autoTestBotRequestCount  = 0;
    private long   autoTestWaitingBotId     = 0;
    private int    autoTestWorkingKeys      = 0;

    // Phase 45: MTP bot proxy requesting
    private int  autoTestMtpBotSubIndex    = 0;
    private int  autoTestMtpBotCmdIndex    = 0;
    private long autoTestMtpBotWaitingId   = 0;
    private int  autoTestMtpBotReqCount    = 0;
    private Runnable autoTestMtpBotTimeoutRunnable;
    private Runnable autoTestBotTimeoutRunnable;
    private Runnable autoTestBotShortTimeoutRunnable;
    // Serial numbers to invalidate stale checkProxy callbacks when a per-proxy timeout fires.
    // Without this, a slow checkProxy (e.g. TCP 75-sec OS timeout) would hang phase 35/36 until
    // the cycle watchdog fires (150s), then the next cycle always restarts at proxy 1.
    private int      autoTestStoredProxySerial  = 0;
    private int      autoTestCachedProxySerial  = 0;
    // True when SELECT_BEST_KEY was sent in response to a key import (phase 5 → 1 transition).
    // v2rayNG PATH B: it sends RESP(working=0) BEFORE starting VPN, so "0 working" doesn't mean
    // "no VPN" — it means the measurement threshold wasn't met. Trust the import and switch to v2ray.
    private boolean  autoTestV2RayJustImported  = false;
    // True while waiting for RESP_TEST_REAL_PING_DONE; set before sendTestRealPingCommand(),
    // cleared in onTestRealPingDone() or on reset.
    private boolean  autoTestV2RayPingPending   = false;
    private static final long PROXY_CHECK_TIMEOUT_MS = 8000L; // per-proxy safety cap
    private boolean  flagBadWorkMtProxy         = false;
    private boolean  manualSelectBestKeyPending = false; // set by sendSelectBestKeyForced(), shows diag on working=0
    private static final long MAX_TIME_WAIT_RESPONCE = 10000L;

    private String lastV2RayStatusText = "";

    private boolean isMTProxyActive() {
        return MessagesController.getGlobalMainSettings().getBoolean("proxy_enabled", false)
                && SharedConfig.currentProxy != null;
    }

    // ---- Constructor ----

    private AutoConnectController(int account) {
        this.currentAccount = account;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messagesDidLoad);
    }

    // ---- Public state accessors (read by fragment adapter) ----

    public boolean isAutoLoopRunning()    { return autoLoopRunning;  }
    public boolean isMtproxyLoopRunning() { return mtproxyLoopRunning; }
    public boolean hadError()             { return autoLoopHadError; }
    public String  getAnswerText()        { return v2rayAnswerText;  }
    public String  getTimerText()         { return v2rayTimerText;   }

    // ---- V2Ray bridge (idempotent init) ----

    public void ensureV2RayBridge() {
        if (v2rayBridge == null) {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) {
                v2rayBridge = V2RayNekoBridge.getInstance(ctx);
                v2rayBridge.initialize();
                setupV2RayListener();
            }
        }
    }

    // ---- V2Ray IPC listener → state machine ----

    private void setupV2RayListener() {
        v2rayBridge.setOnV2RayResponseListener(new V2RayNekoBridge.OnV2RayResponseListener() {
            @Override
            public void onTestResult(int working, int total) {
                updateAnswer("v2ray ключей: " + working + "/" + total);
            }

            @Override
            public void onSelectBestKeyResult(int working, String selectedGuid, long pingMs) {
                autoTestWorkingKeys = working;
                if (working > 0 && selectedGuid != null && !selectedGuid.isEmpty()) {
                    // v2ray found a working key — switch to it regardless of current phase.
                    // Response may arrive late (background TCP tests take N×3s) while TeleRay
                    // has already moved to MTProxy testing after a 55s timeout.
                    manualSelectBestKeyPending = false;
                    lastV2RayStatusText = "v2ray: лучший ключ выбран, ping " + pingMs + "мс (" + working + " рабочих)";
                    updateAnswer("v2ray работает! VPN подключён.\n" + lastV2RayStatusText);
                    connectionFound = true;
                    connectionFoundViaV2Ray = true;
                    flagNoConnect = false;
                    disableAllProxies();
                    ConnectionsManager.getInstance(currentAccount).checkConnection();
                    showToast("v2ray работает. Прокси Telegram отключены.");
                    // Cancel any ongoing phase (MTProxy testing, channel loading, bot requests)
                    if (autoTestChannelTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestChannelTimeoutRunnable);
                        autoTestChannelTimeoutRunnable = null;
                    }
                    if (autoTestBotTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestBotTimeoutRunnable);
                        autoTestBotTimeoutRunnable = null;
                    }
                    if (autoTestMtpBotTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestMtpBotTimeoutRunnable);
                        autoTestMtpBotTimeoutRunnable = null;
                    }
                    // Don't go directly to scheduleNextCycle() — call proceedToReserve() first.
                    // When v2ray is the connection, phases 4/45 are never triggered otherwise,
                    // so mtproxyRequestArrays and mtpBotProxyArrays never get fresh URLs.
                    // proceedToReserve() will run phase 4 (channels) + 45 (bots) only if
                    // proxyList.size() < autoMinWrkMTProxy, otherwise falls through to phase 6.
                    proceedToReserve();
                } else if (autoTestV2RayJustImported) {
                    // Post-import probe returned working=0, but v2rayNG PATH B always starts VPN
                    // regardless of measurement threshold (RESP fires BEFORE VPN is up). Trust the
                    // import; the next cycle will verify actual connectivity.
                    autoTestV2RayJustImported = false;
                    manualSelectBestKeyPending = false;
                    lastV2RayStatusText = "v2ray: запущен с новыми ключами (проверка в след. цикле)";
                    updateAnswer("v2ray запущен с новыми ключами. Отключаю прокси...");
                    connectionFound = true;
                    connectionFoundViaV2Ray = true;
                    flagNoConnect = false;
                    disableAllProxies();
                    ConnectionsManager.getInstance(currentAccount).checkConnection();
                    showToast("v2ray запущен с новыми ключами.");
                    if (autoTestChannelTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestChannelTimeoutRunnable);
                        autoTestChannelTimeoutRunnable = null;
                    }
                    if (autoTestBotTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestBotTimeoutRunnable);
                        autoTestBotTimeoutRunnable = null;
                    }
                    if (autoTestMtpBotTimeoutRunnable != null) {
                        handler.removeCallbacks(autoTestMtpBotTimeoutRunnable);
                        autoTestMtpBotTimeoutRunnable = null;
                    }
                    proceedToReserve();
                } else if (autoTestPhase == 1) {
                    // No working keys, specifically waiting for v2ray → fall back to MTProxy
                    lastV2RayStatusText = "v2ray: рабочих ключей нет";
                    updateAnswer(lastV2RayStatusText + "\nПроверяем MTProxy...");
                    manualSelectBestKeyPending = false;
                    testAllmtProxy();
                } else if (manualSelectBestKeyPending) {
                    // Manual test button: PATH B sends working=0 BEFORE starting VPN — the
                    // indicator in v2rayTg will still move to a key. Show neutral message.
                    manualSelectBestKeyPending = false;
                    String diag = selectedGuid != null && !selectedGuid.isEmpty()
                            ? "v2ray: ключ " + selectedGuid.substring(0, Math.min(8, selectedGuid.length())) + "… запущен (ping: н/д)"
                            : "v2ray: VPN запускается... (проверьте полоску в v2rayTg)";
                    updateAnswer(diag);
                    showToast(diag);
                }
                // else: late response with working=0 while already in another phase — ignore
            }

            @Override
            public void onImportResult(int imported, int working, int total) {
                if (autoTestPhase == 5) {
                    autoTestWorkingKeys = working;
                    if (imported > 0) {
                        // Keys were imported — schedule SELECT_BEST_KEY to switch to v2ray.
                        // Set autoTestV2RayJustImported so onSelectBestKeyResult knows this is a
                        // post-import probe and must NOT fall back to MTProxy on working=0:
                        // v2rayNG PATH B sends RESP(working=0) BEFORE it starts the VPN, so "0
                        // working" just means the ping threshold wasn't met, not that VPN failed.
                        autoTestV2RayJustImported = true;
                        autoTestPhase = 1;
                        if (working >= 1) {
                            updateAnswer("v2ray: " + working + " ключ(ей) импортировано. Тестирую...");
                            v2rayBridge.sendDedupProfilesCommand();
                            autoTestV2RayPingPending = true;
                            v2rayBridge.sendTestRealPingCommand();
                        } else {
                            // working=0 at import time — v2ray may still be measuring.
                            // Give it 3s then probe.
                            updateAnswer("Ключи импортированы, жду тестирования v2ray...");
                            handler.postDelayed(() -> {
                                if (autoTestPhase == 1 && autoTestV2RayJustImported) {
                                    v2rayBridge.sendDedupProfilesCommand();
                                    autoTestV2RayPingPending = true;
                                    v2rayBridge.sendTestRealPingCommand();
                                }
                            }, 3000);
                        }
                    } else {
                        // Nothing imported (e.g. parse error) — move to next bot command.
                        autoTestBotCmdIndex++;
                        handler.postDelayed(AutoConnectController.this::autoTestNextBotCmd,
                                MIN_TIME_BETWEEN_REQUEST_TG);
                    }
                } else if (imported > 0) {
                    // Re-push at cycle start (phase 0/1): only log if something actually changed.
                    updateAnswer("v2ray: повторный импорт " + imported + " кл. (" + working + "/" + total + " рабочих)");
                }
                // imported==0 at cycle-start re-push (rePushCachedV2RayKeys) — ignore silently
            }

            @Override
            public void onError(String error) {
                if (autoTestPhase == 1) {
                    testAllmtProxy();
                } else {
                    updateAnswer("Error: " + (error != null ? error : "unknown"));
                }
            }

            @Override
            public void onNoV2Ray() {
                if (autoTestPhase == 1) {
                    showToast("v2ray недоступен, проверяем MTProxy");
                    testAllmtProxy();
                } else {
                    updateAnswer(LocaleController.getString("V2RayNoV2Ray",
                            com.teleray.messenger.R.string.V2RayNoV2Ray));
                }
            }

            @Override
            public void onNoAnswer() {
                if (autoTestPrePhase == 1) {
                    autoTestPrePhase = 2;
                    lastV2RayStatusText = "v2ray: нет ответа (завис?)";
                    updateAnswer("Запускаю v2ray...\n" + lastV2RayStatusText);
                    v2rayBridge.sendStartV2RayCommand();
                    return;
                }
                if (autoTestPrePhase == 2) {
                    autoTestPrePhase = 0;
                    lastV2RayStatusText = "v2ray: не запустился (timeout)";
                    showToast("v2ray не запустился (timeout)");
                    updateAnswer(lastV2RayStatusText + "\nПроверяем MTProxy...");
                    testAllmtProxy();
                    return;
                }
                if (autoTestPhase == 1) {
                    autoTestV2RayPingPending = false;
                    testAllmtProxy();
                } else if (autoTestPhase == 5) {
                    autoTestBotCmdIndex++;
                    autoTestNextBotCmd();
                } else {
                    autoTestV2RayPingPending = false;
                    updateAnswer(LocaleController.getString("V2RayNoAnswer",
                            com.teleray.messenger.R.string.V2RayNoAnswer));
                }
            }

            @Override
            public void onStatusResult(boolean isRunning, long lastPingMs, String errorMsg) {
                if (isRunning) {
                    lastV2RayStatusText = "v2ray: работает" +
                            (lastPingMs > 0 ? ", ping: " + lastPingMs + "мс" : "");
                } else {
                    lastV2RayStatusText = "v2ray: не запущен" +
                            (errorMsg != null ? " (" + errorMsg + ")" : "");
                }
                if (autoTestPrePhase == 1) {
                    autoTestPrePhase = 0;
                    autoTestPhase = 1;
                    // Do NOT set autoTestV2RayJustImported here — same reason as checkForeignConnectivity.
                    // PATH C uses fresh stored pings; if working=0, fall through to MTProxy.
                    if (isRunning) {
                        updateAnswer("Выбираю лучший v2ray ключ...\n" + lastV2RayStatusText);
                    } else {
                        // v2ray not running — skip START attempt, go directly to SELECT_BEST_KEY.
                        // SELECT_BEST_KEY PATH B handles the "not running" case by doing real
                        // measurements and starting v2ray with the best key found.
                        updateAnswer("v2ray не запущен. Выбираю лучший ключ...\n" + lastV2RayStatusText);
                    }
                    v2rayBridge.sendDedupProfilesCommand();
                    autoTestV2RayPingPending = true;
                    v2rayBridge.sendTestRealPingCommand();
                }
            }

            @Override
            public void onStarted(boolean success, String errorMsg) {
                if (autoTestPrePhase == 2) {
                    autoTestPrePhase = 0;
                    if (success) {
                        lastV2RayStatusText = "v2ray: запущен";
                        autoTestPhase = 1;
                        updateAnswer("Тестирую v2ray ключи...\n" + lastV2RayStatusText);
                        v2rayBridge.sendDedupProfilesCommand();
                        autoTestV2RayPingPending = true;
                        v2rayBridge.sendTestRealPingCommand();
                    } else {
                        String err = errorMsg != null ? errorMsg : "ошибка запуска";
                        lastV2RayStatusText = "v2ray: не запустился (" + err + ")";
                        showToast("v2ray не запустился: " + err);
                        updateAnswer(lastV2RayStatusText + "\nПроверяем MTProxy...");
                        testAllmtProxy();
                    }
                } else {
                    lastV2RayStatusText = success ? "v2ray: запущен" : "v2ray: не запустился";
                }
            }

            @Override
            public void onTestRealPingDone(int working, int total) {
                if (!autoTestV2RayPingPending) return;
                autoTestV2RayPingPending = false;
                if (total == 0) {
                    // v2ray has no profiles at all — PATH C will find nothing.
                    // Fall through to MTProxy immediately.
                    if (autoTestPhase == 1) {
                        updateAnswer("v2ray: нет профилей. Проверяю MTProxy...");
                        testAllmtProxy();
                    }
                    // For manual button with 0 profiles: nothing to show, just clear flag.
                    manualSelectBestKeyPending = false;
                    return;
                }
                updateAnswer("Пинг v2ray: " + working + "/" + total + " рабочих. Сортирую...");
                v2rayBridge.sendSortByTestCommand();
                handler.postDelayed(() -> {
                    if (autoTestPhase == 1 || manualSelectBestKeyPending) {
                        v2rayBridge.sendSelectBestKeyCommand(true); // PATH C: trustStored=true
                    }
                }, 500);
            }
        });
    }

    // ================================================================
    // Main auto loop
    // ================================================================

    public void startLoopTstConnect() {
        if (mtproxyLoopRunning) {
            mtproxyLoopRunning = false;
            if (timerMtproxy != null) { timerMtproxy.cancel(); timerMtproxy = null; }
        }
        autoLoopRunning  = true;
        autoLoopHadError = false;
        cycleCount = 0;
        consecutiveNoConnectCycles = 0;
        updateTimer("Авторежим запущен...");
        updateAnswer("Запуск первой проверки...");
        if (uiDelegate != null) uiDelegate.onLoopStateChanged();
        testAllConnections();
    }

    public void stopLoopTstConnect() {
        autoLoopRunning = false;
        if (autoLoopTimer != null) { autoLoopTimer.cancel(); autoLoopTimer = null; }
        if (cycleWatchdogTimer != null) { cycleWatchdogTimer.cancel(); cycleWatchdogTimer = null; }
        if (mtproxyLoopRunning) {
            mtproxyLoopRunning = false;
            if (timerMtproxy != null) { timerMtproxy.cancel(); timerMtproxy = null; }
        }
        autoTestPhase    = 0;
        autoTestPrePhase = 0;
        if (autoTestBotTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestBotTimeoutRunnable);
            autoTestBotTimeoutRunnable = null;
        }
        if (autoTestChannelTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestChannelTimeoutRunnable);
            autoTestChannelTimeoutRunnable = null;
        }
        updateTimer("");
        updateAnswer("Авторежим остановлен.");
        if (uiDelegate != null) uiDelegate.onLoopStateChanged();
    }

    private void scheduleNextCycle() {
        if (!autoLoopRunning) return;
        if (cycleWatchdogTimer != null) { cycleWatchdogTimer.cancel(); cycleWatchdogTimer = null; }
        if (autoLoopTimer != null) { autoLoopTimer.cancel(); autoLoopTimer = null; }
        long ms = (long) SharedConfig.autoTimeTstMinute * 60L * 1000L;
        // Faster retry when no connection (first 3 failed cycles → 1/3 interval)
        if (consecutiveNoConnectCycles > 0 && consecutiveNoConnectCycles <= 3) {
            ms = Math.max(ms / 3, 30_000L);
        }
        autoLoopTimer = new CountDownTimer(ms, 1000) {
            @Override public void onTick(long msLeft) {
                int s = (int)(msLeft / 1000);
                updateTimer("Следующая проверка через: " + (s / 60) + "м " + (s % 60) + "с");
            }
            @Override public void onFinish() {
                updateTimer("Запускаю проверку...");
                if (autoLoopRunning) testAllConnections();
            }
        };
        autoLoopTimer.start();
    }

    // ================================================================
    // MTProxy-only auto loop
    // ================================================================

    public void startMtproxyLoop() {
        mtproxyLoopRunning = true;
        updateTimer("MTProxy авторежим запущен...");
        testAllmtProxy();
        if (uiDelegate != null) uiDelegate.onLoopStateChanged();
    }

    public void stopMtproxyLoop() {
        mtproxyLoopRunning = false;
        if (timerMtproxy != null) { timerMtproxy.cancel(); timerMtproxy = null; }
        if (autoTestChannelTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestChannelTimeoutRunnable);
            autoTestChannelTimeoutRunnable = null;
        }
        updateTimer("");
        updateAnswer("MTProxy авторежим остановлен.");
        if (uiDelegate != null) uiDelegate.onLoopStateChanged();
    }

    private void scheduleNextMtproxyCycle() {
        if (!mtproxyLoopRunning) return;
        if (timerMtproxy != null) { timerMtproxy.cancel(); timerMtproxy = null; }
        long ms = (long) SharedConfig.autoTimeTstMinute * 60L * 1000L;
        timerMtproxy = new CountDownTimer(ms, 1000) {
            @Override public void onTick(long msLeft) {
                int s = (int)(msLeft / 1000);
                updateTimer("MTProxy: след. проверка через " + (s / 60) + "м " + (s % 60) + "с");
            }
            @Override public void onFinish() {
                updateTimer("MTProxy: запускаю проверку...");
                if (mtproxyLoopRunning) testAllmtProxy();
            }
        };
        timerMtproxy.start();
    }

    /**
     * Start (or restart) the per-cycle safety watchdog.
     * Counts down MAX_CYCLE_DURATION_MS, updating the timer row every second.
     * If the cycle hasn't called scheduleNextCycle() before expiry, the watchdog
     * cancels all pending phase callbacks and forces the next cycle to start.
     */
    private void startCycleWatchdog() {
        if (cycleWatchdogTimer != null) { cycleWatchdogTimer.cancel(); cycleWatchdogTimer = null; }
        final int snapshot = cycleCount;
        cycleWatchdogTimer = new CountDownTimer(MAX_CYCLE_DURATION_MS, 1000) {
            @Override public void onTick(long msLeft) {
                int s = (int)(msLeft / 1000);
                updateTimer("Цикл #" + snapshot + ": ~" + s + "с");
            }
            @Override public void onFinish() {
                cycleWatchdogTimer = null;
                if (!autoLoopRunning) return;
                updateTimer("Цикл #" + snapshot + ": завис — принудит. рестарт");
                showToast("Авто-цикл завис. Перезапускаю...");
                if (autoTestChannelTimeoutRunnable != null) {
                    handler.removeCallbacks(autoTestChannelTimeoutRunnable);
                    autoTestChannelTimeoutRunnable = null;
                }
                if (autoTestBotTimeoutRunnable != null) {
                    handler.removeCallbacks(autoTestBotTimeoutRunnable);
                    autoTestBotTimeoutRunnable = null;
                }
                if (autoTestBotShortTimeoutRunnable != null) {
                    handler.removeCallbacks(autoTestBotShortTimeoutRunnable);
                    autoTestBotShortTimeoutRunnable = null;
                }
                if (autoTestMtpBotTimeoutRunnable != null) {
                    handler.removeCallbacks(autoTestMtpBotTimeoutRunnable);
                    autoTestMtpBotTimeoutRunnable = null;
                }
                autoTestPhase    = 6;
                autoTestPrePhase = 0;
                scheduleNextCycle();
            }
        };
        cycleWatchdogTimer.start();
    }

    // ================================================================
    // testAllConnections
    // ================================================================

    /**
     * Trigger best-key selection manually (e.g. from the test button in ConfigSubscrBotRequest).
     * Sends CMD_SELECT_BEST_KEY directly — PATH A in v2rayNG will stop VPN and fall through
     * to PATH B real measurements if no good stored-delay alternative exists.
     */
    public void sendSelectBestKeyForced() {
        ensureV2RayBridge();
        manualSelectBestKeyPending = true;
        showToast("Тестирую v2ray ключи...");
        updateAnswer("Тестирую v2ray ключи (ручной тест)...");
        v2rayBridge.sendDedupProfilesCommand();
        autoTestV2RayPingPending = true;
        v2rayBridge.sendTestRealPingCommand();
    }

    /**
     * Fire-and-forget: import all non-empty cached keys from v2rayKeyArrays into v2ray.
     * Called at the start of every auto-cycle so that v2ray always has the most recently
     * received keys even if its MMKV profile store was cleared since last run.
     */
    private void rePushCachedV2RayKeys() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < SharedConfig.v2raySubscriptionsCount; s++) {
            for (int i = 0; i < SharedConfig.v2rayRequestCounts[s]; i++) {
                String key = SharedConfig.v2rayKeyArrays[s][i];
                if (!TextUtils.isEmpty(key)) {
                    String trimmed = key.trim();
                    if (trimmed.length() > 0) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(trimmed);
                    }
                }
            }
        }
        if (sb.length() > 0) {
            v2rayBridge.sendImportKeysCommand(sb.toString(), "");
        }
    }

    public void testAllConnections() {
        try {
            ensureV2RayBridge();
            cycleCount++;
            startCycleWatchdog();
            // Re-push all cached keys into v2ray before dedup/select so that v2ray has them
            // even if its MMKV profile store was cleared (process restart, app data wipe, etc.)
            rePushCachedV2RayKeys();
            // Dedup v2ray profiles at start of each cycle (fire-and-forget)
            v2rayBridge.sendDedupProfilesCommand();
            autoTestPhase           = 0;
            autoTestPrePhase        = 0;
            autoTestWorkingProxies  = 0;
            autoTestWorkingKeys     = 0;
            autoTestSubIndex        = 0;
            autoTestBotCmdIndex     = 0;
            autoTestV2RaySubIndex   = 0;
            autoTestBotRequestCount = 0;
            autoTestWaitingBotId    = 0;
            autoTestStoredRequestIndex = 0;
            connectionFound             = false;
            connectionFoundViaV2Ray     = false;
            flagBadWorkMtProxy          = false;
            autoTestV2RayJustImported   = false;
            autoTestV2RayPingPending    = false;

            // 1.5: previous cycle had no connection.
            // Always reset flagNoConnect and re-try v2ray first: v2rayTg may have been
            // restarted since the last cycle and could now have working keys.
            if (flagNoConnect) {
                flagNoConnect = false;
                updateAnswer("Прошлый цикл: нет коннекта. Переподключаюсь к v2ray...");
                // fall through to normal flow (disableAllProxies + checkForeignConnectivity)
            }

            updateAnswer("Проверяю прямое соединение...");
            disableAllProxies();
            checkForeignConnectivity();
        } catch (Exception e) {
            stopLoopWithError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    // ================================================================
    // testAllmtProxy — entry point for full MTProxy cycle
    // ================================================================

    /** Phase 3 (proxyList) → Phase 3.5 (stored) → Phase 4 (channel reserve). */
    private void testAllmtProxy() {
        if (connectionFoundViaV2Ray) {
            // v2ray confirmed working this cycle — skip MTProxy, go to reserve accumulation
            proceedToReserve();
            return;
        }
        autoTestMTProxyIndex       = 0;
        autoTestSubIndex           = 0;
        autoTestStoredRequestIndex = 0;
        autoTestWorkingProxies     = 0;
        autoTestPhase              = 3;
        connectionFoundViaV2Ray    = false;
        updateAnswer("Тестирую MTProxy...");
        testMTProxiesSequentially();
    }

    // ================================================================
    // Phase 0/2: direct / v2ray connectivity check
    // ================================================================

    private void checkForeignConnectivity() {
//        if (autoTestPhase == 0) updateAnswer("Проверяю прямое соединение...");
//        else updateAnswer("Проверяю доступность зарубежного интернета...");
        updateAnswer("Проверяю соединение через MTProxy...");
        new Thread(() -> {
            boolean ok = false;
//            for (String host : new String[]{"https://api.telegram.org"}) {
//                try {
//                    HttpURLConnection conn = (HttpURLConnection) new URL(host).openConnection();
//                    conn.setConnectTimeout(4000);
//                    conn.setReadTimeout(4000);
//                    conn.setRequestMethod("HEAD");
//                    int code = conn.getResponseCode();
//                    conn.disconnect();
//                    if (code >= 200 && code < 400) { ok = true; break; }
//                } catch (Exception ignored) {}
//            }
            final boolean accessible = ok;
            handler.post(() -> {
                if (autoTestPhase == 0) {
                    if (accessible) {
                        connectionFound = true;
                        showToast("Прямое соединение OK");
                        proceedToReserve();
                    } else {
                        if (v2rayBridge == null || !v2rayBridge.isV2RayReceiverRegistered()) {
                            testAllmtProxy();
                        } else {
                            // Skip STATUS_CHECK → START chain: v2ray may be busy processing
                            // IMPORT from rePushCachedV2RayKeys and won't respond to STATUS_CHECK
                            // in time, causing the prePhase=1→2 timeout fallback to MTProxy.
                            // SELECT_BEST_KEY PATH B handles both running and non-running v2ray —
                            // it tests all profiles and starts VPN with the best one found.
                            autoTestPhase = 1;
                            // Do NOT set autoTestV2RayJustImported here — that flag is only for
                            // post-bot-import probes where PATH B fires RESP before VPN is up.
                            // In cycle-start, if PATH C returns working=0 we must fall through
                            // to testAllmtProxy(), not trust v2ray.
                            updateAnswer("Прямого соединения нет. Тестирую v2ray ключи...");
                            v2rayBridge.sendDedupProfilesCommand();
                            autoTestV2RayPingPending = true;
                            v2rayBridge.sendTestRealPingCommand();
                        }
                    }
                    return;
                }
                if (autoTestPhase != 2) return;
                if (accessible) {
                    connectionFound = true;
                    disableAllProxies();
                    ConnectionsManager.getInstance(currentAccount).checkConnection();
                    showToast("v2ray работает. Прокси Telegram отключены.");
                    proceedToReserve();
                } else {
                    // 1.5: If we are here, it means we tried to verify v2ray connectivity but it failed (or check code is disabled).
                    // But according to user request, we should trust v2ray if it has working keys.
                    if (autoTestWorkingKeys > 0) {
                        connectionFound = true;
                        disableAllProxies();
                        ConnectionsManager.getInstance(currentAccount).checkConnection();
                        showToast("v2ray работает. Прокси Telegram отключены.");
                        flagNoConnect = false;
                        proceedToReserve(); // accumulate MTProxy reserves while connected
                    } else {
                        showToast("v2ray не обеспечивает соединение. Проверяем MTProxy...");
                        testAllmtProxy();
                    }
                }
            });
        }).start();
    }

    // ================================================================
    // Phase 3: test proxyList sequentially
    // ================================================================

    private void testMTProxiesSequentially() {
        SharedConfig.loadProxyList();
        if (SharedConfig.proxyList.isEmpty() || autoTestMTProxyIndex >= SharedConfig.proxyList.size()) {
            testStoredMTProxies();
            return;
        }
        SharedConfig.ProxyInfo proxy = SharedConfig.proxyList.get(autoTestMTProxyIndex);
        updateAnswer("Тестирую MTProxy " + (autoTestMTProxyIndex + 1) + "/"
                + SharedConfig.proxyList.size() + " — " + proxy.address);
        proxy.proxyCheckPingId = ConnectionsManager.getInstance(currentAccount).checkProxy(
                proxy.address, proxy.port,
                proxy.username != null ? proxy.username : "",
                proxy.password != null ? proxy.password : "",
                proxy.secret   != null ? proxy.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (autoTestPhase != 3) return;
                    if (time > 0 && time < 5000) {
                        if (flagBadWorkMtProxy && proxy.isSame(SharedConfig.currentProxy)) {
                            autoTestMTProxyIndex++;
                            testMTProxiesSequentially();
                            return;
                        }
                        connectionFound = true;
                        if (!connectionFoundViaV2Ray) {
                            enableProxy(proxy);
                            showToast("Включён MTProxy: " + proxy.address + " (" + time + "ms)");
                        }
                        proceedToReserve();
                    } else {
                        autoTestMTProxyIndex++;
                        testMTProxiesSequentially();
                    }
                }));
    }

    // ================================================================
    // Phase 3.5: test stored mtproxyRequestArrays
    // ================================================================

    private void testStoredMTProxies() {
        autoTestPhase              = 35;
        autoTestSubIndex           = 0;
        autoTestStoredRequestIndex = 0;
        updateAnswer("Тестирую сохранённые MTProxy...");
        testNextStoredMTProxy();
    }

    private void testNextStoredMTProxy() {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestSubIndex >= SharedConfig.mtproxySubscriptionsCount) {
            // Phase 35 exhausted — count how many are stored for the diagnostic message
            int storedTotal = 0;
            for (int s = 0; s < SharedConfig.mtproxySubscriptionsCount; s++) {
                for (int i = 0; i < 14; i++) {
                    if (!TextUtils.isEmpty(SharedConfig.mtproxyRequestArrays[s][i])) storedTotal++;
                }
            }
            if (storedTotal > 0) {
                updateAnswer("Сохр. MTProxy: " + storedTotal + " шт. — нет рабочих. Ищу в кеше ботов...");
            }
            testCachedMtpBotProxies();
            return;
        }
        String proxyUrl = SharedConfig.mtproxyRequestArrays[autoTestSubIndex][autoTestStoredRequestIndex];
        if (TextUtils.isEmpty(proxyUrl)) { advanceStoredMTProxy(); return; }
        SharedConfig.ProxyInfo info = parseMTProxyToInfo(proxyUrl);
        if (info == null) { advanceStoredMTProxy(); return; }
        updateAnswer("Тестирую сохр. прокси " + (autoTestStoredRequestIndex + 1)
                + " (подп. " + (autoTestSubIndex + 1) + ")...");
        // Per-proxy timeout: if checkProxy callback is delayed past PROXY_CHECK_TIMEOUT_MS
        // (e.g. due to Android TCP timeout of up to 75s), advance without waiting for the
        // callback. The serial ensures the late callback is ignored once we've moved on.
        final int serial = ++autoTestStoredProxySerial;
        handler.postDelayed(() -> {
            if (autoTestPhase == 35 && autoTestStoredProxySerial == serial) {
                advanceStoredMTProxy();
            }
        }, PROXY_CHECK_TIMEOUT_MS);
        ConnectionsManager.getInstance(currentAccount).checkProxy(
                info.address, info.port,
                info.username != null ? info.username : "",
                info.password != null ? info.password : "",
                info.secret   != null ? info.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (autoTestPhase != 35 || autoTestStoredProxySerial != serial) return;
                    if (time > 0 && time < 5000) {
                        if (flagBadWorkMtProxy && info.isSame(SharedConfig.currentProxy)) {
                            advanceStoredMTProxy();
                            return;
                        }
                        connectionFound = true;
                        SharedConfig.addProxy(info);
                        SharedConfig.saveProxyList();
                        if (!connectionFoundViaV2Ray) {
                            enableProxy(info);
                            showToast("Включён сохр. MTProxy: " + info.address + " (" + time + "ms)");
                        }
                        proceedToReserve();
                    } else {
                        advanceStoredMTProxy();
                    }
                }));
    }

    private void advanceStoredMTProxy() {
        autoTestStoredRequestIndex++;
        if (autoTestStoredRequestIndex >= 14) {
            autoTestStoredRequestIndex = 0;
            autoTestSubIndex++;
        }
        testNextStoredMTProxy();
    }

    // ================================================================
    // Phase 36: test cached mtpBotProxyArrays (MODE 1 — no connection)
    // These are proxy URLs received earlier from ConfigMtpBotRequest bots,
    // stored persistently. No network request to Telegram needed — just ping.
    // ================================================================

    private void testCachedMtpBotProxies() {
        autoTestPhase          = 36;
        autoTestMtpBotSubIndex = 0;
        autoTestMtpBotCmdIndex = 0;
        updateAnswer("Тестирую кешированные прокси ботов...");
        testNextCachedMtpBotProxy();
    }

    private void testNextCachedMtpBotProxy() {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestPhase != 36) return;
        if (autoTestMtpBotSubIndex >= SharedConfig.mtpBotSubscriptionsCount) {
            if (!connectionFound) {
                loadCachedChannelProxies();
            } else {
                proceedToReserve();
            }
            return;
        }
        if (autoTestMtpBotCmdIndex >= SharedConfig.mtpBotRequestCounts[autoTestMtpBotSubIndex]) {
            autoTestMtpBotSubIndex++;
            autoTestMtpBotCmdIndex = 0;
            testNextCachedMtpBotProxy();
            return;
        }
        String proxyUrl = SharedConfig.mtpBotProxyArrays[autoTestMtpBotSubIndex][autoTestMtpBotCmdIndex];
        if (TextUtils.isEmpty(proxyUrl)) {
            autoTestMtpBotCmdIndex++;
            testNextCachedMtpBotProxy();
            return;
        }
        SharedConfig.ProxyInfo info = parseMTProxyToInfo(proxyUrl);
        if (info == null) {
            autoTestMtpBotCmdIndex++;
            testNextCachedMtpBotProxy();
            return;
        }
        updateAnswer("Тестирую кеш. прокси бота " + (autoTestMtpBotCmdIndex + 1)
                + " (подп. " + (autoTestMtpBotSubIndex + 1) + ")...");
        final int serial = ++autoTestCachedProxySerial;
        handler.postDelayed(() -> {
            if (autoTestPhase == 36 && autoTestCachedProxySerial == serial) {
                autoTestMtpBotCmdIndex++;
                testNextCachedMtpBotProxy();
            }
        }, PROXY_CHECK_TIMEOUT_MS);
        ConnectionsManager.getInstance(currentAccount).checkProxy(
                info.address, info.port,
                info.username != null ? info.username : "",
                info.password != null ? info.password : "",
                info.secret   != null ? info.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (autoTestPhase != 36 || autoTestCachedProxySerial != serial) return;
                    if (time > 0 && time < 5000) {
                        if (flagBadWorkMtProxy && info.isSame(SharedConfig.currentProxy)) {
                            autoTestMtpBotCmdIndex++;
                            testNextCachedMtpBotProxy();
                            return;
                        }
                        connectionFound = true;
                        SharedConfig.addProxy(info);
                        SharedConfig.saveProxyList();
                        if (!connectionFoundViaV2Ray) {
                            enableProxy(info);
                            showToast("Включён кеш. прокси бота: " + info.address + " (" + time + "мс)");
                        }
                        proceedToReserve();
                    } else {
                        autoTestMtpBotCmdIndex++;
                        testNextCachedMtpBotProxy();
                    }
                }));
    }

    // ================================================================
    // Phase 37: read locally cached channel messages for MTProxy (offline)
    // ================================================================

    private void loadCachedChannelProxies() {
        autoTestPhase    = 37;
        autoTestSubIndex = 0;
        updateAnswer("Ищу прокси в кеше каналов...");
        loadNextCachedChannel();
    }

    private void loadNextCachedChannel() {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestSubIndex >= SharedConfig.mtproxySubscriptionsCount) {
            proceedToReserve();
            return;
        }
        long chatId = SharedConfig.mtproxySubscriptionChatIds[autoTestSubIndex];
        if (chatId == 0) {
            autoTestSubIndex++;
            loadNextCachedChannel();
            return;
        }
        autoTestLoadingChatId = chatId;
        updateAnswer("Читаю кеш канала MTProxy #" + (autoTestSubIndex + 1) + "...");
        int classGuid = ConnectionsManager.generateClassGuid();
        MessagesController.getInstance(currentAccount).loadMessages(
                -chatId, 0, false, MESSAGE_LOAD_COUNT, 0, 0, false, 0, classGuid, 0, 0, 0, 0, 0, 0, false);
        if (autoTestChannelTimeoutRunnable != null) handler.removeCallbacks(autoTestChannelTimeoutRunnable);
        autoTestChannelTimeoutRunnable = () -> {
            if (autoTestPhase == 37) { autoTestSubIndex++; loadNextCachedChannel(); }
        };
        handler.postDelayed(autoTestChannelTimeoutRunnable, 10000);
    }

    /** Route to correct channel-loader based on current phase (4=reserve, 37=offline). */
    private void advanceToNextChannel() {
        if (autoTestPhase == 4) loadChannelMessagesForReserve();
        else loadNextCachedChannel();
    }

    // ================================================================
    // Phase 4: channel messages → new proxies (reserve, MODE 2 only)
    // ================================================================

    private void proceedToReserve() {
        SharedConfig.loadProxyList();

        // ── MODE 1: no connection ──────────────────────────────────────────────
        // Do NOT try channel loading or bot requests — they all require Telegram
        // to be reachable. Just report failure and wait for next cycle.
        if (!connectionFound) {
            finishReservePhase();
            return;
        }

        // ── MODE 2: has connection — accumulate reserves ───────────────────────
        boolean needMoreProxies = SharedConfig.proxyList.size() < SharedConfig.autoMinWrkMTProxy;
        boolean needMoreKeys    = autoLoopRunning
                && autoTestWorkingKeys < SharedConfig.autoMinWrkKeys
                && autoTestPhase != 5;

        if (!needMoreProxies && !needMoreKeys) {
            // Everything is fine
            autoTestPhase = 6;
            flagNoConnect = false;
            showAutoTestDone("Соединение есть. Резерв в норме!");
            scheduleNextCycle();
            return;
        }

        // Need more proxies → load from channels, then mtpbot bots
        if (needMoreProxies) {
            autoTestPhase          = 4;
            autoTestSubIndex       = 0;
            autoTestWorkingProxies = SharedConfig.proxyList.size();
            loadChannelMessagesForReserve();
            return;
        }

        // Enough proxies but need more v2ray keys
        autoTestPhase       = 5;
        autoTestBotCmdIndex = 0;
        handler.postDelayed(this::autoTestNextBotCmd, 500);
    }

    private void loadChannelMessagesForReserve() {
        if (autoTestWorkingProxies >= SharedConfig.autoMinWrkMTProxy
                || autoTestSubIndex >= SharedConfig.mtproxySubscriptionsCount) {
            // Phase 4 done — try mtpbot reserve if still need more proxies
            proceedToMtpBotReserve();
            return;
        }
        long chatId = SharedConfig.mtproxySubscriptionChatIds[autoTestSubIndex];
        if (chatId == 0) {
            autoTestSubIndex++;
            loadChannelMessagesForReserve();
            return;
        }
        autoTestLoadingChatId = chatId;
        updateAnswer("Загружаю сообщения канала MTProxy #" + (autoTestSubIndex + 1) + "...");
        int classGuid = ConnectionsManager.generateClassGuid();
        MessagesController.getInstance(currentAccount).loadMessages(
                -chatId, 0, false, MESSAGE_LOAD_COUNT, 0, 0, false, 0, classGuid, 0, 0, 0, 0, 0, 0, false);
        if (autoTestChannelTimeoutRunnable != null) handler.removeCallbacks(autoTestChannelTimeoutRunnable);
        autoTestChannelTimeoutRunnable = () -> {
            if (autoTestPhase == 4) { autoTestSubIndex++; loadChannelMessagesForReserve(); }
        };
        handler.postDelayed(autoTestChannelTimeoutRunnable, 15000);
    }

    private void onChannelMessagesLoaded(long chatId, ArrayList<MessageObject> messages) {
        if ((autoTestPhase != 4 && autoTestPhase != 37) || chatId != -autoTestLoadingChatId) return;
        if (autoTestChannelTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestChannelTimeoutRunnable);
            autoTestChannelTimeoutRunnable = null;
        }
        int subIdx = autoTestSubIndex;
        if (messages == null || messages.isEmpty()) {
            autoTestSubIndex++;
            advanceToNextChannel();
            return;
        }
        ArrayList<String> newProxies = new ArrayList<>();
        SharedConfig.loadProxyList();
        for (MessageObject mo : messages) {
            if (mo == null || mo.messageOwner == null) continue;
            String proxyUrl = parseMTProxyFromMessage(mo.messageOwner);
            if (proxyUrl == null) continue;
            // Skip only proxies already in proxyList — proxies stored in settings
            // but absent from proxyList (e.g. after list was cleared) must be re-tested.
            SharedConfig.ProxyInfo candidate = parseMTProxyToInfo(proxyUrl);
            boolean inList = false;
            if (candidate != null) {
                for (SharedConfig.ProxyInfo p : SharedConfig.proxyList) {
                    if (p.address.equals(candidate.address) && p.port == candidate.port) {
                        inList = true;
                        break;
                    }
                }
            }
            if (!inList) newProxies.add(proxyUrl);
        }
        if (newProxies.isEmpty()) {
            autoTestSubIndex++;
            advanceToNextChannel();
            return;
        }
        testNewChannelProxies(newProxies, 0, subIdx);
    }

    private void testNewChannelProxies(ArrayList<String> proxies, int idx, int subIdx) {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestPhase != 4 && autoTestPhase != 37) return;
        if (idx >= proxies.size() || autoTestWorkingProxies >= SharedConfig.autoMinWrkMTProxy) {
            autoTestSubIndex++;
            advanceToNextChannel();
            return;
        }
        String proxyUrl = proxies.get(idx);
        SharedConfig.ProxyInfo info = parseMTProxyToInfo(proxyUrl);
        if (info == null) { testNewChannelProxies(proxies, idx + 1, subIdx); return; }
        ConnectionsManager.getInstance(currentAccount).checkProxy(
                info.address, info.port,
                info.username != null ? info.username : "",
                info.password != null ? info.password : "",
                info.secret   != null ? info.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (time > 0 && time < 5000) {
                        if (flagBadWorkMtProxy && info.isSame(SharedConfig.currentProxy)) {
                            testNewChannelProxies(proxies, idx + 1, subIdx);
                            return;
                        }
                        connectionFound = true;
                        SharedConfig.ProxyInfo added = SharedConfig.addProxy(info);
                        boolean isNew = (added == info); // true = was actually inserted
                        if (isNew) SharedConfig.saveProxyList();
                        if (autoTestWorkingProxies == 0 && !connectionFoundViaV2Ray) {
                            enableProxy(info);
                            showToast("Включён MTProxy из канала: " + info.address);
                        }
                        if (isNew) {
                            for (int j = 0; j < 14; j++) {
                                if (TextUtils.isEmpty(SharedConfig.mtproxyRequestArrays[subIdx][j])) {
                                    SharedConfig.mtproxyRequestArrays[subIdx][j] = proxyUrl;
                                    // Keep count in sync so UI and testMTProxyManual() see all stored proxies
                                    if (j >= SharedConfig.mtproxyRequestCounts[subIdx]) {
                                        SharedConfig.mtproxyRequestCounts[subIdx] = j + 1;
                                    }
                                    SharedConfig.saveConfig();
                                    break;
                                }
                            }
                            autoTestWorkingProxies++;
                        }
                        if (autoTestPhase == 37) {
                            // Got connection in offline mode — proceed to reserve immediately
                            proceedToReserve();
                            return;
                        }
                    }
                    testNewChannelProxies(proxies, idx + 1, subIdx);
                }));
    }

    // ================================================================
    // Phase 45: MTP bot proxy requesting
    // ================================================================

    private void proceedToMtpBotReserve() {
        if (autoTestWorkingProxies >= SharedConfig.autoMinWrkMTProxy
                || SharedConfig.mtpBotSubscriptionsCount == 0) {
            // Enough proxies or no mtpbot subs — proceed to final decision
            finishReservePhase();
            return;
        }
        autoTestPhase          = 45;
        autoTestMtpBotSubIndex = 0;
        autoTestMtpBotCmdIndex = 0;
        autoTestMtpBotReqCount = 0;
        autoTestMtpBotWaitingId = 0;
        updateAnswer("Запрашиваю MTProxy у ботов...");
        autoTestNextMtpBotCmd();
    }

    private void finishReservePhase() {
        if (connectionFound) {
            consecutiveNoConnectCycles = 0;
            flagNoConnect = false;
            updateAnswer("Есть коннект к Telegram.");
            if (autoLoopRunning && autoTestWorkingKeys < SharedConfig.autoMinWrkKeys) {
                autoTestPhase       = 5;
                autoTestBotCmdIndex = 0;
                handler.postDelayed(this::autoTestNextBotCmd, 1000);
            } else {
                autoTestPhase = 6;
                showAutoTestDone("Авто-тест завершён. Всё работает!");
            }
        } else {
            consecutiveNoConnectCycles++;
            flagNoConnect = true;
            // Count how many proxies are available offline (stored + cached bot)
            int availableOffline = 0;
            for (int s = 0; s < SharedConfig.mtproxySubscriptionsCount; s++) {
                for (int i = 0; i < 14; i++) {
                    if (!TextUtils.isEmpty(SharedConfig.mtproxyRequestArrays[s][i])) availableOffline++;
                }
            }
            String noConnMsg = "Нет коннекта к Telegram. Цикл " + consecutiveNoConnectCycles + ". Ожидание...";
            if (availableOffline > 0 && availableOffline < SharedConfig.autoMinWrkMTProxy) {
                noConnMsg += "\nСохр. прокси: " + availableOffline + " из " + SharedConfig.autoMinWrkMTProxy
                        + " нужных. Для пополнения нужно соединение.";
            }
            updateAnswer(noConnMsg);
            showToast("Telegram не доступен. Попробую позже");
            // After 3 consecutive failed cycles — auto-restart v2ray VPN process
            if (consecutiveNoConnectCycles == 3 && autoLoopRunning) {
                showToast("v2ray не работает " + consecutiveNoConnectCycles
                        + " цикла подряд. Перезапускаю VPN...");
                updateAnswer("Перезапускаю v2rayTg VPN (Stop→Start)...");
                v2rayBridge.sendStopV2RayCommand();
                handler.postDelayed(() -> {
                    if (autoLoopRunning) v2rayBridge.sendStartV2RayCommand();
                }, 3000);
            } else if (consecutiveNoConnectCycles % 5 == 0 && consecutiveNoConnectCycles > 3) {
                // Every 5 cycles after the first restart attempt — remind user with a hint
                showToast("v2ray не работает уже " + consecutiveNoConnectCycles
                        + " циклов.\nВ v2rayTg: нажмите Stop → Run вручную");
            }
            autoTestPhase = 6;
            scheduleNextCycle();
        }
    }

    private void autoTestNextMtpBotCmd() {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestPhase != 45) return;
        if (autoTestWorkingProxies >= SharedConfig.autoMinWrkMTProxy) {
            finishReservePhase();
            return;
        }
        if (autoTestMtpBotReqCount >= MAX_BOT_REQUESTS_PER_CYCLE) {
            finishReservePhase();
            return;
        }

        // Find next command to run
        while (autoTestMtpBotSubIndex < SharedConfig.mtpBotSubscriptionsCount) {
            while (autoTestMtpBotCmdIndex < SharedConfig.mtpBotRequestCounts[autoTestMtpBotSubIndex]) {
                String cmd = SharedConfig.mtpBotRequestArrays[autoTestMtpBotSubIndex][autoTestMtpBotCmdIndex];
                if (!TextUtils.isEmpty(cmd)) {
                    long diff = (System.currentTimeMillis()
                            - SharedConfig.mtpBotTimeLastRequestArrays[autoTestMtpBotSubIndex][autoTestMtpBotCmdIndex]) / 60000L;
                    if (diff >= SharedConfig.timeBetwRequest2BotMtp) break;
                }
                autoTestMtpBotCmdIndex++;
            }
            if (autoTestMtpBotCmdIndex < SharedConfig.mtpBotRequestCounts[autoTestMtpBotSubIndex]) break;
            autoTestMtpBotSubIndex++;
            autoTestMtpBotCmdIndex = 0;
        }

        if (autoTestMtpBotSubIndex >= SharedConfig.mtpBotSubscriptionsCount) {
            finishReservePhase();
            return;
        }

        String botUsername = SharedConfig.mtpBotSubscriptionBots[autoTestMtpBotSubIndex];
        if (TextUtils.isEmpty(botUsername)) {
            autoTestMtpBotSubIndex++;
            autoTestMtpBotCmdIndex = 0;
            handler.post(this::autoTestNextMtpBotCmd);
            return;
        }

        String cmd = SharedConfig.mtpBotRequestArrays[autoTestMtpBotSubIndex][autoTestMtpBotCmdIndex];
        updateAnswer("Запрашиваю MTProxy у бота " + botUsername + "...");
        autoTestMtpBotReqCount++;
        autoTestSendMtpBotCommand(botUsername, cmd);
    }

    private void autoTestSendMtpBotCommand(String botUsername, String message) {
        try {
            MessagesController mc = MessagesController.getInstance(currentAccount);
            TLRPC.User botUser = mc.getUser(botUsername.replace("@", ""));
            if (botUser == null && SharedConfig.mtpBotSubscriptionBotIds[autoTestMtpBotSubIndex] != 0L)
                botUser = mc.getUser(SharedConfig.mtpBotSubscriptionBotIds[autoTestMtpBotSubIndex]);
            if (botUser == null) {
                updateAnswer("MTProxy бот не найден: " + botUsername);
                autoTestMtpBotCmdIndex++;
                handler.postDelayed(this::autoTestNextMtpBotCmd, MIN_TIME_BETWEEN_REQUEST_TG);
                return;
            }
            if (SharedConfig.mtpBotSubscriptionBotIds[autoTestMtpBotSubIndex] == 0L) {
                SharedConfig.mtpBotSubscriptionBotIds[autoTestMtpBotSubIndex] = botUser.id;
                SharedConfig.saveConfig();
            }
            autoTestMtpBotWaitingId = botUser.id;
            // Stamp send time so MTP bot rate limit applies even when bot is unreachable.
            SharedConfig.mtpBotTimeLastRequestArrays[autoTestMtpBotSubIndex][autoTestMtpBotCmdIndex] = System.currentTimeMillis();
            SharedConfig.saveConfig();
            String[] parts = message.split(";");
            int delay = 0;
            for (String part : parts) {
                String p = part.trim();
                if (TextUtils.isEmpty(p)) continue;
                final long uid = botUser.id;
                if (delay == 0) {
                    SendMessagesHelper.getInstance(currentAccount).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(p, uid));
                } else {
                    final String fp = p;
                    handler.postDelayed(() -> SendMessagesHelper.getInstance(currentAccount).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(fp, uid)), delay * MIN_TIME_BETWEEN_REQUEST_TG);
                }
                delay++;
            }
            if (autoTestMtpBotTimeoutRunnable != null) handler.removeCallbacks(autoTestMtpBotTimeoutRunnable);
            final long waitingId = botUser.id;
            autoTestMtpBotTimeoutRunnable = () -> {
                if (autoTestPhase == 45 && autoTestMtpBotWaitingId == waitingId) {
                    autoTestMtpBotWaitingId = 0;
                    autoTestMtpBotCmdIndex++;
                    autoTestNextMtpBotCmd();
                }
            };
            handler.postDelayed(autoTestMtpBotTimeoutRunnable, 60000);
        } catch (Exception e) {
            autoTestMtpBotCmdIndex++;
            handler.postDelayed(this::autoTestNextMtpBotCmd, MIN_TIME_BETWEEN_REQUEST_TG);
        }
    }

    private void onAutoTestMtpBotProxyReceived(String proxyUrl, int subIdx, int cmdIdx) {
        if (autoTestMtpBotTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestMtpBotTimeoutRunnable);
            autoTestMtpBotTimeoutRunnable = null;
        }
        autoTestMtpBotWaitingId = 0;
        SharedConfig.mtpBotProxyArrays[subIdx][cmdIdx] = proxyUrl;
        SharedConfig.mtpBotTimeLastRequestArrays[subIdx][cmdIdx] = System.currentTimeMillis();
        SharedConfig.saveConfig();

        // Test proxy ping
        SharedConfig.ProxyInfo info = parseMTProxyToInfo(proxyUrl);
        if (info == null) {
            autoTestMtpBotCmdIndex++;
            handler.postDelayed(this::autoTestNextMtpBotCmd, MIN_TIME_BETWEEN_REQUEST_TG);
            return;
        }
        updateAnswer("Тестирую MTProxy от бота: " + info.address + "...");
        ConnectionsManager.getInstance(currentAccount).checkProxy(
                info.address, info.port,
                info.username != null ? info.username : "",
                info.password != null ? info.password : "",
                info.secret   != null ? info.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (time > 0 && time < 5000) {
                        SharedConfig.ProxyInfo added = SharedConfig.addProxy(info);
                        boolean isNew = (added == info);
                        if (isNew) SharedConfig.saveProxyList();
                        if (!connectionFound && !connectionFoundViaV2Ray) {
                            connectionFound = true;
                            enableProxy(info);
                            showToast("Включён MTProxy от бота: " + info.address);
                        } else {
                            connectionFound = true;
                        }
                        if (isNew) autoTestWorkingProxies++;
                    }
                    autoTestMtpBotCmdIndex++;
                    handler.postDelayed(AutoConnectController.this::autoTestNextMtpBotCmd,
                            MIN_TIME_BETWEEN_REQUEST_TG);
                }));
    }

    // ================================================================
    // Phase 5: bot key requesting
    // ================================================================

    private void autoTestNextBotCmd() {
        if (!autoLoopRunning && !mtproxyLoopRunning) return;
        if (autoTestWorkingKeys >= SharedConfig.autoMinWrkKeys) {
            autoTestPhase = 6;
            showAutoTestDone("Готово: " + autoTestWorkingKeys + " v2ray ключей работает.");
            return;
        }
        if (autoTestBotRequestCount >= MAX_BOT_REQUESTS_PER_CYCLE) {
            autoTestPhase = 6;
            showAutoTestDone("Лимит запросов к боту (" + MAX_BOT_REQUESTS_PER_CYCLE
                    + "/цикл). Рабочих ключей: " + autoTestWorkingKeys);
            return;
        }

        while (autoTestV2RaySubIndex < SharedConfig.v2raySubscriptionsCount) {
            while (autoTestBotCmdIndex < SharedConfig.v2rayRequestCounts[autoTestV2RaySubIndex]) {
                String cmd = SharedConfig.v2rayRequestArrays[autoTestV2RaySubIndex][autoTestBotCmdIndex];
                if (!TextUtils.isEmpty(cmd)) {
                    long diff = (System.currentTimeMillis()
                            - SharedConfig.v2rayTimeLastRequestArrays[autoTestV2RaySubIndex][autoTestBotCmdIndex]) / 60000L;
                    if (diff >= SharedConfig.timeBetwRequest2Bot) {
                        // Found a command to run
                        break;
                    }
                }
                autoTestBotCmdIndex++;
            }

            if (autoTestBotCmdIndex < SharedConfig.v2rayRequestCounts[autoTestV2RaySubIndex]) {
                // We have a command to run in current subIndex
                break;
            } else {
                // Move to next subscription
                autoTestV2RaySubIndex++;
                autoTestBotCmdIndex = 0;
            }
        }

        if (autoTestV2RaySubIndex >= SharedConfig.v2raySubscriptionsCount) {
            autoTestPhase = 6;
            showAutoTestDone("Авто-тест завершён. Рабочих v2ray ключей: " + autoTestWorkingKeys);
            return;
        }

        String botUsername = SharedConfig.v2raySubscriptionBots[autoTestV2RaySubIndex];
        if (TextUtils.isEmpty(botUsername)) {
            autoTestV2RaySubIndex++;
            autoTestBotCmdIndex = 0;
            handler.post(this::autoTestNextBotCmd);
            return;
        }

        String cmd = SharedConfig.v2rayRequestArrays[autoTestV2RaySubIndex][autoTestBotCmdIndex];
        updateAnswer("Запрашиваю ключи от бота " + botUsername + " (подп. " + (autoTestV2RaySubIndex + 1)
                + ", ком. " + (autoTestBotCmdIndex + 1) + ")...");
        autoTestBotRequestCount++;
        autoTestSendBotCommand(botUsername, cmd);
    }

    private void autoTestSendBotCommand(String botUsername, String message) {
        try {
            MessagesController mc = MessagesController.getInstance(currentAccount);
            TLRPC.User botUser = mc.getUser(botUsername.replace("@", ""));
            if (botUser == null && SharedConfig.v2raySubscriptionBotIds[autoTestV2RaySubIndex] != 0L)
                botUser = mc.getUser(SharedConfig.v2raySubscriptionBotIds[autoTestV2RaySubIndex]);
            if (botUser == null) {
                updateAnswer("Бот не найден: " + botUsername);
                autoTestBotCmdIndex++;
                handler.postDelayed(this::autoTestNextBotCmd, MIN_TIME_BETWEEN_REQUEST_TG);
                return;
            }
            if (SharedConfig.v2raySubscriptionBotIds[autoTestV2RaySubIndex] == 0L) {
                SharedConfig.v2raySubscriptionBotIds[autoTestV2RaySubIndex] = botUser.id;
                SharedConfig.saveConfig();
            }
            autoTestWaitingBotId = botUser.id;
            // Stamp the send time NOW so rate limiting applies even if the bot never responds.
            // (Previously only onAutoTestBotKeysReceived stamped the time, so a timed-out
            // request had timestamp=0 and was re-sent every cycle regardless of the interval.)
            SharedConfig.v2rayTimeLastRequestArrays[autoTestV2RaySubIndex][autoTestBotCmdIndex] = System.currentTimeMillis();
            SharedConfig.saveConfig();
            String[] parts = message.split(";");
            int delay = 0;
            for (String part : parts) {
                String p = part.trim();
                if (TextUtils.isEmpty(p)) continue;
                final long uid = botUser.id;
                if (delay == 0) {
                    SendMessagesHelper.getInstance(currentAccount).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(p, uid));
                } else {
                    final String fp = p;
                    final long d = delay * MIN_TIME_BETWEEN_REQUEST_TG;
                    handler.postDelayed(() -> SendMessagesHelper.getInstance(currentAccount).sendMessage(
                            SendMessagesHelper.SendMessageParams.of(fp, uid)), d);
                }
                delay++;
            }
            if (autoTestBotTimeoutRunnable != null) handler.removeCallbacks(autoTestBotTimeoutRunnable);
            if (autoTestBotShortTimeoutRunnable != null) handler.removeCallbacks(autoTestBotShortTimeoutRunnable);
            final long waitingId = botUser.id;
            autoTestBotTimeoutRunnable = () -> {
                if (autoTestPhase == 5 && autoTestWaitingBotId == waitingId) {
                    autoTestWaitingBotId = 0;
                    autoTestBotCmdIndex++;
                    autoTestNextBotCmd();
                }
            };
            autoTestBotShortTimeoutRunnable = () -> {
                if (autoTestPhase == 5 && autoTestWaitingBotId == waitingId) {
                    if (isMTProxyActive()) {
                        flagBadWorkMtProxy = true;
                        updateAnswer("MTProxy не отвечает (10с). Ищу другой...");
                        autoTestWaitingBotId = 0;
                        testAllmtProxy();
                    }
                }
            };
            handler.postDelayed(autoTestBotTimeoutRunnable, 60000);
            handler.postDelayed(autoTestBotShortTimeoutRunnable, MAX_TIME_WAIT_RESPONCE);
        } catch (Exception e) {
            autoTestBotCmdIndex++;
            handler.postDelayed(this::autoTestNextBotCmd, MIN_TIME_BETWEEN_REQUEST_TG);
        }
    }

    private void onAutoTestBotKeysReceived(String keys, int cmdIndex) {
        if (autoTestBotTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestBotTimeoutRunnable);
            autoTestBotTimeoutRunnable = null;
        }
        if (autoTestBotShortTimeoutRunnable != null) {
            handler.removeCallbacks(autoTestBotShortTimeoutRunnable);
            autoTestBotShortTimeoutRunnable = null;
        }
        flagBadWorkMtProxy = false;
        autoTestWaitingBotId = 0;
        SharedConfig.v2rayKeyArrays[autoTestV2RaySubIndex][cmdIndex] = keys;
        SharedConfig.v2rayTimeLastRequestArrays[autoTestV2RaySubIndex][cmdIndex] = System.currentTimeMillis();
        SharedConfig.saveConfig();

        updateAnswer("Ключи получены. Запускаю и импортирую в v2ray...");
        v2rayBridge.sendStartV2RayCommand();
        handler.postDelayed(() -> v2rayBridge.sendImportKeysCommand(keys, ""), 1000);
    }

    // ================================================================
    // NotificationCenter
    // ================================================================

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) return;

        if (id == NotificationCenter.messagesDidLoad) {
            if (autoTestPhase == 4 || autoTestPhase == 37) {
                long dialogId = (long) args[0];
                @SuppressWarnings("unchecked")
                ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[2];
                onChannelMessagesLoaded(dialogId, messages);
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            long dialogId = (long) args[0];
            @SuppressWarnings("unchecked")
            ArrayList<MessageObject> msgs = (ArrayList<MessageObject>) args[1];
            if (msgs == null) return;

            // Phase 5: v2ray bot key response
            if (autoTestPhase == 5 && autoTestWaitingBotId != 0 && dialogId == autoTestWaitingBotId) {
                final int cmdIdx = autoTestBotCmdIndex;
                for (MessageObject mo : msgs) {
                    if (mo == null || mo.isOut()) continue;
                    if (mo.messageOwner == null || mo.messageOwner.message == null) continue;
                    String keys = parseAllV2RayProfiles(mo.messageOwner.message);
                    if (!TextUtils.isEmpty(keys)) { onAutoTestBotKeysReceived(keys, cmdIdx); break; }
                }
            }

            // Phase 45: mtpbot proxy response (auto-cycle)
            if (autoTestPhase == 45 && autoTestMtpBotWaitingId != 0 && dialogId == autoTestMtpBotWaitingId) {
                final int subIdx = autoTestMtpBotSubIndex;
                final int cmdIdx = autoTestMtpBotCmdIndex;
                for (MessageObject mo : msgs) {
                    if (mo == null || mo.isOut()) continue;
                    if (mo.messageOwner == null) continue;
                    String proxyUrl = parseMTProxyFromMessage(mo.messageOwner);
                    if (!TextUtils.isEmpty(proxyUrl)) {
                        onAutoTestMtpBotProxyReceived(proxyUrl, subIdx, cmdIdx);
                        break;
                    }
                }
            }

            // Manual mtpbot proxy request (from ConfigMtpBotRequest panel)
            if (pendingMtpBotManualWaitingId != 0 && dialogId == pendingMtpBotManualWaitingId) {
                for (MessageObject mo : msgs) {
                    if (mo == null || mo.isOut()) continue;
                    if (mo.messageOwner == null) continue;
                    String proxyUrl = parseMTProxyFromMessage(mo.messageOwner);
                    if (!TextUtils.isEmpty(proxyUrl)) {
                        onMtpBotManualProxyReceived(proxyUrl);
                        break;
                    }
                }
            }
        }
    }

    // ================================================================
    // Manual Request Handlers (for settings panels)
    // ================================================================

    public void requestBotKeyManual(int subIndex, int cmdIndex) {
        ensureV2RayBridge();
        if (subIndex < 0 || subIndex >= SharedConfig.v2raySubscriptionsCount) return;
        if (cmdIndex < 0 || cmdIndex >= SharedConfig.v2rayRequestCounts[subIndex]) return;

        String botUsername = SharedConfig.v2raySubscriptionBots[subIndex];
        String cmd = SharedConfig.v2rayRequestArrays[subIndex][cmdIndex];
        if (TextUtils.isEmpty(botUsername) || TextUtils.isEmpty(cmd)) return;

        // Reset state for this manual request
        autoTestV2RaySubIndex = subIndex;
        autoTestBotCmdIndex = cmdIndex;
        autoTestPhase = 5; // BOT_REQUEST phase
        autoTestBotRequestCount = 0;
        autoTestWorkingKeys = 0;

        updateAnswer("Запрашиваю ключ у бота " + botUsername + "...");
        autoTestSendBotCommand(botUsername, cmd);
    }

    public void testMTProxyManual(int subIndex, int proxyIndex, final RequestTimeDelegate delegate) {
        if (subIndex < 0 || subIndex >= SharedConfig.mtproxySubscriptionsCount) return;
        if (proxyIndex < 0 || proxyIndex >= SharedConfig.mtproxyRequestCounts[subIndex]) return;

        String url = SharedConfig.mtproxyRequestArrays[subIndex][proxyIndex];
        if (TextUtils.isEmpty(url)) {
            if (delegate != null) delegate.run(-1);
            return;
        }

        final SharedConfig.ProxyInfo proxyInfo = parseMTProxyToInfo(url);
        if (proxyInfo == null) {
            if (delegate != null) delegate.run(-1);
            return;
        }

        ConnectionsManager.getInstance(currentAccount).checkProxy(
                proxyInfo.address, proxyInfo.port,
                proxyInfo.username, proxyInfo.password, proxyInfo.secret,
                time -> {
                    if (time > 0) {
                        SharedConfig.addProxy(proxyInfo);
                    }
                    if (delegate != null) delegate.run(time);
                }
        );
    }

    // Manual request for ConfigMtpBotRequest panel
    // Sends bot command and waits for MTProxy reply; calls delegate with proxy URL or null on timeout.
    public interface MtpBotProxyDelegate {
        void onProxyReceived(String proxyUrl);
        void onTimeout();
    }

    private MtpBotProxyDelegate pendingMtpBotManualDelegate;
    private long pendingMtpBotManualWaitingId = 0;
    private int  pendingMtpBotManualSubIndex  = -1;
    private int  pendingMtpBotManualCmdIndex  = -1;
    private Runnable pendingMtpBotManualTimeoutRunnable;

    public void requestMtpBotProxyManual(int subIndex, int cmdIndex, MtpBotProxyDelegate delegate) {
        if (subIndex < 0 || subIndex >= SharedConfig.mtpBotSubscriptionsCount) return;
        if (cmdIndex < 0 || cmdIndex >= SharedConfig.mtpBotRequestCounts[subIndex]) return;

        String botUsername = SharedConfig.mtpBotSubscriptionBots[subIndex];
        String cmd = SharedConfig.mtpBotRequestArrays[subIndex][cmdIndex];
        if (TextUtils.isEmpty(botUsername) || TextUtils.isEmpty(cmd)) return;

        if (pendingMtpBotManualTimeoutRunnable != null) {
            handler.removeCallbacks(pendingMtpBotManualTimeoutRunnable);
        }

        MessagesController mc = MessagesController.getInstance(currentAccount);
        TLRPC.User botUser = mc.getUser(botUsername.replace("@", ""));
        if (botUser == null && SharedConfig.mtpBotSubscriptionBotIds[subIndex] != 0L)
            botUser = mc.getUser(SharedConfig.mtpBotSubscriptionBotIds[subIndex]);
        if (botUser == null) { if (delegate != null) delegate.onTimeout(); return; }

        if (SharedConfig.mtpBotSubscriptionBotIds[subIndex] == 0L) {
            SharedConfig.mtpBotSubscriptionBotIds[subIndex] = botUser.id;
            SharedConfig.saveConfig();
        }

        pendingMtpBotManualDelegate   = delegate;
        pendingMtpBotManualWaitingId  = botUser.id;
        pendingMtpBotManualSubIndex   = subIndex;
        pendingMtpBotManualCmdIndex   = cmdIndex;

        String[] parts = cmd.split(";");
        int delay = 0;
        for (String part : parts) {
            String p = part.trim();
            if (TextUtils.isEmpty(p)) continue;
            final long uid = botUser.id;
            if (delay == 0) {
                SendMessagesHelper.getInstance(currentAccount).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(p, uid));
            } else {
                final String fp = p;
                handler.postDelayed(() -> SendMessagesHelper.getInstance(currentAccount).sendMessage(
                        SendMessagesHelper.SendMessageParams.of(fp, uid)), delay * MIN_TIME_BETWEEN_REQUEST_TG);
            }
            delay++;
        }

        pendingMtpBotManualTimeoutRunnable = () -> {
            pendingMtpBotManualWaitingId = 0;
            MtpBotProxyDelegate d = pendingMtpBotManualDelegate;
            pendingMtpBotManualDelegate = null;
            if (d != null) d.onTimeout();
        };
        handler.postDelayed(pendingMtpBotManualTimeoutRunnable, 60000);
    }

    // Called from didReceivedNotification when a manual mtpbot reply arrives
    private void onMtpBotManualProxyReceived(String proxyUrl) {
        if (pendingMtpBotManualTimeoutRunnable != null) {
            handler.removeCallbacks(pendingMtpBotManualTimeoutRunnable);
            pendingMtpBotManualTimeoutRunnable = null;
        }
        int si = pendingMtpBotManualSubIndex;
        int ci = pendingMtpBotManualCmdIndex;
        if (si >= 0 && ci >= 0) {
            SharedConfig.mtpBotProxyArrays[si][ci] = proxyUrl;
            SharedConfig.mtpBotTimeLastRequestArrays[si][ci] = System.currentTimeMillis();
            SharedConfig.saveConfig();
        }
        pendingMtpBotManualWaitingId  = 0;
        MtpBotProxyDelegate d = pendingMtpBotManualDelegate;
        pendingMtpBotManualDelegate   = null;
        pendingMtpBotManualSubIndex   = -1;
        pendingMtpBotManualCmdIndex   = -1;
        if (d != null) d.onProxyReceived(proxyUrl);
    }

    // ================================================================
    // Proxy helpers
    // ================================================================

    private void disableAllProxies() {
        MessagesController.getGlobalMainSettings().edit()
                .putBoolean("proxy_enabled", false).apply();
        ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
    }

    private void enableProxy(SharedConfig.ProxyInfo proxy) {
        SharedConfig.currentProxy = proxy;
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        editor.putBoolean("proxy_enabled", true);
        editor.putString("proxy_ip",       proxy.address);
        editor.putInt   ("proxy_port",     proxy.port);
        editor.putString("proxy_user",     proxy.username != null ? proxy.username : "");
        editor.putString("proxy_password", proxy.password != null ? proxy.password : "");
        editor.putString("proxy_secret",   proxy.secret   != null ? proxy.secret   : "");
        editor.apply();
        ConnectionsManager.setProxySettings(true, proxy.address, proxy.port,
                proxy.username != null ? proxy.username : "",
                proxy.password != null ? proxy.password : "",
                proxy.secret   != null ? proxy.secret   : "");
    }

    // ================================================================
    // Parsing helpers
    // ================================================================

    /** Extract tg://proxy URL from plain text (regex). Strips trailing punctuation like ')'. */
    private String parseMTProxyUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Matcher m = Pattern.compile("(?:tg://proxy\\?|https?://t\\.me/proxy\\?)[^\\s)]+").matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Extract tg://proxy URL from a MessageObject — checks both plain text and message entities. */
    private String parseMTProxyFromMessage(TLRPC.Message msg) {
        if (msg == null) return null;
        // 1) Try plain text first
        String fromText = parseMTProxyUrl(msg.message);
        if (fromText != null) return fromText;
        // 2) Check entities (bot may send URL as a MessageEntityTextUrl)
        if (msg.entities != null) {
            for (TLRPC.MessageEntity entity : msg.entities) {
                if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                    String url = ((TLRPC.TL_messageEntityTextUrl) entity).url;
                    if (!TextUtils.isEmpty(url) && (url.startsWith("tg://proxy?")
                            || url.startsWith("https://t.me/proxy?"))) {
                        return url;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Strip "nick: " / "[date] nick: " prefix from each line of clipboard text,
     * then join non-empty lines with "; " so multiple commands are sent sequentially.
     * Public so it can be reused by UI fragments.
     */
    public static String stripNickPrefixFromClipboard(String raw) {
        if (TextUtils.isEmpty(raw)) return raw;
        String[] lines = raw.split("\\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String stripped = line.trim();
            if (TextUtils.isEmpty(stripped)) continue;
            // Strip "anything: " prefix where the colon is within first 60 chars
            int colonIdx = stripped.indexOf(": ");
            if (colonIdx > 0 && colonIdx < 60) {
                String after = stripped.substring(colonIdx + 2).trim();
                if (!TextUtils.isEmpty(after)) stripped = after;
            }
            if (!TextUtils.isEmpty(stripped)) {
                if (result.length() > 0) result.append("; ");
                result.append(stripped);
            }
        }
        return result.toString();
    }

    /** Public wrapper used by ConfigMtpBotRequest to parse a tg://proxy URL. */
    public SharedConfig.ProxyInfo parseMTProxyToInfoPublic(String url) {
        return parseMTProxyToInfo(url);
    }

    private SharedConfig.ProxyInfo parseMTProxyToInfo(String url) {
        try {
            Uri uri = url.startsWith("tg://proxy?")
                    ? Uri.parse("https://telegram.org/proxy?" + url.substring("tg://proxy?".length()))
                    : Uri.parse(url);
            String server  = uri.getQueryParameter("server");
            String portStr = uri.getQueryParameter("port");
            String secret  = uri.getQueryParameter("secret");
            if (TextUtils.isEmpty(server) || TextUtils.isEmpty(portStr)) return null;
            return new SharedConfig.ProxyInfo(server, Integer.parseInt(portStr), "", "",
                    secret != null ? secret : "");
        } catch (Exception e) { return null; }
    }

    private String parseAllV2RayProfiles(String message) {
        if (TextUtils.isEmpty(message)) return null;
        Matcher m = Pattern.compile("(?:vless|vmess|ss|trojan|shadowsocks)://[^\\s]+").matcher(message);
        StringBuilder sb = new StringBuilder();
        while (m.find()) { if (sb.length() > 0) sb.append("\n"); sb.append(m.group()); }
        return sb.length() > 0 ? sb.toString() : null;
    }

    // ================================================================
    // UI helpers
    // ================================================================

    private void showToast(final String msg) {
        AndroidUtilities.runOnUIThread(() -> {
            Context ctx = ApplicationLoader.applicationContext;
            if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
        });
    }

    private void showAutoTestDone(String msg) {
        updateAnswer(msg);
        if (autoLoopRunning)       scheduleNextCycle();
        else if (mtproxyLoopRunning) scheduleNextMtproxyCycle();
    }

    private void updateAnswer(String text) {
        v2rayAnswerText = text;
        if (uiDelegate != null) {
            AndroidUtilities.runOnUIThread(() -> { if (uiDelegate != null) uiDelegate.onAnswerUpdate(text); });
        }
    }

    private void updateTimer(String text) {
        v2rayTimerText = text;
        if (uiDelegate != null) {
            AndroidUtilities.runOnUIThread(() -> { if (uiDelegate != null) uiDelegate.onTimerUpdate(text); });
        }
    }

    private void stopLoopWithError(String reason) {
        autoLoopHadError = true;
        stopLoopTstConnect();
        updateAnswer("Ошибка авторежима: " + reason);
    }
}
