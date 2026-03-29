/*
 * ChannelsActivity - channel selector fragment (filtered, channels only).
 * Returns selected channel via ChannelsActivityDelegate.
 * Channel username returned WITHOUT "@" prefix.
 */

package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import com.teleray.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;

public class ChannelsActivity extends BaseFragment {

    public interface ChannelsActivityDelegate {
        /**
         * Called when a channel is selected.
         * @param chatId  positive channel chat ID
         * @param title   channel display title
         */
        void didSelectChannel(long chatId, String title);
    }

    private ChannelsActivityDelegate delegate;
    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private final ArrayList<TLRPC.Chat> allChannels = new ArrayList<>();
    private final ArrayList<TLRPC.Chat> filteredChannels = new ArrayList<>();

    public void setDelegate(ChannelsActivityDelegate d) {
        this.delegate = d;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString("ChannelsTitle", R.string.ChannelsTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem searchItem = menu.addItem(0, R.drawable.ic_ab_search).setIsSearchField(true);
        searchItem.setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
            }

            @Override
            public void onSearchCollapse() {
                filterChannels("");
            }

            @Override
            public void onTextChanged(EditText editText) {
                filterChannels(editText.getText().toString());
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString("Search", R.string.Search));

        fragmentView = new FrameLayout(context);
        FrameLayout frameLayout = (FrameLayout) fragmentView;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Channels list
        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() { return false; }
        });
        listView.setVerticalScrollBarEnabled(false);
        listView.setItemAnimator(null);
        listView.setLayoutAnimation(null);
        listView.setAdapter(listAdapter = new ListAdapter(context));
        frameLayout.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= filteredChannels.size()) return;
            TLRPC.Chat chat = filteredChannels.get(position);
            if (delegate != null) {
                delegate.didSelectChannel(chat.id, chat.title != null ? chat.title : "");
            }
            finishFragment();
        });

        loadChannels();
        return fragmentView;
    }

    private void loadChannels() {
        allChannels.clear();
        // Use the pre-filtered channels-only list from MessagesController
        ArrayList<TLRPC.Dialog> dialogs = MessagesController.getInstance(currentAccount).dialogsChannelsOnly;
        if (dialogs != null) {
            for (TLRPC.Dialog dialog : dialogs) {
                if (dialog == null) continue;
                if (DialogObject.isChatDialog(dialog.id)) {
                    TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialog.id);
                    if (chat != null && !chat.left && !chat.deactivated) {
                        allChannels.add(chat);
                    }
                }
            }
        }
        filteredChannels.clear();
        filteredChannels.addAll(allChannels);
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private void filterChannels(String query) {
        filteredChannels.clear();
        if (TextUtils.isEmpty(query)) {
            filteredChannels.addAll(allChannels);
        } else {
            String q = query.toLowerCase();
            for (TLRPC.Chat chat : allChannels) {
                if (chat.title != null && chat.title.toLowerCase().contains(q)) {
                    filteredChannels.add(chat);
                }
            }
        }
        if (listAdapter != null) listAdapter.notifyDataSetChanged();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public int getItemCount() {
            return filteredChannels.size();
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = new TextSettingsCell(mContext);
            view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextSettingsCell cell = (TextSettingsCell) holder.itemView;
            TLRPC.Chat chat = filteredChannels.get(position);
            String username = ChatObject.getPublicUsername(chat);
            String title = chat.title != null ? chat.title : "";
            String subtitle = TextUtils.isEmpty(username) ? "" : username;
            cell.setTextAndValue(title, subtitle, position < filteredChannels.size() - 1);
        }

        @Override
        public int getItemViewType(int position) {
            return 0;
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
