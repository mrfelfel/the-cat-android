package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

/**
 * One title: artwork header that settles as you scroll, overview, play buttons
 * and the episode list. Colours come from the title itself so the screen keeps
 * the identity the poster already established on the grid.
 */
public class CatMediaDetailFragment extends BaseFragment {

    private static final int SCRIM_TOP = 0xCC000000;
    private static final int SCRIM_BOTTOM = 0xEE000000;

    private final CatMediaModels.Title title;
    private android.view.View actionBarTitle;
    private TextView likeButton;
    private TextView seenButton;

    public CatMediaDetailFragment(CatMediaModels.Title title) {
        this.title = title;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackgroundColor(0);
        actionBar.setCastShadows(false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        // Only one title at a time: hidden until the artwork scrolls away.
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(title.name);
        actionBar.setTitleColor(0xFFFFFFFF);
        actionBar.setItemsColor(0xFFFFFFFF, false);
        actionBarTitle = actionBar.getTitleTextView();
        if (actionBarTitle != null) {
            actionBarTitle.setAlpha(0f);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(0xFF0B0D12);

        final ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(scroll, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // LayoutHelper sizes are in dp — feed it 340, not dp(340).
        final int headerHeightDp = 340;
        final int headerHeight = dp(headerHeightDp);
        final FrameLayout header = new FrameLayout(context);
        final CatMediaPoster artwork = new CatMediaPoster(context);
        artwork.setColors(title).setCornerRadius(0);
        header.addView(artwork, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, headerHeightDp));

        final HeaderScrim scrim = new HeaderScrim(context);
        header.addView(scrim, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, headerHeightDp));

        final LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(20), 0, dp(20), 0);
        header.addView(info, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.BOTTOM, 0, 0, 0, 18));

