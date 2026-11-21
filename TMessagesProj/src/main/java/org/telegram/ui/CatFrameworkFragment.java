package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.DividerCell;
import org.telegram.ui.Cells.EmptyCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerItemsEnterAnimator;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * The component framework: a catalogue of the building blocks this fork ships,
 * laid out exactly like a Telegram settings screen — {@link RecyclerListView}
 * with {@link HeaderCell} sections, {@link TextCell} / {@link TextSettingsCell}
 * rows, toggles, dividers and shadow sections.
 *
 * <p>Each row is a real component rendered live rather than a picture of one, so
 * what you see in this list is what a screen built from the framework gets. Rows
 * that need room to breathe — a hero, a player — open their own screen.
 */
public class CatFrameworkFragment extends BaseFragment {

    // Row kinds, mirroring how a settings screen splits its adapter.
    private static final int KIND_HEADER = 0;
    private static final int KIND_SHADOW = 1;
    private static final int KIND_EMPTY = 2;
    private static final int KIND_DIVIDER = 3;
    private static final int KIND_TEXT = 4;
    private static final int KIND_SETTINGS = 5;
    private static final int KIND_DETAIL = 6;
    private static final int KIND_CHECK = 7;
    private static final int KIND_CHECKBOX = 8;
    private static final int KIND_RADIO = 9;
    private static final int KIND_INFO = 10;

    private RecyclerListView listView;
    private ListAdapter listAdapter;
    private LinearLayoutManager layoutManager;
    private RecyclerItemsEnterAnimator enterAnimator;
    private final List<Row> rows = new ArrayList<>();

