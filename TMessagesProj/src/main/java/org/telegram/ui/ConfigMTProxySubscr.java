/*
 * ConfigMTProxySubscr - Fragment for managing MTProxy channel subscription.
 * - subscriptionRow: channel selector via ChannelsActivity
 * - requestRow[i]: select proxy message from channel via ChatActivity
 * - updateKeyRow[i]: test result (ping / "недоступен"); tap to re-test
 * - Successful proxy is added to SharedConfig.proxyList
 */

package org.telegram.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.AutoConnectController;
import com.teleray.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigMTProxySubscr extends BaseFragment {

    private int subIndex = 0;
    private AutoConnectController ctrl() {
        return AutoConnectController.getInstance(currentAccount);
    }

    public ConfigMTProxySubscr() {
        super();
    }

    public ConfigMTProxySubscr(int index) {
        super();
        this.subIndex = index;
    }

    private ListAdapter listAdapter;
    private RecyclerListView listView;

    private int rowCount;

    // Rows
    private int subscriptionRow;
    private int[] requestRow = new int[14];
    private int[] updateKeyRow = new int[14];
    private int addButtonRow;
    private int infoRow;

    // Local copies of data
    private String[] requestValues = new String[14]; // MTProxy URLs
    private String[] testResults = new String[14];   // display in updateKeyRow

    // Navigation state: index (1-based) of the request field we're filling
    private int choosingForRequestIndex = 0;

    // Whether DialogsActivity/ChannelsActivity was auto-opened on enter
    private boolean channelSelectorOpened = false;

    // Handler for async ops
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        // Load saved data
        for (int i = 0; i < 14; i++) {
            requestValues[i] = SharedConfig.mtproxyRequestArrays[subIndex][i];
            testResults[i] = TextUtils.isEmpty(requestValues[i])
                    ? LocaleController.getString("MTProxyNoProxy", R.string.MTProxyNoProxy)
                    : LocaleController.getString("MTProxyTapToTest", R.string.MTProxyTapToTest);
        }
        updateRows();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString("MTProxySubscriptionSettings", R.string.MTProxySubscriptionSettings));
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
            @Override
            public boolean supportsPredictiveItemAnimations() { return false; }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter = new ListAdapter(context));

        // Short tap
        listView.setOnItemClickListener((view, position) -> {
            if (!view.isEnabled()) return;

            if (position == subscriptionRow) {
                openChannelsActivity();
            } else if (position == addButtonRow) {
                onAddProxyClicked();
            } else {
                for (int i = 0; i < SharedConfig.mtproxyRequestCounts[subIndex]; i++) {
                    if (position == requestRow[i]) {
                        onRequestRowClicked(i);
                        return;
                    }
                    if (position == updateKeyRow[i]) {
                        onUpdateKeyClicked(i);
                        return;
                    }
                }
            }
        });

        // Long tap
        listView.setOnItemLongClickListener((view, position) -> {
            if (position == subscriptionRow) {
                showSubscriptionLongTapMenu();
                return true;
            }
            for (int i = 0; i < SharedConfig.mtproxyRequestCounts[subIndex]; i++) {
                if (position == requestRow[i]) {
                    showRequestRowLongTapMenu(i);
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

        // Auto-open ChannelsActivity if channel not set yet
        if (SharedConfig.mtproxySubscriptionChatIds[subIndex] == 0 && !channelSelectorOpened) {
            channelSelectorOpened = true;
            openChannelsActivity();
            return;
        }

        // Process return from ChatActivity: fill requestValues[choosingForRequestIndex - 1]
        if (choosingForRequestIndex != 0) {
            int idx = choosingForRequestIndex - 1;
            choosingForRequestIndex = 0;

            try {
                android.content.ClipboardManager clipboard =
                        (android.content.ClipboardManager) getParentActivity()
                                .getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null && clipboard.hasPrimaryClip()) {
                    android.content.ClipData.Item item = clipboard.getPrimaryClip().getItemAt(0);
                    if (item != null && item.getText() != null) {
                        String clipText = item.getText().toString().trim();
                        String proxyUrl = parseMTProxyUrl(clipText);
                        if (!TextUtils.isEmpty(proxyUrl)) {
                            requestValues[idx] = proxyUrl;
                            SharedConfig.mtproxyRequestArrays[subIndex][idx] = proxyUrl;
                            SharedConfig.saveConfig();
                            // Immediately test the proxy
                            testProxy(idx);
                        } else {
                            android.widget.Toast.makeText(getParentActivity(),
                                    LocaleController.getString("MTProxyNotFound", R.string.MTProxyNotFound),
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            } catch (Exception e) {
                // ignore clipboard errors
            }
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        } else {
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        }
    }

    // --- Actions ---

    private void openChannelsActivity() {
        ChannelsActivity channelsActivity = new ChannelsActivity();
        channelsActivity.setDelegate((chatId, title) -> {
            SharedConfig.mtproxySubscriptionChatIds[subIndex] = chatId;
            // Ensure mtproxySubscriptionsCount is at least subIndex + 1
            if (SharedConfig.mtproxySubscriptionsCount <= subIndex) {
                SharedConfig.mtproxySubscriptionsCount = subIndex + 1;
            }
            SharedConfig.saveConfig();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        });
        presentFragment(channelsActivity);
    }

    private void showSubscriptionLongTapMenu() {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setItems(new CharSequence[]{"Удалить подписку", "Перевыбрать канал"}, (dialog, which) -> {
            if (which == 0) {
                // Delete: clear channel and all proxies
                SharedConfig.mtproxySubscriptionChatIds[subIndex] = 0;
                SharedConfig.mtproxyRequestCounts[subIndex] = 1;
                for (int i = 0; i < 14; i++) {
                    SharedConfig.mtproxyRequestArrays[subIndex][i] = "";
                    requestValues[i] = "";
                    testResults[i] = LocaleController.getString("MTProxyNoProxy", R.string.MTProxyNoProxy);
                }
                // Optional: decrease mtproxySubscriptionsCount if it was the last one
                if (subIndex == SharedConfig.mtproxySubscriptionsCount - 1) {
                    SharedConfig.mtproxySubscriptionsCount--;
                }
                SharedConfig.saveConfig();
                channelSelectorOpened = false;
                updateRows();
                if (listAdapter != null) listAdapter.notifyDataSetChanged();
            } else {
                // Reselect
                openChannelsActivity();
            }
        });
        builder.create().show();
    }

    private void onRequestRowClicked(int index) {
        if (SharedConfig.mtproxySubscriptionChatIds[subIndex] == 0) {
            android.widget.Toast.makeText(getParentActivity(),
                    LocaleController.getString("MTProxyChannelNotSet", R.string.MTProxyChannelNotSet),
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        choosingForRequestIndex = index + 1; // 1-based
        openChannelChat(SharedConfig.mtproxySubscriptionChatIds[subIndex]);
        android.widget.Toast.makeText(getParentActivity(),
                "Найди сообщение с MTProxy ссылкой и скопируй его",
                android.widget.Toast.LENGTH_LONG).show();
    }

    private void showRequestRowLongTapMenu(int index) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Прокси " + (index + 1));
        builder.setItems(new CharSequence[]{"Удалить", "Удалить и выбрать другой"}, (dialog, which) -> {
            requestValues[index] = "";
            SharedConfig.mtproxyRequestArrays[subIndex][index] = "";
            testResults[index] = LocaleController.getString("MTProxyNoProxy", R.string.MTProxyNoProxy);
            SharedConfig.saveConfig();
            if (listAdapter != null) listAdapter.notifyDataSetChanged();

            if (which == 1) {
                // Re-select
                choosingForRequestIndex = index + 1;
                openChannelChat(SharedConfig.mtproxySubscriptionChatIds[subIndex]);
                android.widget.Toast.makeText(getParentActivity(),
                        "Найди сообщение с MTProxy ссылкой и скопируй его",
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        builder.create().show();
    }

    private void onUpdateKeyClicked(int index) {
        if (TextUtils.isEmpty(requestValues[index])) {
            android.widget.Toast.makeText(getParentActivity(),
                    "Сначала выбери прокси из канала",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        testProxy(index);
    }

    private void onAddProxyClicked() {
        if (SharedConfig.mtproxyRequestArrays[subIndex].length >= 14) {
            // we should use a constant or check against the array length
        }
        if (SharedConfig.mtproxyRequestCounts[subIndex] >= 14) {
            android.widget.Toast.makeText(getParentActivity(),
                    "Максимум 14 прокси",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        int newIndex = SharedConfig.mtproxyRequestCounts[subIndex];
        SharedConfig.mtproxyRequestCounts[subIndex]++;
        SharedConfig.saveConfig();
        testResults[newIndex] = LocaleController.getString("MTProxyNoProxy", R.string.MTProxyNoProxy);
        updateRows();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();

        // Immediately open channel to pick a proxy
        if (SharedConfig.mtproxySubscriptionChatIds[subIndex] != 0) {
            choosingForRequestIndex = newIndex + 1;
            openChannelChat(SharedConfig.mtproxySubscriptionChatIds[subIndex]);
            android.widget.Toast.makeText(getParentActivity(),
                    "Найди сообщение с MTProxy ссылкой и скопируй его",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    // --- Helpers ---

    private void openChannelChat(long chatId) {
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        ChatActivity chatActivity = new ChatActivity(args);
        presentFragment(chatActivity);
    }

    private void testProxy(int index) {
        String url = requestValues[index];
        if (TextUtils.isEmpty(url)) return;

        testResults[index] = LocaleController.getString("MTProxyTesting", R.string.MTProxyTesting);
        if (listAdapter != null) listAdapter.notifyDataSetChanged();

        ctrl().testMTProxyManual(subIndex, index, time -> AndroidUtilities.runOnUIThread(() -> {
            if (time > 0) {
                testResults[index] = time + " мс";
                android.widget.Toast.makeText(getParentActivity(),
                        String.format(
                                LocaleController.getString("MTProxyAdded", R.string.MTProxyAdded),
                                time),
                        android.widget.Toast.LENGTH_SHORT).show();
            } else {
                testResults[index] = LocaleController.getString("MTProxyUnavailable", R.string.MTProxyUnavailable);
            }
            if (listAdapter != null) listAdapter.notifyDataSetChanged();
        }));
    }

    /**
     * Extracts the first MTProxy URL from a text string.
     * Matches: tg://proxy?... or https://t.me/proxy?...
     */
    private String parseMTProxyUrl(String text) {
        if (TextUtils.isEmpty(text)) return null;
        Pattern pattern = Pattern.compile(
                "(?:tg://proxy\\?|https?://t\\.me/proxy\\?)[^\\s]+",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) return matcher.group();
        return null;
    }

    /**
     * Parses an MTProxy URL into a ProxyInfo.
     * Supports tg://proxy?server=...&port=...&secret=...
     * and https://t.me/proxy?server=...&port=...&secret=...
     */
    private SharedConfig.ProxyInfo parseProxyInfo(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            Uri uri;
            if (url.startsWith("tg://proxy?")) {
                // Replace tg://proxy? with a parseable URL
                uri = Uri.parse("https://telegram.org/proxy?" + url.substring("tg://proxy?".length()));
            } else {
                uri = Uri.parse(url);
            }
            String server = uri.getQueryParameter("server");
            String portStr = uri.getQueryParameter("port");
            String secret = uri.getQueryParameter("secret");
            if (!TextUtils.isEmpty(server) && !TextUtils.isEmpty(portStr)) {
                int port = Integer.parseInt(portStr);
                return new SharedConfig.ProxyInfo(server, port, "", "", secret != null ? secret : "");
            }
        } catch (Exception e) {
            // ignore parse errors
        }
        return null;
    }

    private void updateRows() {
        rowCount = 0;
        subscriptionRow = rowCount++;
        for (int i = 0; i < SharedConfig.mtproxyRequestCounts[subIndex]; i++) {
            requestRow[i] = rowCount++;
            updateKeyRow[i] = rowCount++;
        }
        if (SharedConfig.mtproxyRequestCounts[subIndex] < 14) {
            addButtonRow = rowCount++;
        } else {
            addButtonRow = -1;
        }
        infoRow = rowCount++;
    }

    // --- Adapter ---

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == subscriptionRow || position == addButtonRow) return true;
            for (int i = 0; i < SharedConfig.mtproxyRequestCounts[subIndex]; i++) {
                if (position == requestRow[i] || position == updateKeyRow[i]) return true;
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
            if (viewType == 1) {
                view = new TextInfoPrivacyCell(mContext);
            } else {
                view = new TextSettingsCell(mContext);
                view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == 1) {
                TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                if (position == infoRow) {
                    cell.setText("Выбери канал с MTProxy конфигами. Тапни на строку прокси, чтобы открыть канал и скопировать ссылку прокси. Тапни на результат теста для повторного тестирования.");
                    cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                            R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                }
                return;
            }

            // TextSettingsCell
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;

            if (position == subscriptionRow) {
                String title;
                if (SharedConfig.mtproxySubscriptionChatIds[subIndex] != 0) {
                    org.telegram.tgnet.TLRPC.Chat chat = MessagesController.getInstance(currentAccount)
                            .getChat(SharedConfig.mtproxySubscriptionChatIds[subIndex]);
                    if (chat != null && chat.title != null) {
                        title = chat.title;
                    } else {
                        title = "Channel #" + SharedConfig.mtproxySubscriptionChatIds[subIndex];
                    }
                } else {
                    title = LocaleController.getString("MTProxySelectChannel", R.string.MTProxySelectChannel);
                }
                cell.setTextAndValue("Канал подписки", title, true);
                int colorKey = SharedConfig.mtproxySubscriptionChatIds[subIndex] == 0
                        ? Theme.key_text_RedRegular
                        : Theme.key_windowBackgroundWhiteGreenText;
                cell.setTextColor(Theme.getColor(colorKey));

            } else if (position == addButtonRow) {
                cell.setText(LocaleController.getString("MTProxyAddCommand", R.string.MTProxyAddCommand), true);
                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));

            } else {
                for (int i = 0; i < SharedConfig.mtproxyRequestCounts[subIndex]; i++) {
                    if (position == requestRow[i]) {
                        String val = TextUtils.isEmpty(requestValues[i])
                                ? LocaleController.getString("MTProxySelectFromChannel", R.string.MTProxySelectFromChannel)
                                : requestValues[i];
                        cell.setTextAndValue("Прокси " + (i + 1), val,
                                i < SharedConfig.mtproxyRequestCounts[subIndex] - 1 || addButtonRow != -1 || true);
                        int color = TextUtils.isEmpty(requestValues[i])
                                ? 0xfff44336
                                : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
                        cell.setTextColor(color);
                        break;
                    }
                    if (position == updateKeyRow[i]) {
                        String result = testResults[i];
                        cell.setTextAndValue("Тест " + (i + 1), result != null ? result : "—", true);
                        int color;
                        if (result == null
                                || result.equals(LocaleController.getString("MTProxyNoProxy", R.string.MTProxyNoProxy))
                                || result.equals(LocaleController.getString("MTProxyUnavailable", R.string.MTProxyUnavailable))
                                || result.equals("неверный URL")) {
                            color = 0xfff44336; // red
                        } else if (result.equals(LocaleController.getString("MTProxyTesting", R.string.MTProxyTesting))) {
                            color = 0xffFF9800; // orange
                        } else {
                            color = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText); // green
                        }
                        cell.setTextColor(color);
                        break;
                    }
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infoRow) return 1;
            return 0;
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> list = new ArrayList<>();
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        list.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
        list.add(new ThemeDescription(null, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        return list;
    }
}
