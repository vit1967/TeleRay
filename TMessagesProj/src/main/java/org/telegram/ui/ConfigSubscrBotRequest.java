/*
 * ConfigSubscrBotRequest - Fragment for managing V2Ray Subscription 1 settings
 * Provides UI for configuring bot subscription, request commands (up to 14), and viewing received keys
 */

package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.AutoConnectController;
import com.teleray.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.V2RayNekoBridge;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.EditTextSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigSubscrBotRequest extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, DialogsActivity.DialogsActivityDelegate {

    private int subIndex;
    private AutoConnectController ctrl() {
        return AutoConnectController.getInstance(currentAccount);
    }

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private View addButtonView; // Reference to "+ Add Command" button

    private int rowCount;

    // Subscription 1 section rows
    private int subscriptionRow;
    private int timeBetwRequest2BotRow;

    // Dynamic request fields section (up to 14 pairs)
    private int[] requestRow = new int[14];
    private int[] updateKeyRow = new int[14];

    // Add button row
    private int addButtonRow;

    // Common section
    private int botUsernameHintRow;
    private int requestCommandsHintRow;
    private int infoRow;

    // Storage for UI values (mirrors SharedConfig arrays)
    private String subscription1 = "";
    private String[] requestValues = new String[14];
    private String[] keyValues = new String[14];

    // Bot tracking
    private long waitingForBotDialogId = 0;
    private String expectedBotUsername = "";
    private int waitingForRequestIndex = 0; // 0-13

    // State tracking for navigation
    private int choosingForSubscription;  // For DialogsActivity (1 = subscription selection)
    private int choosingForRequestField;  // For ChatActivity (-1 to -14 for request fields, 1 to 14 for key fields)
    private boolean wasDialogSelected;

    // Flag to track if user manually touched the request field
    private boolean requestFieldManuallyTouched = false;

    // Flag to prevent infinite loop when auto-opening DialogsActivity
    private boolean wasDialogOpened = false;

    private final static int STATE_IDLE = 0;
    private final static int STATE_WAITING = 1;
    private final static int STATE_TIMEOUT = 2;
    private final static int STATE_EMPTY = 3;
    private final static int STATE_NO_KEYS = 4;
    private int[] requestStates = new int[14];
    private Runnable[] timeoutRunnables = new Runnable[14];
    private static final long MIN_TIME_BETWEEN_REQUEST_TG = 4000L; // мс между запросами к Telegram
    private Handler handler = new Handler(Looper.getMainLooper());

    public ConfigSubscrBotRequest(int index) {
        super();
        this.subIndex = index;
    }

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        subscription1 = SharedConfig.v2raySubscriptionBots[subIndex];
        updateRows();

        // Load values from SharedConfig
        for (int i = 0; i < 14; i++) {
            requestValues[i] = SharedConfig.v2rayRequestArrays[subIndex][i];
            keyValues[i] = SharedConfig.v2rayKeyArrays[subIndex][i];
        }

        // Register for message notifications
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.messagesDidLoad);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.messagesDidLoad);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        actionBar.setTitle("V2Ray Subscr. " + (subIndex + 1));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setTag(Theme.key_windowBackgroundGray);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setAdapter(listAdapter = new ListAdapter(context));

        listView.setOnItemClickListener((view, position, x, y) -> {
            if (!view.isEnabled()) {
                return;
            }

            if (position == subscriptionRow) {
                // Handle subscription field click - open dialog selection
                wasDialogSelected = false;
                choosingForSubscription = 1;
                Bundle args = new Bundle();
                args.putBoolean("onlySelect", true);
                args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
                DialogsActivity dialogsActivity = new DialogsActivity(args);
                dialogsActivity.setDelegate(ConfigSubscrBotRequest.this);
                presentFragment(dialogsActivity);
            } else if (position == timeBetwRequest2BotRow) {
                showNumberDialog("к-во мин.между одинак.запросами", SharedConfig.timeBetwRequest2Bot, value -> {
                    SharedConfig.timeBetwRequest2Bot = value;
                    SharedConfig.saveConfig();
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(timeBetwRequest2BotRow);
                    }
                });
            } else if (position == addButtonRow) {
                onAddCommandClicked();
            } else {
                // Check if it's an update key button
                for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                    if (position == updateKeyRow[i]) {
                        onUpdateKeyClicked(i);
                        break;
                    }
                }
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            if (position == subscriptionRow) {
                showSubscriptionLongTapMenu();
                return true;
            }
            for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                if (position == updateKeyRow[i]) {
                    showUpdateKeyContextMenu(i);
                    return true;
                }
            }
            return false;
        });

        return fragmentView;
    }

    private void showSubscriptionLongTapMenu() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setItems(new CharSequence[]{"Удалить подписку", "Перевыбрать бота"}, (dialog, which) -> {
            if (which == 0) {
                SharedConfig.v2raySubscriptionBots[subIndex] = subscription1 = "";
                SharedConfig.v2raySubscriptionBotIds[subIndex] = 0L;
                SharedConfig.v2rayRequestCounts[subIndex] = 1;
                for (int i = 0; i < 14; i++) {
                    SharedConfig.v2rayRequestArrays[subIndex][i] = "";
                    SharedConfig.v2rayKeyArrays[subIndex][i] = "";
                    requestValues[i] = "";
                    keyValues[i] = "";
                }
                if (subIndex == SharedConfig.v2raySubscriptionsCount - 1) {
                    SharedConfig.v2raySubscriptionsCount--;
                }
                SharedConfig.saveConfig();
                wasDialogOpened = false;
                updateRows();
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            } else {
                openDialogsActivity();
            }
        });
        builder.create().show();
    }

    /**
     * Show context menu for long tap on updateKeyRow[N] (spec 4.2).
     */
    private void showUpdateKeyContextMenu(int index) {
        if (getParentActivity() == null) return;
        String key = keyValues[index];
        boolean hasKey = !TextUtils.isEmpty(key);

        CharSequence[] options;
        if (hasKey) {
            options = new CharSequence[]{"Запросить/Обновить", "Показать ключ", "Отправить -> v2rayTg", "Удалить"};
        } else {
            options = new CharSequence[]{"Запросить/Обновить"};
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Ключ " + (index + 1));
        builder.setItems(options, (dialog, which) -> {
            if (!hasKey) {
                // Only one option: request/update
                onUpdateKeyClicked(index);
                return;
            }
            switch (which) {
                case 0: // Request/Update
                    onUpdateKeyClicked(index);
                    break;
                case 1: // Show key in dialog
                    new AlertDialog.Builder(getParentActivity())
                            .setTitle("Ключ " + (index + 1))
                            .setMessage(key)
                            .setPositiveButton("OK", null)
                            .create()
                            .show();
                    break;
                case 2: // Send to v2rayTg
                    sendToV2Ray(key);
                    break;
                case 3: // Delete
                    keyValues[index] = "";
                    SharedConfig.v2rayKeyArrays[subIndex][index] = "";
                    requestStates[index] = STATE_IDLE;
                    SharedConfig.saveConfig();
                    if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    break;
            }
        });
        builder.create().show();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }

        requestFieldManuallyTouched = false;

        // Auto-open DialogsActivity if subscription field is empty and we haven't opened it yet
        if (TextUtils.isEmpty(SharedConfig.v2raySubscriptionBots[subIndex]) && !wasDialogOpened) {
            wasDialogOpened = true;
            openDialogsActivity();
            return;
        }

        // Auto-fill request field from clipboard if returning from ChatActivity
        // choosingForRequestField: -1 to -14 = request fields (index 0-13)
        // choosingForRequestField: 1 to 14 = key fields (index 0-13)
        if (choosingForRequestField != 0) {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getParentActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        // Strip "nick: " prefix from each line, join with "; " for multi-line
                        String clipboardText = AutoConnectController.stripNickPrefixFromClipboard(
                                item.getText().toString().trim());
                        // Only auto-fill if clipboard has text
                        if (!TextUtils.isEmpty(clipboardText)) {
                            int index = Math.abs(choosingForRequestField) - 1; // Convert to 0-13

                            if (choosingForRequestField < 0) {
                                // Request field
                                String current = requestValues[index];
                                if (TextUtils.isEmpty(current)) {
                                    requestValues[index] = clipboardText;
                                } else if (current.endsWith(";")) {
                                    requestValues[index] = current + " " + clipboardText;
                                }
                                SharedConfig.v2rayRequestArrays[subIndex][index] = requestValues[index];
                            } else {
                                // Key field
                                String current = keyValues[index];
                                if (TextUtils.isEmpty(current)) {
                                    keyValues[index] = clipboardText;
                                } else if (current.endsWith(";")) {
                                    keyValues[index] = current + " " + clipboardText;
                                }
                                SharedConfig.v2rayKeyArrays[subIndex][index] = keyValues[index];
                            }
                            SharedConfig.saveConfig();
                            android.widget.Toast.makeText(getParentActivity(), "Text updated from clipboard", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore clipboard errors
            }
            choosingForRequestField = 0; // Reset after processing
        }
    }

    /**
     * Opens DialogsActivity to select a bot for subscription.
     */
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

    /**
     * Handle click on "+ Add Command" button - add new request row and open ChatActivity.
     */
    private void onAddCommandClicked() {
        if (SharedConfig.v2rayRequestCounts[subIndex] >= 14) {
            android.widget.Toast.makeText(getParentActivity(), "Maximum 14 commands allowed", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        int newIndex = SharedConfig.v2rayRequestCounts[subIndex];
        SharedConfig.v2rayRequestCounts[subIndex]++;
        SharedConfig.saveConfig();
        updateRows();

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }

        // Immediately open ChatActivity for the new row (spec point 5)
        choosingForRequestField = -(newIndex + 1);
        if (!TextUtils.isEmpty(SharedConfig.v2raySubscriptionBots[subIndex])) {
            openChatToViewMessages(SharedConfig.v2raySubscriptionBots[subIndex]);
        }
    }

    /**
     * Handle click on "Update Key" button - send request commands to bot.
     */
    private void onUpdateKeyClicked(int index) {
        // Values are already saved by TextWatcher.
        SharedConfig.saveConfig();

        // Get bot username
        String botUsername = subscription1;
        if (TextUtils.isEmpty(botUsername)) {
            android.widget.Toast.makeText(getParentActivity(), "Subscription is not set", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Get request text for the specified index
        String requestText = requestValues[index];

        if (TextUtils.isEmpty(requestText)) {
            requestStates[index] = STATE_EMPTY;
            if (listAdapter != null) {
                listAdapter.notifyItemChanged(updateKeyRow[index]);
            }
            return;
        }

        long lastRequestTime = SharedConfig.v2rayTimeLastRequestArrays[subIndex][index];
        long currentTime = System.currentTimeMillis();
        long diffMin = (currentTime - lastRequestTime) / (60 * 1000);

        if (diffMin < SharedConfig.timeBetwRequest2Bot) {
            long remainingMin = SharedConfig.timeBetwRequest2Bot - diffMin;
            android.widget.Toast.makeText(getParentActivity(), "До следующего запроса осталось " + remainingMin + " мин.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }

        // Send message to bot — only proceed to WAITING if send succeeded
        boolean sent = sendMessageToBot(botUsername, requestText, index);
        if (!sent) {
            return;
        }

        // Set state to WAITING and start timeout
        requestStates[index] = STATE_WAITING;
        if (timeoutRunnables[index] != null) {
            handler.removeCallbacks(timeoutRunnables[index]);
        }
        timeoutRunnables[index] = () -> {
            if (requestStates[index] == STATE_WAITING) {
                requestStates[index] = STATE_TIMEOUT;
                waitingForBotDialogId = 0;
                if (listAdapter != null) {
                    listAdapter.notifyItemChanged(updateKeyRow[index]);
                }
            }
        };
        handler.postDelayed(timeoutRunnables[index], 60000); // 1 minute
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(updateKeyRow[index]);
        }
    }

    /**
     * Sends commands to the bot. Returns true if the message was sent successfully.
     */
    private boolean sendMessageToBot(String botUsername, String message, int index) {
        try {
            if (TextUtils.isEmpty(message)) return false;

            MessagesController messagesController = MessagesController.getInstance(currentAccount);

            // Try to find bot user: first by username, then by stored dialog ID
            String username = botUsername.replace("@", "");
            TLRPC.User botUser = messagesController.getUser(username);
            if (botUser == null && SharedConfig.v2raySubscriptionBotIds[subIndex] != 0L) {
                botUser = messagesController.getUser(SharedConfig.v2raySubscriptionBotIds[subIndex]);
            }

            if (botUser == null) {
                android.widget.Toast.makeText(getParentActivity(), LocaleController.getString("V2RayBotNotFound", R.string.V2RayBotNotFound), android.widget.Toast.LENGTH_SHORT).show();
                return false;
            }

            // Update stored ID in case it was missing
            if (SharedConfig.v2raySubscriptionBotIds[subIndex] == 0L) {
                SharedConfig.v2raySubscriptionBotIds[subIndex] = botUser.id;
                SharedConfig.saveConfig();
            }

            // Store the bot dialog ID to wait for response
            waitingForBotDialogId = botUser.id;
            expectedBotUsername = botUsername;
            waitingForRequestIndex = index;

            // Delegate to controller to use its multi-threaded/handler logic
            ctrl().requestBotKeyManual(subIndex, index);

            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString("V2RayMessageSent", R.string.V2RayMessageSent), android.widget.Toast.LENGTH_SHORT).show();
            return true;

        } catch (Exception e) {
            android.widget.Toast.makeText(getParentActivity(), LocaleController.getString("V2RaySendError", R.string.V2RaySendError), android.widget.Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private boolean isV2RayKey(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.trim().toLowerCase();
        return t.startsWith("vless://") || t.startsWith("vmess://") || t.startsWith("ss://") ||
                t.startsWith("trojan://") || t.startsWith("shadowsocks://");
    }

    private void sendToV2Ray(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }

        try {
            // Get V2RayNekoBridge instance
            V2RayNekoBridge v2rayBridge = V2RayNekoBridge.getInstance(getParentActivity());

            // Initialize bridge if not already done
            v2rayBridge.initialize();

            // Set response listener to handle import result
            v2rayBridge.setOnV2RayResponseListener(new V2RayNekoBridge.OnV2RayResponseListener() {
                @Override
                public void onTestResult(int working, int total) {
                    if (getParentActivity() != null) {
                        String msg = String.format("V2Ray: %d/%d keys working", working, total);
                        android.widget.Toast.makeText(getParentActivity(), msg, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onImportResult(int imported, int working, int total) {
                    if (getParentActivity() != null) {
                        String msg = String.format("V2Ray: Imported %d, %d/%d working", imported, working, total);
                        android.widget.Toast.makeText(getParentActivity(), msg, android.widget.Toast.LENGTH_LONG).show();
                    }
                }

                @Override
                public void onError(String error) {
                    if (getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), "V2Ray error: " + error, android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onNoV2Ray() {
                    if (getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), "V2Ray module not available", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onNoAnswer() {
                    if (getParentActivity() != null) {
                        android.widget.Toast.makeText(getParentActivity(), "V2Ray: No response (timeout)", android.widget.Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onStatusResult(boolean isRunning, long lastPingMs, String errorMsg) {
                    // not needed in this context
                }

                @Override
                public void onStarted(boolean success, String errorMsg) {
                    // not needed in this context
                }

                @Override
                public void onSelectBestKeyResult(int working, String selectedGuid, long pingMs) {
                    // not needed in this context
                }

                @Override
                public void onTestRealPingDone(int working, int total) {
                    // not needed in this context
                }
            });

            // Send import command with the key
            v2rayBridge.sendImportKeysCommand(key, "");

            android.widget.Toast.makeText(getParentActivity(), "Sending key to V2Ray...", android.widget.Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            if (getParentActivity() != null) {
                android.widget.Toast.makeText(getParentActivity(), "Error sending to V2Ray: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) {
            return;
        }
        if (id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.messagesDidLoad) {
            long dialogId = (long) args[0];

            // Check if this is a response from the bot we're waiting for
            if (waitingForBotDialogId != 0 && dialogId == waitingForBotDialogId) {
                @SuppressWarnings("unchecked")
                ArrayList<MessageObject> messages = (id == NotificationCenter.didReceiveNewMessages)
                        ? (ArrayList<MessageObject>) args[1]
                        : (ArrayList<MessageObject>) args[2];

                if (messages == null) return;

                // Process messages from bot
                boolean foundKey = false;
                boolean receivedInbound = false;
                for (MessageObject messageObject : messages) {
                    if (messageObject == null || messageObject.isOut()) {
                        continue;
                    }
                    receivedInbound = true;

                    TLRPC.Message message = messageObject.messageOwner;
                    if (message != null && message.message != null) {
                        String allProfiles = parseAllV2RayProfiles(message.message);
                        if (allProfiles != null && !allProfiles.isEmpty()) {
                            // Found profiles - save to corresponding key field
                            int index = waitingForRequestIndex;
                            keyValues[index] = allProfiles;
                            SharedConfig.v2rayKeyArrays[subIndex][index] = keyValues[index];
                            SharedConfig.v2rayTimeLastRequestArrays[subIndex][index] = System.currentTimeMillis();
                            SharedConfig.saveConfig();

                            // Cancel timeout and reset state
                            if (timeoutRunnables[index] != null) {
                                handler.removeCallbacks(timeoutRunnables[index]);
                                timeoutRunnables[index] = null;
                            }
                            requestStates[index] = STATE_IDLE;

                            // Copy first key to clipboard for quick use
                            String firstKey = allProfiles.contains("\n")
                                    ? allProfiles.substring(0, allProfiles.indexOf('\n'))
                                    : allProfiles;
                            AndroidUtilities.addToClipboard(firstKey);

                            // Send all profiles to V2Ray
                            sendToV2Ray(allProfiles);

                            // Reset waiting state
                            waitingForBotDialogId = 0;
                            expectedBotUsername = "";
                            waitingForRequestIndex = 0;

                            // Update UI
                            final int rowToUpdate = updateKeyRow[index];
                            AndroidUtilities.runOnUIThread(() -> {
                                if (listAdapter != null) {
                                    listAdapter.notifyItemChanged(rowToUpdate);
                                }
                            });

                            int keyCount = allProfiles.split("\n").length;
                            String msg = keyCount > 1
                                    ? "Получено " + keyCount + " ключей от бота"
                                    : LocaleController.getString("V2RayProfileReceived", R.string.V2RayProfileReceived);
                            android.widget.Toast.makeText(getParentActivity(), msg, android.widget.Toast.LENGTH_SHORT).show();
                            foundKey = true;
                            break;
                        }
                    }
                }

                if (!foundKey && receivedInbound && id == NotificationCenter.didReceiveNewMessages) {
                    // Bot responded but no key found in the new messages
                    final int index = waitingForRequestIndex;
                    if (requestStates[index] != STATE_NO_KEYS) {
                        requestStates[index] = STATE_NO_KEYS;
                        final int row = updateKeyRow[index];
                        AndroidUtilities.runOnUIThread(() -> {
                            if (listAdapter != null) {
                                listAdapter.notifyItemChanged(row);
                            }
                        });
                        if (getParentActivity() != null) {
                            android.widget.Toast.makeText(getParentActivity(), "бот не дал ключей", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }

                    // We can keep waitingForBotDialogId != 0 and timeoutRunnables[index] active
                    // in case a second message with keys arrives, but the UI already reflects the failure.
                }

                // Do NOT reset waitingForBotDialogId here — the notification may fire for the
                // outgoing command message or for intermediate bot messages (header text without keys).
                // The 60-second timeout will handle the case where no key ever arrives.
            }
        }
    }

    private String parseAllV2RayProfiles(String message) {
        if (TextUtils.isEmpty(message)) return null;
        try {
            // Pattern to match v2ray-style URLs (vless, vmess, ss, trojan, shadowsocks)
            Pattern pattern = Pattern.compile("(vless|vmess|ss|trojan|shadowsocks)://[^\\s\\n]+", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(message);

            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String profile = matcher.group();
                if (!TextUtils.isEmpty(profile)) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(profile);
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            // Ignore regex errors
        }
        return null;
    }

    @Override
    public boolean didSelectDialogs(DialogsActivity fragment, ArrayList<MessagesStorage.TopicKey> dids, CharSequence message, boolean param, boolean notify, int scheduleDate, int scheduleRepeatPeriod, TopicsFragment topicsFragment) {
        String username = null;
        if (dids != null && !dids.isEmpty()) {
            long dialogId = dids.get(0).dialogId;
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = getMessagesController().getUser(dialogId);
                if (user != null) {
                    username = UserObject.getPublicUsername(user);
                }
            } else if (DialogObject.isChatDialog(dialogId)) {
                TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                if (chat != null) {
                    username = ChatObject.getPublicUsername(chat);
                }
            }
        }

        if (!TextUtils.isEmpty(username)) {
            // Subscription field selection
            if (choosingForSubscription == 1) {
                SharedConfig.v2raySubscriptionBots[subIndex] = subscription1 = "@" + username;
                // Save the dialog/user ID for reliable lookup later
                if (dids != null && !dids.isEmpty()) {
                    long dialogId = dids.get(0).dialogId;
                    if (DialogObject.isUserDialog(dialogId)) {
                        SharedConfig.v2raySubscriptionBotIds[subIndex] = dialogId;
                    }
                }
                if (subIndex >= SharedConfig.v2raySubscriptionsCount) {
                    SharedConfig.v2raySubscriptionsCount = subIndex + 1;
                }
                SharedConfig.saveConfig();
            }
        } else {
            // User didn't select anything - clear the subscription field
            if (choosingForSubscription == 1) {
                SharedConfig.v2raySubscriptionBots[subIndex] = subscription1 = "";
                SharedConfig.v2raySubscriptionBotIds[subIndex] = 0L;
                SharedConfig.saveConfig();
            }
        }

        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        fragment.finishFragment();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        subscriptionRow = rowCount++;
        botUsernameHintRow = rowCount++;
        timeBetwRequest2BotRow = rowCount++;

        // Request section - dynamic based on v2rayRequestCount
        for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
            requestRow[i] = rowCount++;
            if (i == 0) requestCommandsHintRow = rowCount++;
            updateKeyRow[i] = rowCount++;
        }

        // Add button row (always shown if count < 14)
        if (SharedConfig.v2rayRequestCounts[subIndex] < 14) {
            addButtonRow = rowCount++;
        } else {
            addButtonRow = -1;
        }

        // Info section
        infoRow = rowCount++;
    }

    private void showNumberDialog(String title, int currentValue, NumberCallback callback) {
        if (getParentActivity() == null) return;
        android.widget.EditText et = new android.widget.EditText(getParentActivity());
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(currentValue));
        et.setSelection(et.getText().length());
        int pad = AndroidUtilities.dp(16);
        et.setPadding(pad, pad / 2, pad, pad / 2);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        et.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        et.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated)));

        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try { callback.onNumber(Integer.parseInt(et.getText().toString())); } catch (Exception ignored) {}
                })
                .setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)
                .create().show();
    }

    interface NumberCallback { void onNumber(int value); }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            // Subscription and update key rows are clickable
            if (position == subscriptionRow || position == timeBetwRequest2BotRow || position == addButtonRow) {
                return true;
            }
            for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                if (position == updateKeyRow[i]) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0: // Text cell (headers, buttons)
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1: // Info cell
                default:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 2: // Edit text cell
                    view = new EditTextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 0: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    if (position == subscriptionRow) {
                        String value = TextUtils.isEmpty(subscription1) ? "None" : subscription1;
                        textCell.setTextAndValue("Подписка " + (subIndex + 1), value, true);
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                        textCell.setTag(Theme.key_windowBackgroundWhiteBlackText);
                    } else if (position == timeBetwRequest2BotRow) {
                        textCell.setTextAndValue("к-во мин.между одинак.запросами", String.valueOf(SharedConfig.timeBetwRequest2Bot), true);
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    } else if (position == addButtonRow) {
                        textCell.setText("+ Add Subscr.Bot command", true);
                        textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    } else {
                        // Update key buttons
                        for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                            if (position == updateKeyRow[i]) {
                                String key = keyValues[i];
                                String initialLabel = "Получить ключ по запросу " + (i + 1);

                                String value;
                                int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

                                if (requestStates[i] == STATE_WAITING) {
                                    value = "ожидаю";
                                    color = 0xfff44336; // Red
                                } else if (requestStates[i] == STATE_EMPTY) {
                                    value = "нет запроса";
                                    color = 0xfff44336; // Red
                                } else if (requestStates[i] == STATE_TIMEOUT) {
                                    value = initialLabel + " повторно";
                                    color = 0xfff44336; // Red
                                } else if (requestStates[i] == STATE_NO_KEYS) {
                                    value = "этих ключей пока нет";
                                    color = 0xfff44336; // Red
                                } else if (TextUtils.isEmpty(key)) {
                                    value = initialLabel;
                                    color = 0xfff44336; // Red
                                } else {
                                    value = key;
                                    // color is default
                                }

                                textCell.setTextAndValue(value, null, true);
                                textCell.setTextColor(color);
                                break;
                            }
                        }
                    }
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == infoRow) {
                        cell.setText(LocaleController.getString("V2RayPanelInfo", R.string.V2RayPanelInfo));
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == botUsernameHintRow) {
                        cell.setText(LocaleController.getString("V2RayBotUsernameHint", R.string.V2RayBotUsernameHint));
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == requestCommandsHintRow) {
                        cell.setText(LocaleController.getString("V2RayRequestCommandsHint", R.string.V2RayRequestCommandsHint));
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 2: {
                    EditTextSettingsCell editCell = (EditTextSettingsCell) holder.itemView;
                    editCell.getTextView().setFocusable(true);
                    editCell.getTextView().setFocusableInTouchMode(true);
                    editCell.getTextView().setEnabled(true);
                    editCell.setOnClickListener(null);

                    // Find which request row this is
                    int requestIndex = -1;
                    for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                        if (position == requestRow[i]) {
                            requestIndex = i;
                            break;
                        }
                    }

                    if (requestIndex >= 0) {
                        String text = requestValues[requestIndex];
                        String hint = "Запрос" + (requestIndex + 1) + " ключа";

                        editCell.setTextAndHint(text, hint, true);
                        editCell.getTextView().setTextIsSelectable(true);
                        editCell.getTextView().setCursorVisible(true);
                        editCell.getTextView().setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));

                        // Text change listener - save on change
                        editCell.getTextView().removeTextChangedListener((TextWatcher) editCell.getTextView().getTag());
                        TextWatcher textWatcher = new TextWatcher() {
                            @Override
                            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                            @Override
                            public void onTextChanged(CharSequence s, int start, int before, int count) {}
                            @Override
                            public void afterTextChanged(Editable s) {
                                int pos = holder.getAdapterPosition();
                                if (pos == RecyclerView.NO_POSITION) return;
                                String val = s.toString();

                                // Find index again from position
                                int idx = -1;
                                for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                                    if (pos == requestRow[i]) {
                                        idx = i;
                                        break;
                                    }
                                }

                                if (idx >= 0) {
                                    if (isV2RayKey(val)) {
                                        editCell.getTextView().setTextColor(0xfff44336); // Red error
                                    } else {
                                        editCell.getTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                                    }

                                    requestValues[idx] = val;
                                    SharedConfig.v2rayRequestArrays[subIndex][idx] = val;
                                    requestStates[idx] = STATE_IDLE;
                                    SharedConfig.saveConfig();
                                }
                            }
                        };
                        editCell.getTextView().addTextChangedListener(textWatcher);
                        editCell.getTextView().setTag(textWatcher);

                        // Touch listener - open ChatActivity for empty or ";" ending fields
                        final int touchPos = holder.getAdapterPosition();
                        final int touchIndex = requestIndex;
                        editCell.getTextView().setOnTouchListener((v, event) -> {
                            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                                String currentText = editCell.getTextView().getText().toString();
                                if (TextUtils.isEmpty(currentText) || currentText.endsWith(";")) {
                                    choosingForRequestField = -(touchIndex + 1); // -1, -2, ..., -14

                                    if (!TextUtils.isEmpty(SharedConfig.v2raySubscriptionBots[subIndex])) {
                                        openChatToViewMessages(SharedConfig.v2raySubscriptionBots[subIndex]);
                                        AndroidUtilities.hideKeyboard(editCell.getTextView());
                                    } else {
                                        android.widget.Toast.makeText(getParentActivity(), "Please select a bot first", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return true; // Consume event
                                }
                            }
                            return false;
                        });
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            // Headers = 0 (TextSettingsCell)
            // Info = 1 (TextInfoPrivacyCell)
            // Edit fields = 2 (EditTextSettingsCell)
            if (position == subscriptionRow || position == timeBetwRequest2BotRow || position == addButtonRow || isUpdateKeyRow(position)) {
                return 0;
            }
            if (position == infoRow || position == botUsernameHintRow || position == requestCommandsHintRow) {
                return 1;
            }
            return 2; // All request fields are edit text
        }

        private boolean isUpdateKeyRow(int position) {
            for (int i = 0; i < SharedConfig.v2rayRequestCounts[subIndex]; i++) {
                if (position == updateKeyRow[i]) return true;
            }
            return false;
        }
    }

    /**
     * Opens a chat to view messages and select text for request field.
     * @param username The username of the chat/bot (with or without @).
     */
    private void openChatToViewMessages(String username) {
        try {
            MessagesController messagesController = getMessagesController();
            String cleanUsername = username.replace("@", "");
            TLRPC.User botUser = messagesController.getUser(cleanUsername);

            if (botUser == null) {
                android.widget.Toast.makeText(getParentActivity(), "Bot not found: " + username, android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // Open chat activity
            Bundle args = new Bundle();
            args.putLong("user_id", botUser.id);

            ChatActivity chatActivity = new ChatActivity(args);
            presentFragment(chatActivity);

            android.widget.Toast.makeText(getParentActivity(), "Select messages in the chat, then copy text", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(getParentActivity(), "Error opening chat: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        themeDescriptions.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(null, ThemeDescription.FLAG_HINTTEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteHintText));
        return themeDescriptions;
    }
}