    /** Demo state for the toggle rows — a framework component also demos itself. */
    private boolean toggleOn = true;
    private boolean checkboxOn = true;
    private int radioIndex = 0;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(R.string.CatMedia));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);
        buildRows();

        final FrameLayout frameLayout = new FrameLayout(context);
        fragmentView = frameLayout;
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context) {
            @Override
            public Integer getSelectorColor(int position) {
                return getThemedColor(Theme.key_listSelector);
            }
        };
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener(this::onRowClicked);
        frameLayout.addView(listView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));

        // Same polish a settings page gets: the bar tints to the list, and the
        // rows arrive in a wave instead of snapping in.
        actionBar.setAdaptiveBackground(listView);
        enterAnimator = new RecyclerItemsEnterAnimator(listView, true);
        listView.post(() -> enterAnimator.showItemsAnimated(0));

        return frameLayout;
    }

    /**
     * The catalogue. Order here is the order on screen; every section is a
     * HeaderCell followed by its components and a ShadowSectionCell, the same
     * rhythm a settings page uses so blocks read as blocks.
     */
    private void buildRows() {
        rows.clear();

        rows.add(header(R.string.CatFrameworkSectionCells));
        rows.add(text(R.string.CatFrameworkTextCell, R.string.CatFrameworkTextCellValue, R.drawable.msg_filled_storageusage, 0xFF4F85F6));
        rows.add(settings(R.string.CatFrameworkTextSettingsCell, R.string.CatFrameworkValue));
        rows.add(detail(R.string.CatFrameworkTextDetailCell, R.string.CatFrameworkTextDetailHint));
        rows.add(shadow());

        rows.add(header(R.string.CatFrameworkSectionControls));
        rows.add(check(R.string.CatFrameworkCheckCell));
        rows.add(checkbox(R.string.CatFrameworkCheckBoxCell));
        rows.add(radio(R.string.CatFrameworkRadioCell));
        rows.add(shadow());

        rows.add(header(R.string.CatFrameworkSectionLayout));
        rows.add(new Row(KIND_EMPTY, 0, 0));
        rows.add(new Row(KIND_DIVIDER, 0, 0));
        rows.add(info(R.string.CatFrameworkInfoCell));
        rows.add(shadow());

        rows.add(header(R.string.CatFrameworkSectionMedia));
        rows.add(settings(R.string.CatFrameworkHeroCell, R.string.CatFrameworkOpen));
        rows.add(settings(R.string.CatFrameworkPosterCell, R.string.CatFrameworkOpen));
        rows.add(new Row(KIND_DIVIDER, 0, 0));
        rows.add(settings(R.string.CatFrameworkProgressCell, R.string.CatFrameworkProgressValue));
        rows.add(shadow());

        rows.add(header(R.string.CatFrameworkSectionPlayback));
        rows.add(settings(R.string.CatFrameworkPlayerCell, R.string.CatFrameworkOpen));
        rows.add(info(R.string.CatFrameworkPlayerNote));
        rows.add(shadow());

        rows.add(header(R.string.CatFrameworkSectionPages));
        rows.add(text(R.string.CatFrameworkDetailPage, R.string.CatFrameworkOpen, R.drawable.msg_filled_datausage, 0xFF55CA47));
        rows.add(text(R.string.CatFrameworkPlayerPage, R.string.CatFrameworkOpen, R.drawable.msg_filled_sdcard, 0xFFF09F1B));
        rows.add(shadow());
    }

    private void onRowClicked(View view, int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        final Row row = rows.get(position);
        switch (row.kind) {
            case KIND_CHECK:
                toggleOn = !toggleOn;
                listAdapter.notifyItemChanged(position);
                break;
            case KIND_CHECKBOX:
                checkboxOn = !checkboxOn;
                listAdapter.notifyItemChanged(position);
                break;
            case KIND_RADIO:
                radioIndex = position % 2;
                listAdapter.notifyItemChanged(position);
                break;
            case KIND_SETTINGS:
            case KIND_TEXT: {
                // Media and playback rows open their demo screen; everything
                // else is a live component that has nothing more to show.
                if (row.titleRes == R.string.CatFrameworkHeroCell
                        || row.titleRes == R.string.CatFrameworkPosterCell
                        || row.titleRes == R.string.CatFrameworkDetailPage) {
                    final CatMediaModels.Title sample = CatMediaModels.title("last-ember");
                    if (sample != null) {
                        presentFragment(new CatMediaDetailFragment(sample));
                    }
                } else if (row.titleRes == R.string.CatFrameworkPlayerCell
                        || row.titleRes == R.string.CatFrameworkPlayerPage) {
                    final CatMediaModels.Title sample = CatMediaModels.title("last-ember");
                    final CatMediaModels.Episode episode = sample == null ? null : CatMediaModels.nextEpisode(sample);
                    if (episode != null) {
                        presentFragment(new CatMediaPlayerFragment(sample, episode));
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    // --- row builders ---

    private static Row header(int titleRes) {
        return new Row(KIND_HEADER, titleRes, 0);
    }

    private static Row shadow() {
        return new Row(KIND_SHADOW, 0, 0);
    }

    private static Row text(int titleRes, int valueRes, int iconRes, int iconColor) {
        final Row row = new Row(KIND_TEXT, titleRes, valueRes);
        row.iconRes = iconRes;
        row.iconColor = iconColor;
        return row;
    }

    private static Row settings(int titleRes, int valueRes) {
        return new Row(KIND_SETTINGS, titleRes, valueRes);
    }

    private static Row detail(int titleRes, int detailRes) {
        return new Row(KIND_DETAIL, titleRes, detailRes);
    }

    private static Row check(int titleRes) {
        return new Row(KIND_CHECK, titleRes, 0);
    }

    private static Row checkbox(int titleRes) {
        return new Row(KIND_CHECKBOX, titleRes, 0);
    }

    private static Row radio(int titleRes) {
        return new Row(KIND_RADIO, titleRes, 0);
    }

    private static Row info(int textRes) {
        return new Row(KIND_INFO, textRes, 0);
    }

    private static final class Row {
        final int kind;
        final int titleRes;
        final int valueRes;
        int iconRes;
        int iconColor;

        Row(int kind, int titleRes, int valueRes) {
            this.kind = kind;
            this.titleRes = titleRes;
            this.valueRes = valueRes;
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int kind = holder.getItemViewType();
            return kind != KIND_HEADER && kind != KIND_SHADOW
                    && kind != KIND_EMPTY && kind != KIND_DIVIDER
                    && kind != KIND_INFO;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case KIND_HEADER:
                    view = new HeaderCell(context);
                    break;
                case KIND_SHADOW:
                    view = new ShadowSectionCell(context);
                    break;
                case KIND_EMPTY:
                    view = new EmptyCell(context, dp(52));
                    break;
                case KIND_DIVIDER:
                    view = new DividerCell(context);
                    break;
                case KIND_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case KIND_CHECK:
                    view = new org.telegram.ui.Cells.TextCheckCell(context);
                    break;
                case KIND_CHECKBOX:
                    view = new CheckBoxCell(context, 1);
                    break;
                case KIND_RADIO:
                    view = new RadioCell(context);
                    break;
                case KIND_DETAIL:
                    view = new org.telegram.ui.Cells.TextDetailCell(context);
                    break;
                case KIND_SETTINGS:
                    view = new TextSettingsCell(context);
                    break;
                case KIND_TEXT:
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Row row = rows.get(position);
            switch (row.kind) {
                case KIND_HEADER:
                    ((HeaderCell) holder.itemView).setText(LocaleController.getString(row.titleRes));
                    break;
                case KIND_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(row.titleRes));
                    break;
                case KIND_SETTINGS: {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(LocaleController.getString(row.titleRes), LocaleController.getString(row.valueRes),
                            position != rows.size() - 1);
                    break;
                }
                case KIND_TEXT: {
                    final TextCell cell = (TextCell) holder.itemView;
                    if (row.iconRes != 0) {
                        cell.setTextAndColorfulIcon(LocaleController.getString(row.titleRes), row.iconRes, row.iconColor, true);
                    } else {
                        cell.setTextAndValue(LocaleController.getString(row.titleRes), LocaleController.getString(row.valueRes), true);
                    }
                    break;
                }
                case KIND_DETAIL: {
                    final org.telegram.ui.Cells.TextDetailCell cell =
                            (org.telegram.ui.Cells.TextDetailCell) holder.itemView;
                    cell.setTextAndValue(LocaleController.getString(row.titleRes), LocaleController.getString(row.valueRes), true);
                    break;
                }
                case KIND_CHECK: {
                    final org.telegram.ui.Cells.TextCheckCell cell =
                            (org.telegram.ui.Cells.TextCheckCell) holder.itemView;
                    cell.setTextAndCheck(LocaleController.getString(row.titleRes), toggleOn, true);
                    break;
                }
                case KIND_CHECKBOX: {
                    final CheckBoxCell cell = (CheckBoxCell) holder.itemView;
                    cell.setText(LocaleController.getString(row.titleRes), "", checkboxOn, true);
                    break;
                }
                case KIND_RADIO: {
                    final RadioCell cell = (RadioCell) holder.itemView;
                    cell.setText(LocaleController.getString(row.titleRes), radioIndex == 1, false);
                    break;
                }
                default:
                    break;
            }
        }
    }
}
