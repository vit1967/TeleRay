/*
 * SubscriptionsConfigFragment — UI layer for the auto-connect settings panel.
 *
 * All loop/test business logic lives in AutoConnectController (a per-account singleton that
 * survives fragment lifecycle). This fragment registers itself as UIDelegate while visible
 * and simply forwards button clicks to the controller — the loop keeps running when the user
 * navigates away to chats, contacts, etc.
 */

package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AutoConnectController;
import org.telegram.messenger.LocaleController;
import com.teleray.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ActionButtonCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class SubscriptionsConfigFragment extends BaseFragment
        implements AutoConnectController.UIDelegate {

    // Subscription types
    public static final int TYPE_V2RAY   = 0;
    public static final int TYPE_MTPROXY = 1;
    public static final int TYPE_MTPBOT  = 2;

    private static class SubscriptionItem {
        final int type;
        final String label;
        final int index;
        SubscriptionItem(int type, String label, int index) {
            this.type = type; this.label = label; this.index = index;
        }
    }

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    private int rowCount;

    // Dynamic subscription rows
    private final ArrayList<Integer> subscriptionRows = new ArrayList<>();

    // Fixed rows
    private int minWrkKeysRow;
    private int minWrkMTProxyRow;
    private int timeTstMinuteRow;
    private int addSubscriptionRow;
    private int v2raySettingsHintRow;
    private int autoUpdateSettingsHintRow;
    private int mtproxyAutoRow;
    private int testBestV2RayKeyRow;
    private int v2raySendKeyRow;
    private int v2rayTimerRow;
    private int v2rayAnswerRow;

    private final ArrayList<SubscriptionItem> subscriptions = new ArrayList<>();

    // Local copies of display text — updated by UIDelegate callbacks
    private String v2rayAnswerText = "";
    private String v2rayTimerText  = "";

    // ---- Convenience accessor ----

    private AutoConnectController ctrl() {
        return AutoConnectController.getInstance(currentAccount);
    }

    // ---- Fragment lifecycle ----

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        syncSubscriptions();
        ctrl().ensureV2RayBridge();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        // Detach UI — loop keeps running independently inside AutoConnectController
        ctrl().setDelegate(null);
        super.onFragmentDestroy();
    }

    // ---- UIDelegate implementation ----

    @Override
    public void onAnswerUpdate(String text) {
        v2rayAnswerText = text;
        notifyRow(v2rayAnswerRow);
    }

    @Override
    public void onTimerUpdate(String text) {
        v2rayTimerText = text;
        notifyRow(v2rayTimerRow);
    }

    @Override
    public void onLoopStateChanged() {
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    // ---- View creation ----

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString("V2RaySettings", R.string.V2RaySettings));
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

        listView.setOnItemClickListener((view, position) -> {
            if (!view.isEnabled()) return;

            for (int i = 0; i < subscriptionRows.size(); i++) {
                if (position == subscriptionRows.get(i)) {
                    openSubscriptionPanel(subscriptions.get(i).type, subscriptions.get(i).index);
                    return;
                }
            }

            if (position == addSubscriptionRow) {
                showAddSubscriptionDialog();
            } else if (position == minWrkKeysRow) {
                showNumberDialog(
                        LocaleController.getString("AutoMinWrkKeys", R.string.AutoMinWrkKeys),
                        SharedConfig.autoMinWrkKeys,
                        val -> { SharedConfig.autoMinWrkKeys = val; SharedConfig.saveConfig(); notifyRow(minWrkKeysRow); });
            } else if (position == minWrkMTProxyRow) {
                showNumberDialog(
                        LocaleController.getString("AutoMinWrkMTProxy", R.string.AutoMinWrkMTProxy),
                        SharedConfig.autoMinWrkMTProxy,
                        val -> { SharedConfig.autoMinWrkMTProxy = val; SharedConfig.saveConfig(); notifyRow(minWrkMTProxyRow); });
            } else if (position == timeTstMinuteRow) {
                showNumberDialog(
                        LocaleController.getString("AutoTimeTstMinute", R.string.AutoTimeTstMinute),
                        SharedConfig.autoTimeTstMinute,
                        val -> { if (val > 0) { SharedConfig.autoTimeTstMinute = val; SharedConfig.saveConfig(); notifyRow(timeTstMinuteRow); } });
            } else if (position == mtproxyAutoRow) {
                onMtproxyAutoButtonClicked();
            } else if (position == testBestV2RayKeyRow) {
                ctrl().sendSelectBestKeyForced();
            } else if (position == v2raySendKeyRow) {
                onAutoButtonClicked();
            }
        });

        return fragmentView;
    }

    private void notifyRow(int row) {
        if (listAdapter != null) listAdapter.notifyItemChanged(row);
    }

    @Override
    public void onResume() {
        super.onResume();
        syncSubscriptions();
        // Re-attach as delegate and pull latest text from controller
        AutoConnectController c = ctrl();
        c.setDelegate(this);
        v2rayAnswerText = c.getAnswerText();
        v2rayTimerText  = c.getTimerText();
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    // ---- Subscription sync ----

    private void syncSubscriptions() {
        subscriptions.clear();
        for (int i = 0; i < SharedConfig.v2raySubscriptionsCount; i++) {
            if (!TextUtils.isEmpty(SharedConfig.v2raySubscriptionBots[i])) {
                subscriptions.add(new SubscriptionItem(TYPE_V2RAY, SharedConfig.v2raySubscriptionBots[i], i));
            }
        }
        for (int i = 0; i < SharedConfig.mtproxySubscriptionsCount; i++) {
            if (SharedConfig.mtproxySubscriptionChatIds[i] != 0) {
                subscriptions.add(new SubscriptionItem(TYPE_MTPROXY,
                        "MTProxy Канал #" + (i + 1), i));
            }
        }
        for (int i = 0; i < SharedConfig.mtpBotSubscriptionsCount; i++) {
            if (!TextUtils.isEmpty(SharedConfig.mtpBotSubscriptionBots[i])) {
                subscriptions.add(new SubscriptionItem(TYPE_MTPBOT,
                        SharedConfig.mtpBotSubscriptionBots[i], i));
            }
        }
        updateRows();
    }

    private void updateRows() {
        rowCount = 0;
        subscriptionRows.clear();
        for (int i = 0; i < subscriptions.size(); i++) {
            subscriptionRows.add(rowCount++);
        }
        addSubscriptionRow  = rowCount++;
        v2raySettingsHintRow = rowCount++;

        minWrkKeysRow       = rowCount++;
        minWrkMTProxyRow    = rowCount++;
        timeTstMinuteRow    = rowCount++;
        autoUpdateSettingsHintRow = rowCount++;

        mtproxyAutoRow         = rowCount++;
        testBestV2RayKeyRow    = rowCount++;
        v2raySendKeyRow        = rowCount++;
        v2rayTimerRow       = rowCount++;
        v2rayAnswerRow      = rowCount++;
    }

    // ---- Dialogs ----

    private void showAddSubscriptionDialog() {
        if (getParentActivity() == null) return;
        new AlertDialog.Builder(getParentActivity())
                .setTitle("Тип подписки")
                .setItems(new CharSequence[]{
                        LocaleController.getString("SubscriptionTypeV2Ray",   R.string.SubscriptionTypeV2Ray),
                        LocaleController.getString("SubscriptionTypeMTProxy", R.string.SubscriptionTypeMTProxy),
                        "MTProxy Бот"
                }, (dialog, which) -> {
                    if (which == 0) {
                        int index = SharedConfig.v2raySubscriptionsCount;
                        if (index >= 5) {
                            Toast.makeText(getParentActivity(), "Максимум 5 V2Ray подписок", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openSubscriptionPanel(TYPE_V2RAY, index);
                    } else if (which == 1) {
                        int index = SharedConfig.mtproxySubscriptionsCount;
                        if (index >= 5) {
                            Toast.makeText(getParentActivity(), "Максимум 5 MTProxy подписок", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openSubscriptionPanel(TYPE_MTPROXY, index);
                    } else {
                        int index = SharedConfig.mtpBotSubscriptionsCount;
                        if (index >= 5) {
                            Toast.makeText(getParentActivity(), "Максимум 5 MTProxy Бот подписок", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        openSubscriptionPanel(TYPE_MTPBOT, index);
                    }
                })
                .create().show();
    }

    private void openSubscriptionPanel(int type, int index) {
        if (type == TYPE_V2RAY) {
            presentFragment(new ConfigSubscrBotRequest(index));
        } else if (type == TYPE_MTPROXY) {
            presentFragment(new ConfigMTProxySubscr(index));
        } else {
            presentFragment(new ConfigMtpBotRequest(index));
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

    // ---- Button handlers — delegate to controller ----

    private void onAutoButtonClicked() {
        if (ctrl().isAutoLoopRunning()) {
            ctrl().stopLoopTstConnect();
        } else {
            ctrl().startLoopTstConnect();
        }
        if (listAdapter != null) listAdapter.notifyItemChanged(v2raySendKeyRow);
    }

    private void onMtproxyAutoButtonClicked() {
        if (ctrl().isAutoLoopRunning()) return; // main loop running — button should be greyed
        if (ctrl().isMtproxyLoopRunning()) {
            ctrl().stopMtproxyLoop();
        } else {
            ctrl().startMtproxyLoop();
        }
        if (listAdapter != null) listAdapter.notifyItemChanged(mtproxyAutoRow);
    }

    // ---- Adapter ----

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;
        ListAdapter(Context context) { mContext = context; }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int pos = holder.getAdapterPosition();
            if (pos == addSubscriptionRow || pos == v2raySendKeyRow || pos == testBestV2RayKeyRow
                    || pos == minWrkKeysRow || pos == minWrkMTProxyRow || pos == timeTstMinuteRow) return true;
            if (pos == mtproxyAutoRow) return !ctrl().isAutoLoopRunning();
            for (int row : subscriptionRows) { if (pos == row) return true; }
            return false;
        }

        @Override public int getItemCount() { return rowCount; }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case 2:
                    view = new ActionButtonCell(mContext);
                    break;
                case 1:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case 2: {
                    ActionButtonCell cell = (ActionButtonCell) holder.itemView;
                    if (position == mtproxyAutoRow) {
                        String label = ctrl().isMtproxyLoopRunning() ? "Auto upd.MTproxy" : "turn Auto MTproxy";
                        cell.setText(label, false);
                        cell.setAlpha(ctrl().isAutoLoopRunning() ? 0.4f : 1.0f);
                    } else if (position == testBestV2RayKeyRow) {
                        cell.setText("tst v2rayTg→лучш.кл.", false);
                    } else if (position == v2raySendKeyRow) {
                        String label;
                        if (ctrl().isAutoLoopRunning()) {
                            label = LocaleController.getString("V2RayButStop", R.string.V2RayButStop);
                        } else if (ctrl().hadError()) {
                            label = LocaleController.getString("V2RayButError", R.string.V2RayButError);
                        } else {
                            label = LocaleController.getString("V2RayButSend", R.string.V2RayButSend);
                        }
                        cell.setText(label, false);
                    } else if (position == addSubscriptionRow) {
                        cell.setText(LocaleController.getString("AddSubscription", R.string.AddSubscription), true);
                    }
                    break;
                }
                case 1: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == v2rayTimerRow) {
                        cell.setText(TextUtils.isEmpty(v2rayTimerText) ? "" : v2rayTimerText);
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == v2rayAnswerRow) {
                        cell.setText(TextUtils.isEmpty(v2rayAnswerText)
                                ? LocaleController.getString("V2RayAnswer", R.string.V2RayAnswer)
                                : v2rayAnswerText);
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == v2raySettingsHintRow) {
                        cell.setText(LocaleController.getString("V2RaySettingsHint", R.string.V2RaySettingsHint));
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    } else if (position == autoUpdateSettingsHintRow) {
                        cell.setText(LocaleController.getString("AutoUpdateSettingsHint", R.string.AutoUpdateSettingsHint));
                        cell.setBackgroundDrawable(Theme.getThemedDrawable(mContext,
                                R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    }
                    break;
                }
                default: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == minWrkKeysRow) {
                        cell.setTextAndValue(LocaleController.getString("AutoMinWrkKeys", R.string.AutoMinWrkKeys),
                                String.valueOf(SharedConfig.autoMinWrkKeys), true);
                    } else if (position == minWrkMTProxyRow) {
                        cell.setTextAndValue(LocaleController.getString("AutoMinWrkMTProxy", R.string.AutoMinWrkMTProxy),
                                String.valueOf(SharedConfig.autoMinWrkMTProxy), true);
                    } else if (position == timeTstMinuteRow) {
                        cell.setTextAndValue(LocaleController.getString("AutoTimeTstMinute", R.string.AutoTimeTstMinute),
                                String.valueOf(SharedConfig.autoTimeTstMinute), true);
                    } else {
                        for (int i = 0; i < subscriptionRows.size(); i++) {
                            if (position == subscriptionRows.get(i)) {
                                SubscriptionItem item = subscriptions.get(i);
                                String typeLabel;
                                if (item.type == TYPE_V2RAY)   typeLabel = "V2Ray Бот";
                                else if (item.type == TYPE_MTPBOT) typeLabel = "MTProxy Бот";
                                else                            typeLabel = "MTProxy Канал";
                                cell.setTextAndValue(typeLabel, item.label, true);
                                cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                                return;
                            }
                        }
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == addSubscriptionRow || position == v2raySendKeyRow || position == mtproxyAutoRow || position == testBestV2RayKeyRow) return 2;
            if (position == v2rayAnswerRow || position == v2rayTimerRow || position == v2raySettingsHintRow || position == autoUpdateSettingsHintRow) return 1;
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