        final TextView heading = new TextView(context);
        heading.setText(title.name);
        heading.setTextColor(0xFFFFFFFF);
        heading.setTextSize(28);
        heading.setTypeface(AndroidUtilities.bold());
        heading.setShadowLayer(dp(2), 0, dp(1), 0x33000000);
        info.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView meta = new TextView(context);
        meta.setText(buildMeta());
        meta.setTextColor(0xD9FFFFFF);
        meta.setTextSize(13);
        info.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        content.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, headerHeightDp));

        final LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(6), dp(20), dp(24));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        final TextView play = actionButton(context, title.isSeries()
                ? LocaleController.getString(R.string.CatMediaPlayEpisode)
                : LocaleController.getString(R.string.CatMediaPlay), true);
        play.setOnClickListener(v -> openPlayer(CatMediaModels.nextEpisode(title)));
        buttons.addView(play, LayoutHelper.createLinear(0, 44, 1f));

        likeButton = actionButton(context, "", false);
        final LinearLayout.LayoutParams likeLp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44);
        if (LocaleController.isRTL) {
            likeLp.rightMargin = dp(10);
        } else {
            likeLp.leftMargin = dp(10);
        }
        buttons.addView(likeButton, likeLp);
        likeButton.setOnClickListener(v -> toggleLiked());

        seenButton = actionButton(context, "", false);
        final LinearLayout.LayoutParams seenLp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 44);
        if (LocaleController.isRTL) {
            seenLp.rightMargin = dp(10);
        } else {
            seenLp.leftMargin = dp(10);
        }
        buttons.addView(seenButton, seenLp);
        seenButton.setOnClickListener(v -> toggleSeen());

        refreshLibraryState();
        body.addView(buttons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 14, 0, 0));

        // Everything below the header fades and rises in, staggered, so the
        // screen does not arrive as one flat block.
        final View[] staged = new View[]{buttons};
        root.post(() -> {
            for (int i = 0; i < staged.length; i++) {
                staged[i].setAlpha(0f);
                staged[i].setTranslationY(dp(18));
                staged[i].animate().alpha(1f).translationY(0f).setStartDelay(90L * i)
                        .setDuration(360).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            }
        });

        final TextView overview = new TextView(context);
        overview.setText(title.overview);
        overview.setTextColor(0xCCFFFFFF);
        overview.setTextSize(14);
        overview.setLineSpacing(dp(3), 1f);
        final LinearLayout.LayoutParams overviewLp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        overviewLp.topMargin = dp(14);
        body.addView(overview, overviewLp);

        if (!title.people.isEmpty()) {
            final LinearLayout castRow = new LinearLayout(context);
            castRow.setOrientation(LinearLayout.VERTICAL);
            final LinearLayout.LayoutParams castLp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            castLp.topMargin = dp(18);
            for (CatMediaModels.Person person : title.people) {
                final TextView line = new TextView(context);
                line.setText(person.role + " · " + person.name);
                line.setTextColor(person.role.startsWith("Director") ? 0xCCFFFFFF : 0x99FFFFFF);
                line.setTextSize(13);
                line.setPadding(0, 0, 0, dp(3));
                castRow.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            body.addView(castRow, castLp);
        }

        if (title.isSeries()) {
            final TextView episodesLabel = sectionLabel(context, LocaleController.getString(R.string.CatMediaEpisodes));
            final LinearLayout.LayoutParams sectionLp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            sectionLp.topMargin = dp(26);
            body.addView(episodesLabel, sectionLp);
            body.addView(episodeList(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0));
        }

        content.addView(body, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // The header resists scrolling: the artwork travels slower than the
        // content, and the action bar fades its backdrop in behind the title.
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> {
            final float shift = Math.min(y, headerHeight) * 0.45f;
            artwork.setTranslationY(shift);
            scrim.setTranslationY(shift);
            info.setTranslationY(shift * 0.35f);
            final float fade = Math.min(1f, y / (float) dp(180));
            actionBar.setBackgroundColor((int) (fade * 255) << 24);
            actionBar.setCastShadows(fade > 0.6f);
            if (actionBarTitle != null) {
                actionBarTitle.setAlpha(fade);
            }
        });

        fragmentView = root;
        return root;
    }

    private String buildMeta() {
        final StringBuilder meta = new StringBuilder();
        meta.append(title.kindLabel()).append(" · ").append(title.year);
        if (title.rating > 0) {
            meta.append(" · ").append(String.format(java.util.Locale.US, "%.1f", title.rating));
        }
        if (!title.episodes.isEmpty() && !title.episodes.get(0).qualities.isEmpty()) {
            meta.append(" · ").append(title.episodes.get(0).qualities.get(0).label);
        }
        if (title.isSeries()) {
            meta.append(" · ").append(LocaleController.formatString(R.string.CatMediaSeasonCount, title.episodes.size()));
        } else {
            meta.append(" · ").append(title.totalMinutes()).append(" min");
        }
        return meta.toString();
    }

    private View episodeList(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < title.episodes.size(); i++) {
            final CatMediaModels.Episode episode = title.episodes.get(i);
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Theme.createRadSelectorDrawable(0x22FFFFFF, dp(12), dp(12)));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));

            final TextView number = new TextView(context);
            number.setText(String.valueOf(episode.number));
            number.setTextColor(0x66FFFFFF);
            number.setTextSize(18);
            number.setTypeface(AndroidUtilities.bold());
            number.setGravity(Gravity.CENTER);
            number.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            row.addView(number, LayoutHelper.createLinear(34, LayoutHelper.WRAP_CONTENT));

            final LinearLayout text = new LinearLayout(context);
            text.setOrientation(LinearLayout.VERTICAL);

            final TextView name = new TextView(context);
            name.setText(episode.title);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(14);
            name.setMaxLines(1);
            name.setEllipsize(TextUtils.TruncateAt.END);
            text.addView(name, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView duration = new TextView(context);
            final boolean seen = episode.progress >= 0.97f;
            if (seen) {
                duration.setText(LocaleController.getString(R.string.CatMediaSeen) + " · " + episode.minutes + " min");
            } else if (episode.progress > 0.01f) {
                duration.setText(Math.round(episode.progress * 100) + "% · " + episode.minutes + " min");
            } else {
                duration.setText(episode.minutes + " min");
            }
            duration.setTextColor(0x99FFFFFF);
            duration.setTextSize(12);
            text.addView(duration, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            row.addView(text, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f,
                    LocaleController.isRTL ? 0 : 10, 0, LocaleController.isRTL ? 10 : 0, 0));
            row.setOnClickListener(v -> openPlayer(episode));

            column.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        }
        return column;
    }

    private void toggleLiked() {
        final boolean liked = !CatMediaLibrary.get().isLiked(title.id);
        CatMediaLibrary.get().setLiked(title.id, liked);
        refreshLibraryState();
    }

    private void toggleSeen() {
        final CatMediaLibrary library = CatMediaLibrary.get();
        if (library.isSeen(title.id)) {
            library.clearSeen(title.id);
        } else {
            library.markSeen(title.id);
        }
        refreshLibraryState();
    }

    /** Keeps the two state buttons in step with the store, including colours. */
    private void refreshLibraryState() {
        if (likeButton == null || seenButton == null) {
            return;
        }
        final CatMediaLibrary library = CatMediaLibrary.get();
        final boolean liked = library.isLiked(title.id);
        final boolean seen = library.isSeen(title.id);

        styleToggle(likeButton, LocaleController.getString(R.string.CatMediaLiked), liked);
        styleToggle(seenButton, LocaleController.getString(R.string.CatMediaSeen), seen);
    }

    private void styleToggle(TextView button, String label, boolean on) {
        button.setText((on ? "\u2713 " : "") + label);
        if (on) {
            button.setTextColor(0xFF0B0D12);
            button.setBackground(Theme.createRoundRectDrawable(dp(22), 0xFFFFFFFF));
        } else {
            button.setTextColor(0xFFFFFFFF);
            button.setBackground(Theme.createRoundRectDrawable(dp(22), 0x2BFFFFFF));
        }
    }

    private void openPlayer(CatMediaModels.Episode episode) {
        if (episode != null) {
            presentFragment(new CatMediaPlayerFragment(title, episode));
        }
    }

    private static TextView actionButton(Context context, String text, boolean filled) {
        final TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(22), 0, dp(22), 0);
        if (filled) {
            tv.setTextColor(0xFF0B0D12);
            tv.setBackground(Theme.createRoundRectDrawable(dp(22), 0xFFFFFFFF));
        } else {
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackground(Theme.createRoundRectDrawable(dp(22), 0x2BFFFFFF));
        }
        return tv;
    }

    private static TextView sectionLabel(Context context, String text) {
        final TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(17);
        tv.setTypeface(AndroidUtilities.bold());
        return tv;
    }

    /** Darkens the artwork towards the bottom so overlaid text stays readable. */
    private static class HeaderScrim extends View {
        private final Paint paint = new Paint();

        HeaderScrim(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (paint.getShader() == null) {
                paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                        new int[]{SCRIM_TOP, 0x33000000, 0xF2090B10},
                        new float[]{0f, .42f, 1f}, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
