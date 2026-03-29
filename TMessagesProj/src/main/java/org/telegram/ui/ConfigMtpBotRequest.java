/*
 * ConfigMtpBotRequest — Panel for managing an MTProxy Bot subscription.
 *
 * Hybrid of ConfigSubscrBotRequest (bot selection + command fields) and
 * ConfigMTProxySubscr (proxy URL storage + test/add to proxyList).
 *
 * Each entry has:
 *   requestRow[i]  — command text sent to the bot (editable, like ConfigSubscrBotRequest)
 *   proxyRow[i]    — received tg://proxy URL + test result (tap to request → receive → test → add)
 */
package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.teleray.messenger.R;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AutoConnectController;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
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

import java.util.ArrayList;

public class ConfigMtpBotRequest extends BaseFragment
        implements DialogsActivity.DialogsActivityDelegate {

    private final int subIndex;

    private AutoConnectController ctrl() {
        return AutoConnectController.getInstance(currentAccount);
    }

    // ---- UI ----
    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private int rowCount;

    private int subscriptionRow;
    private int botUsernameHintRow;
    private int timeBetwRow;
    private int[] requestRow = new int[14];
    private int requestCommandsHintRow;
    private int[] proxyRow  = new int[14];
    private int addButtonRow;
    private int infoRow;

    // ---- State ----
    private String subscriptionBot = "";
    private String[] requestValues = new String[14];  // commands
    private String[] proxyValues   = new String[14];  // received proxy URLs

    private int  choosingForSubscription = 0;
    private int  choosingForRequestField = 0;  // -(idx+1) = request, +(idx+1) = proxy
    private boolean wasDialogOpened = false;

    private static final int STATE_IDLE    = 0;
    private static final int STATE_WAITING = 1;
    private static final int STATE_TIMEOUT = 2;
    private static final int STATE_NO_PROXY = 3;
    private static final int STATE_WORKING  = 4;
    private static final int STATE_FAILED   = 5;
    private static final int STATE_TESTING  = 6;

    private int[]      proxyStates        = new int[14];
    private String[]   proxyTestResults   = new String[14]; // "ping Xms" or "недоступен"
    private Runnable[] timeoutRunnables   = new Runnable[14];
    private Handler    handler            = new Handler(Looper.getMainLooper());

    public ConfigMtpBotRequest(int index) {
        super();
        this.subIndex = index;
    }

    // ================================================================
    // Fragment lifecycle
    // ================================================================

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        subscriptionBot = SharedConfig.mtpBotSubscriptionBots[subIndex];
        for (int i = 0; i < 14; i++) {
            requestValues[i] = SharedConfig.mtpBotRequestArrays[subIndex][i];
            proxyValues[i]   = SharedConfig.mtpBotProxyArrays[subIndex][i];
            proxyStates[i]   = STATE_IDLE;
        }
        ctrl().ensureV2RayBridge();
        updateRows();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        for (int i = 0; i < 14; i++) {
            if (timeoutRunnables[i] != null) {
                handler.removeCallbacks(timeoutRunnables[i]);
            }
        }
    }

    // ================================================================
    // View creation
    // ================================================================

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle("MTProxy Бот " + (subIndex + 1));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setTag(Theme.key_windowBackgroundGray);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override public boolean supportsPredictiveItemAnimations() { return false; }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter = new ListAdapter(context));

        listView.setOnItemClickListener((view, position, x, y) -> {
            if (!view.isEnabled()) return;
            if (position == subscriptionRow) {
                openBotDialogsActivity();
            } else if (position == timeBetwRow) {
                showNumberDialog("Мин. между одинак. запросами", SharedConfig.timeBetwRequest2BotMtp, value -> {
                    SharedConfig.timeBetwRequest2BotMtp = value;
                    SharedConfig.saveConfig();
                    if (listAdapter != null) listAdapter.notifyItemChanged(timeBetwRow);
                });
            } else if (position == addButtonRow) {
                onAddCommandClicked();
            } else {
                for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                    if (position == proxyRow[i]) {
                        onProxyRowClicked(i);
                        return;
                    }
                }
            }
        });

        listView.setOnItemLongClickListener((view, position) -> {
            if (position == subscriptionRow) {
                showSubscriptionLongTapMenu();
                return true;
            }
            for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                if (position == proxyRow[i]) {
                    showProxyRowContextMenu(i);
                    return true;
                }
            }
            return false;
        });

        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();

        // Auto-fill request field from clipboard when returning from ChatActivity
        // choosingForRequestField < 0 means we were choosing for requestRow[abs(val)-1]
        if (choosingForRequestField != 0) {
            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getParentActivity()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String raw = item.getText().toString().trim();
                        String clipboardText = AutoConnectController.stripNickPrefixFromClipboard(raw);
                        if (!TextUtils.isEmpty(clipboardText) && choosingForRequestField < 0) {
                            int index = Math.abs(choosingForRequestField) - 1;
                            String current = requestValues[index];
                            if (TextUtils.isEmpty(current)) {
                                requestValues[index] = clipboardText;
                            } else if (current.endsWith(";")) {
                                requestValues[index] = current + " " + clipboardText;
                            }
                            SharedConfig.mtpBotRequestArrays[subIndex][index] = requestValues[index];
                            SharedConfig.saveConfig();
                            Toast.makeText(getParentActivity(), "Команда обновлена из буфера", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Exception ignored) {}
            choosingForRequestField = 0;
        }

        // Auto-open bot selection if subscription is empty
        if (TextUtils.isEmpty(SharedConfig.mtpBotSubscriptionBots[subIndex]) && !wasDialogOpened) {
            wasDialogOpened = true;
            openBotDialogsActivity();
        }
    }

    // ================================================================
    // Click handlers
    // ================================================================

    private void openBotDialogsActivity() {
        choosingForSubscription = 1;
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_DEFAULT);
        DialogsActivity dialogsActivity = new DialogsActivity(args);
        dialogsActivity.setDelegate(ConfigMtpBotRequest.this);
        presentFragment(dialogsActivity);
    }

    private void onAddCommandClicked() {
        if (SharedConfig.mtpBotRequestCounts[subIndex] >= 14) {
            Toast.makeText(getParentActivity(), "Максимум 14 команд", Toast.LENGTH_SHORT).show();
            return;
        }
        int newIndex = SharedConfig.mtpBotRequestCounts[subIndex];
        SharedConfig.mtpBotRequestCounts[subIndex]++;
        SharedConfig.saveConfig();
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();

        // Immediately open bot chat for the new request field
        choosingForRequestField = -(newIndex + 1);
        if (!TextUtils.isEmpty(SharedConfig.mtpBotSubscriptionBots[subIndex])) {
            openChatToViewMessages(SharedConfig.mtpBotSubscriptionBots[subIndex]);
        }
    }

    private void onProxyRowClicked(int index) {
        String botUsername = subscriptionBot;
        if (TextUtils.isEmpty(botUsername)) {
            Toast.makeText(getParentActivity(), "Сначала выберите бота", Toast.LENGTH_SHORT).show();
            return;
        }
        String requestText = requestValues[index];
        if (TextUtils.isEmpty(requestText)) {
            Toast.makeText(getParentActivity(), "Сначала заполните поле команды", Toast.LENGTH_SHORT).show();
            return;
        }

        long lastReq = SharedConfig.mtpBotTimeLastRequestArrays[subIndex][index];
        long diffMin = (System.currentTimeMillis() - lastReq) / 60000L;
        if (diffMin < SharedConfig.timeBetwRequest2BotMtp) {
            long rem = SharedConfig.timeBetwRequest2BotMtp - diffMin;
            Toast.makeText(getParentActivity(), "До следующего запроса " + rem + " мин.", Toast.LENGTH_SHORT).show();
            return;
        }

        proxyStates[index] = STATE_WAITING;
        if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);

        if (timeoutRunnables[index] != null) handler.removeCallbacks(timeoutRunnables[index]);

        ctrl().requestMtpBotProxyManual(subIndex, index, new AutoConnectController.MtpBotProxyDelegate() {
            @Override
            public void onProxyReceived(String proxyUrl) {
                proxyValues[index] = proxyUrl;
                proxyStates[index] = STATE_TESTING;
                if (timeoutRunnables[index] != null) handler.removeCallbacks(timeoutRunnables[index]);
                AndroidUtilities.runOnUIThread(() -> {
                    if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);
                });
                testAndAddProxy(index, proxyUrl);
            }
            @Override
            public void onTimeout() {
                if (proxyStates[index] == STATE_WAITING) {
                    proxyStates[index] = STATE_TIMEOUT;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);
                    });
                    Toast.makeText(getParentActivity(), "Бот не ответил (таймаут)", Toast.LENGTH_SHORT).show();
                }
            }
        });

        Toast.makeText(getParentActivity(), "Запрос отправлен боту...", Toast.LENGTH_SHORT).show();

        // Fallback UI timeout (60s)
        timeoutRunnables[index] = () -> {
            if (proxyStates[index] == STATE_WAITING) {
                proxyStates[index] = STATE_TIMEOUT;
                if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);
            }
        };
        handler.postDelayed(timeoutRunnables[index], 61000);
    }

    private void testAndAddProxy(int index, String proxyUrl) {
        SharedConfig.ProxyInfo info = ctrl().parseMTProxyToInfoPublic(proxyUrl);
        if (info == null) {
            proxyStates[index] = STATE_FAILED;
            proxyTestResults[index] = "неверный URL";
            AndroidUtilities.runOnUIThread(() -> {
                if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);
            });
            return;
        }

        ConnectionsManager.getInstance(currentAccount).checkProxy(
                info.address, info.port,
                info.username != null ? info.username : "",
                info.password != null ? info.password : "",
                info.secret   != null ? info.secret   : "",
                time -> AndroidUtilities.runOnUIThread(() -> {
                    if (time > 0 && time < 5000) {
                        proxyStates[index] = STATE_WORKING;
                        proxyTestResults[index] = "ping " + time + "ms";
                        SharedConfig.addProxy(info);
                        SharedConfig.saveProxyList();
                        Toast.makeText(getParentActivity(),
                                "MTProxy добавлен: " + info.address + " (" + time + "ms)",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        proxyStates[index] = STATE_FAILED;
                        proxyTestResults[index] = "недоступен";
                    }
                    if (listAdapter != null) listAdapter.notifyItemChanged(proxyRow[index]);
                }));
    }

    private void showSubscriptionLongTapMenu() {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setItems(new CharSequence[]{"Удалить подписку", "Перевыбрать бота"}, (dialog, which) -> {
                    if (which == 0) {
                        clearSubscription();
                    } else {
                        openBotDialogsActivity();
                    }
                })
                .create().show();
    }

    private void clearSubscription() {
        SharedConfig.mtpBotSubscriptionBots[subIndex]   = subscriptionBot = "";
        SharedConfig.mtpBotSubscriptionBotIds[subIndex] = 0L;
        SharedConfig.mtpBotRequestCounts[subIndex]      = 1;
        for (int i = 0; i < 14; i++) {
            SharedConfig.mtpBotRequestArrays[subIndex][i] = requestValues[i] = "";
            SharedConfig.mtpBotProxyArrays[subIndex][i]   = proxyValues[i]   = "";
        }
        if (subIndex == SharedConfig.mtpBotSubscriptionsCount - 1) {
            SharedConfig.mtpBotSubscriptionsCount--;
        }
        SharedConfig.saveConfig();
        wasDialogOpened = false;
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void showProxyRowContextMenu(int index) {
        if (getParentActivity() == null) return;
        String proxy = proxyValues[index];
        boolean hasProxy = !TextUtils.isEmpty(proxy);
        CharSequence[] options = hasProxy
                ? new CharSequence[]{"Запросить/Обновить", "Показать URL", "Удалить"}
                : new CharSequence[]{"Запросить/Обновить"};

        new AlertDialog.Builder(getParentActivity())
                .setTitle("Прокси " + (index + 1))
                .setItems(options, (dialog, which) -> {
                    if (!hasProxy || which == 0) {
                        onProxyRowClicked(index);
                    } else if (which == 1) {
                        new AlertDialog.Builder(getParentActivity())
                                .setTitle("Прокси " + (index + 1))
                                .setMessage(proxy)
                                .setPositiveButton("OK", null)
                                .create().show();
                    } else if (which == 2) {
                        proxyValues[index] = "";
                        SharedConfig.mtpBotProxyArrays[subIndex][index] = "";
                        proxyStates[index] = STATE_IDLE;
                        proxyTestResults[index] = null;
                        SharedConfig.saveConfig();
                        if (listAdapter != null) listAdapter.notifyDataSetChanged();
                    }
                })
                .create().show();
    }

    // ================================================================
    // DialogsActivity delegate
    // ================================================================

    @Override
    public boolean didSelectDialogs(DialogsActivity fragment, ArrayList<MessagesStorage.TopicKey> dids,
                                    CharSequence message, boolean param, boolean notify,
                                    int scheduleDate, int scheduleRepeatPeriod, TopicsFragment topicsFragment) {
        String username = null;
        if (dids != null && !dids.isEmpty()) {
            long dialogId = dids.get(0).dialogId;
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = getMessagesController().getUser(dialogId);
                if (user != null) username = UserObject.getPublicUsername(user);
            } else if (DialogObject.isChatDialog(dialogId)) {
                TLRPC.Chat chat = getMessagesController().getChat(-dialogId);
                if (chat != null) username = ChatObject.getPublicUsername(chat);
            }
        }

        if (choosingForSubscription == 1) {
            if (!TextUtils.isEmpty(username)) {
                SharedConfig.mtpBotSubscriptionBots[subIndex] = subscriptionBot = "@" + username;
                if (dids != null && !dids.isEmpty()) {
                    long dialogId = dids.get(0).dialogId;
                    if (DialogObject.isUserDialog(dialogId)) {
                        SharedConfig.mtpBotSubscriptionBotIds[subIndex] = dialogId;
                    }
                }
                if (subIndex >= SharedConfig.mtpBotSubscriptionsCount) {
                    SharedConfig.mtpBotSubscriptionsCount = subIndex + 1;
                }
            } else {
                SharedConfig.mtpBotSubscriptionBots[subIndex]   = subscriptionBot = "";
                SharedConfig.mtpBotSubscriptionBotIds[subIndex] = 0L;
            }
            SharedConfig.saveConfig();
            choosingForSubscription = 0;
        }

        if (listAdapter != null) listAdapter.notifyDataSetChanged();
        fragment.finishFragment();
        return true;
    }

    // ================================================================
    // Rows
    // ================================================================

    private void updateRows() {
        rowCount = 0;
        subscriptionRow      = rowCount++;
        botUsernameHintRow   = rowCount++;
        timeBetwRow          = rowCount++;

        for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
            requestRow[i] = rowCount++;
            if (i == 0) requestCommandsHintRow = rowCount++;
            proxyRow[i]   = rowCount++;
        }

        addButtonRow = (SharedConfig.mtpBotRequestCounts[subIndex] < 14) ? rowCount++ : -1;
        infoRow      = rowCount++;
    }

    // ================================================================
    // Helpers
    // ================================================================

    private void openChatToViewMessages(String username) {
        try {
            String cleanUsername = username.replace("@", "");
            TLRPC.User botUser = getMessagesController().getUser(cleanUsername);
            if (botUser == null) {
                Toast.makeText(getParentActivity(), "Бот не найден: " + username, Toast.LENGTH_SHORT).show();
                return;
            }
            Bundle args = new Bundle();
            args.putLong("user_id", botUser.id);
            presentFragment(new ChatActivity(args));
            Toast.makeText(getParentActivity(), "Выберите сообщение с командой, скопируйте текст", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getParentActivity(), "Ошибка открытия чата", Toast.LENGTH_SHORT).show();
        }
    }

    interface NumberCallback { void onNumber(int value); }

    private void showNumberDialog(String title, int currentValue, NumberCallback callback) {
        if (getParentActivity() == null) return;
        android.widget.EditText et = new android.widget.EditText(getParentActivity());
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setText(String.valueOf(currentValue));
        et.setSelection(et.getText().length());
        int pad = AndroidUtilities.dp(16);
        et.setPadding(pad, pad / 2, pad, pad / 2);
        et.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        new AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    try { callback.onNumber(Integer.parseInt(et.getText().toString())); } catch (Exception ignored) {}
                })
                .setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null)
                .create().show();
    }

    // ================================================================
    // Adapter
    // ================================================================

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;
        ListAdapter(Context ctx) { mContext = ctx; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos == subscriptionRow || pos == timeBetwRow || pos == addButtonRow) return true;
            for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                if (pos == proxyRow[i]) return true;
            }
            return false;
        }

        @Override public int getItemCount() { return rowCount; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 0:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case 2:
                default:
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
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == subscriptionRow) {
                        String value = TextUtils.isEmpty(subscriptionBot) ? "Не выбран" : subscriptionBot;
                        cell.setTextAndValue("MTProxy Бот " + (subIndex + 1), value, true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    } else if (position == timeBetwRow) {
                        cell.setTextAndValue("Мин. между запросами", String.valueOf(SharedConfig.timeBetwRequest2BotMtp), true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    } else if (position == addButtonRow) {
                        cell.setText("+ Добавить команду боту", true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    } else {
                        // proxyRow[i]
                        for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                            if (position == proxyRow[i]) {
                                bindProxyRow(cell, i);
                                return;
                            }
                        }
                    }
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == botUsernameHintRow) {
                        cell.setText("Выберите Telegram-бота, который присылает MTProxy ссылки в ответ на команду");
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == requestCommandsHintRow) {
                        cell.setText("Команда боту (например /start или /get). Тап по полю команды открывает чат с ботом для выбора");
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == infoRow) {
                        cell.setText("Тап по строке прокси — отправляет команду боту, получает tg://proxy ссылку, тестирует и добавляет в список прокси Telegram");
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                case 2: {
                    // EditTextSettingsCell for requestRow[i]
                    EditTextSettingsCell editCell = (EditTextSettingsCell) holder.itemView;
                    editCell.getTextView().setFocusable(true);
                    editCell.getTextView().setFocusableInTouchMode(true);
                    editCell.getTextView().setEnabled(true);
                    editCell.setOnClickListener(null);

                    int requestIndex = -1;
                    for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                        if (position == requestRow[i]) { requestIndex = i; break; }
                    }
                    if (requestIndex < 0) break;

                    final int idx = requestIndex;
                    String text = requestValues[idx];
                    editCell.setTextAndHint(text, "Команда боту " + (idx + 1), true);
                    editCell.getTextView().setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));

                    editCell.getTextView().removeTextChangedListener(
                            (TextWatcher) editCell.getTextView().getTag());
                    TextWatcher tw = new TextWatcher() {
                        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                        @Override
                        public void afterTextChanged(Editable s) {
                            int pos = holder.getAdapterPosition();
                            if (pos == RecyclerView.NO_POSITION) return;
                            int index = -1;
                            for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                                if (pos == requestRow[i]) { index = i; break; }
                            }
                            if (index >= 0) {
                                requestValues[index] = s.toString();
                                SharedConfig.mtpBotRequestArrays[subIndex][index] = s.toString();
                                SharedConfig.saveConfig();
                            }
                        }
                    };
                    editCell.getTextView().addTextChangedListener(tw);
                    editCell.getTextView().setTag(tw);

                    editCell.getTextView().setOnTouchListener((v, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                            String cur = editCell.getTextView().getText().toString();
                            if (TextUtils.isEmpty(cur) || cur.endsWith(";")) {
                                choosingForRequestField = -(idx + 1);
                                if (!TextUtils.isEmpty(SharedConfig.mtpBotSubscriptionBots[subIndex])) {
                                    openChatToViewMessages(SharedConfig.mtpBotSubscriptionBots[subIndex]);
                                    AndroidUtilities.hideKeyboard(editCell.getTextView());
                                } else {
                                    Toast.makeText(getParentActivity(), "Сначала выберите бота", Toast.LENGTH_SHORT).show();
                                }
                                return true;
                            }
                        }
                        return false;
                    });
                    break;
                }
            }
        }

        private void bindProxyRow(TextSettingsCell cell, int i) {
            String label = "Получить прокси по запросу #" + (i + 1);
            String value;
            int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);

            switch (proxyStates[i]) {
                case STATE_WAITING:
                    value = "ожидаю ответа бота...";
                    color = 0xfff44336;
                    break;
                case STATE_TIMEOUT:
                    value = label + " (повторно)";
                    color = 0xfff44336;
                    break;
                case STATE_NO_PROXY:
                    value = "бот не прислал прокси";
                    color = 0xfff44336;
                    break;
                case STATE_TESTING:
                    value = TextUtils.isEmpty(proxyValues[i]) ? label : proxyValues[i];
                    value += " — тестируется...";
                    // normal (black) color — proxy received, ping in progress
                    break;
                case STATE_WORKING:
                    value = TextUtils.isEmpty(proxyValues[i]) ? label : proxyValues[i];
                    if (!TextUtils.isEmpty(proxyTestResults[i])) value += " — " + proxyTestResults[i];
                    color = 0xff4caf50; // green
                    break;
                case STATE_FAILED:
                    value = TextUtils.isEmpty(proxyValues[i]) ? label : proxyValues[i];
                    value += " — " + (TextUtils.isEmpty(proxyTestResults[i]) ? "недоступен" : proxyTestResults[i]);
                    // proxy was received — keep normal color; red only when nothing received
                    if (TextUtils.isEmpty(proxyValues[i])) color = 0xfff44336;
                    break;
                default: // STATE_IDLE
                    value = TextUtils.isEmpty(proxyValues[i]) ? label : proxyValues[i];
                    if (!TextUtils.isEmpty(proxyValues[i]) && !TextUtils.isEmpty(proxyTestResults[i]))
                        value += " — " + proxyTestResults[i];
                    if (TextUtils.isEmpty(proxyValues[i])) color = 0xfff44336;
                    break;
            }

            cell.setTextAndValue(value, null, true);
            cell.setTextColor(color);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == subscriptionRow || position == timeBetwRow || position == addButtonRow)
                return 0;
            for (int i = 0; i < SharedConfig.mtpBotRequestCounts[subIndex]; i++) {
                if (position == proxyRow[i]) return 0;
            }
            if (position == botUsernameHintRow || position == requestCommandsHintRow || position == infoRow)
                return 1;
            return 2; // requestRow[i] — EditText
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        return list;
    }
}
