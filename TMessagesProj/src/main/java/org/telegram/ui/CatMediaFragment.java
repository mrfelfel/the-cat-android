package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import java.util.Random;

public class CatMediaFragment extends BaseFragment {

    private static final int COLOR_BACKDROP = 0xFF10131A;
    private static final int COLOR_ACCENT = 0xFFE8B23C;

    private final String displayName;

    public CatMediaFragment() {
        this(null);
    }

    public CatMediaFragment(String displayName) {
        super();
        this.displayName = displayName;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.CatMedia));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(COLOR_BACKDROP);

        final ScrollView scrollView = new ScrollView(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                if (paint.getShader() == null && getWidth() > 0) {
                    paint.setShader(new LinearGradient(0, 0, getWidth(), dp(260), 0xFF25385E, COLOR_BACKDROP, Shader.TileMode.CLAMP));
                }
                canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
                super.onDraw(canvas);
            }
        };
        scrollView.setWillNotDraw(false);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(96), 0, dp(24));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        final LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        content.addView(greeting(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 20, 0, 4));
        content.addView(heroCard(context), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        content.addView(rail(context, LocaleController.getString(R.string.CatMediaContinueWatching), MEDIA_ITEMS, true));
        content.addView(rail(context, LocaleController.getString(R.string.CatMediaTopPicks), MEDIA_ITEMS_REVERSED, false));
        content.addView(rail(context, LocaleController.getString(R.string.CatMediaGenre), GENRE_ITEMS, false));

        return root;
    }

    private View greeting(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(18), 0, dp(18), 0);

        final TextView title = new TextView(context);
        title.setText(LocaleController.formatString(R.string.CatMediaGreeting, resolveDisplayName()));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24);
        title.setTypeface(AndroidUtilities.bold());
        column.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.CatMediaSubtitle));
        subtitle.setTextColor(0x99FFFFFF);
        subtitle.setTextSize(14);
        column.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

        return column;
    }

    private View heroCard(Context context) {
        final FrameLayout card = new FrameLayout(context);
        card.setBackground(Theme.createRoundRectDrawable(dp(18), 0xFF1A2233));

        final PosterView poster = new PosterView(context, 3, dp(96), dp(140), 0xFFB2432E, 0xFF5A1D14);
        card.addView(poster, LayoutHelper.createFrame(96, 140, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final LinearLayout info = new LinearLayout(context);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(16), 0, 0, 0);
        card.addView(info, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 120, 0, 14, 0));

        final TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.CatMediaHeroTitle));
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(17);
        title.setTypeface(AndroidUtilities.bold());
        title.setMaxLines(2);
        info.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView meta = new TextView(context);
        meta.setText(LocaleController.getString(R.string.CatMediaHeroMeta));
        meta.setTextColor(0x99FFFFFF);
        meta.setTextSize(12);
        info.addView(meta, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 3, 0, 0));

        final TextView description = new TextView(context);
        description.setText(LocaleController.getString(R.string.CatMediaHeroDescription));
        description.setTextColor(0xCCFFFFFF);
        description.setTextSize(13);
        description.setMaxLines(3);
        description.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        final LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(pill(context, LocaleController.getString(R.string.CatMediaPlay), true));
        final View save = pill(context, LocaleController.getString(R.string.CatMediaSave), false);
        final LinearLayout.LayoutParams saveLp = LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, dp(36));
        saveLp.leftMargin = dp(8);
        buttons.addView(save, saveLp);
        info.addView(buttons, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 10, 0, 0));

        final LinearLayout.LayoutParams lp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 0);
        lp.leftMargin = dp(18);
        lp.rightMargin = dp(18);
        card.setLayoutParams(lp);
        return card;
    }

    private View pill(Context context, String text, boolean filled) {
        final TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(18), 0, dp(18), 0);
        tv.setMinHeight(dp(36));
        if (filled) {
            tv.setTextColor(0xFF10131A);
            tv.setBackground(Theme.createRoundRectDrawable(dp(18), COLOR_ACCENT));
        } else {
            tv.setTextColor(0xFFFFFFFF);
            tv.setBackground(Theme.createRoundRectDrawable(dp(18), 0x22FFFFFF));
        }
        return tv;
    }

    private View rail(Context context, String title, MediaItem[] items, boolean progress) {
        final LinearLayout section = new LinearLayout(context);
        section.setOrientation(LinearLayout.VERTICAL);

        final TextView header = new TextView(context);
        header.setText(title);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(17);
        header.setTypeface(AndroidUtilities.bold());
        header.setPadding(dp(18), dp(16), dp(18), dp(8));
        section.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final HorizontalScrollView hsv = new HorizontalScrollView(context);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setClipToPadding(false);
        hsv.setClipChildren(false);
        hsv.setPadding(dp(18), 0, dp(18), 0);

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < items.length; i++) {
            final LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);

            final PosterView poster = new PosterView(context, items[i].seed, dp(118), dp(166), items[i].colorA, items[i].colorB);
            card.addView(poster, LayoutHelper.createLinear(118, 166));

            final TextView itemTitle = new TextView(context);
            itemTitle.setText(items[i].title);
            itemTitle.setTextColor(0xFFFFFFFF);
            itemTitle.setTextSize(13);
            itemTitle.setMaxLines(1);
            itemTitle.setEllipsize(TextUtils.TruncateAt.END);
            card.addView(itemTitle, LayoutHelper.createLinear(118, LayoutHelper.WRAP_CONTENT, 0, 7, 0, 0));

            final TextView itemMeta = new TextView(context);
            itemMeta.setText(items[i].meta);
            itemMeta.setTextColor(0x88FFFFFF);
            itemMeta.setTextSize(11);
            card.addView(itemMeta, LayoutHelper.createLinear(118, LayoutHelper.WRAP_CONTENT));

            if (progress) {
                final ProgressBar bar = new ProgressBar(context, items[i].progress);
                card.addView(bar, LayoutHelper.createLinear(118, 6, 0, 7, 0, 0));
            }

            final LinearLayout.LayoutParams lp = LayoutHelper.createLinear(118, LayoutHelper.WRAP_CONTENT);
            lp.rightMargin = dp(12);
            row.addView(card, lp);
        }
        hsv.addView(row, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        section.addView(hsv, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return section;
    }

    private String resolveDisplayName() {
        if (!TextUtils.isEmpty(displayName)) {
            return displayName;
        }
        final TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
        if (user != null && !TextUtils.isEmpty(user.first_name)) {
            return user.first_name;
        }
        return LocaleController.getString(R.string.CatMediaYou);
    }

    private static class ProgressBar extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float progress;

        ProgressBar(Context context, float progress) {
            super(context);
            this.progress = progress;
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int h = dp(3);
            final float top = (getHeight() - h) / 2f;
            paint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(0, top, getWidth(), top + h, h / 2f, h / 2f, paint);
            paint.setColor(COLOR_ACCENT);
            canvas.drawRoundRect(0, top, Math.max(h, getWidth() * progress), top + h, h / 2f, h / 2f, paint);
        }
    }

    private static final class MediaItem {
        final String title;
        final String meta;
        final float progress;
        final int colorA;
        final int colorB;
        final int seed;

        MediaItem(String title, String meta, float progress, int colorA, int colorB, int seed) {
            this.title = title;
            this.meta = meta;
            this.progress = progress;
            this.colorA = colorA;
            this.colorB = colorB;
            this.seed = seed;
        }
    }

    private static final MediaItem[] MEDIA_ITEMS = new MediaItem[]{
            new MediaItem("The Last Ember", "S2 · E4", .63f, 0xFFB2432E, 0xFF5A1D14, 11),
            new MediaItem("Night Watch", "Series", .31f, 0xFF2B4A8C, 0xFF0E1B3A, 22),
            new MediaItem("Copenhagen", "Film · 2024", 0f, 0xFF6B5B4A, 0xFF2A221A, 33),
            new MediaItem("Quiet Stone", "Documentary", 0f, 0xFF3A5B52, 0xFF11241F, 44),
            new MediaItem("Blue Fist", "S1 · E9", .12f, 0xFF25456E, 0xFF0A1626, 55),
    };

    private static final MediaItem[] MEDIA_ITEMS_REVERSED = new MediaItem[]{
            new MediaItem("After Hours", "Series", 0f, 0xFF7A3F5E, 0xFF2E1524, 66),
            new MediaItem("To the North", "Film · 2023", 0f, 0xFF4A5F7A, 0xFF1A232E, 77),
            new MediaItem("Signal", "Thriller", 0f, 0xFF6E2F35, 0xFF290E11, 88),
            new MediaItem("Small Cities", "Documentary", 0f, 0xFF5C6B3A, 0xFF222816, 99),
    };

    private static final MediaItem[] GENRE_ITEMS = new MediaItem[]{
            new MediaItem("Drama", "", 0f, 0xFF7C3B2A, 0xFF34120C, 111),
            new MediaItem("Comedy", "", 0f, 0xFF8A6A1F, 0xFF3A2C0B, 122),
            new MediaItem("Sci-Fi", "", 0f, 0xFF2E4B7A, 0xFF0F1B2E, 133),
            new MediaItem("Music", "", 0f, 0xFF6B3B8A, 0xFF291132, 144),
    };

    private static final class PosterView extends View {
        private final int posterWidth;
        private final int posterHeight;
        private final int colorA;
        private final int colorB;
        private final int seed;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint vignette = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();

        PosterView(Context context, int seed, int posterWidth, int posterHeight, int colorA, int colorB) {
            super(context);
            this.seed = seed;
            this.posterWidth = posterWidth;
            this.posterHeight = posterHeight;
            this.colorA = colorA;
            this.colorB = colorB;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(posterWidth, posterHeight);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            final int w = getWidth();
            final int h = getHeight();
            final float radius = dp(12);

            clip.rewind();
            rect.set(0, 0, w, h);
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(clip);

            fill.setShader(new LinearGradient(0, 0, w, h, colorA, colorB, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, fill);
            fill.setShader(null);

            final Random random = new Random(seed);
            for (int i = 0; i < 6; i++) {
                blob.setColor(0x1AFFFFFF);
                canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h, w * (.18f + random.nextFloat() * .32f), blob);
            }

            vignette.setShader(new LinearGradient(0, 0, 0, h, 0x00000000, 0x44000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, vignette);
            vignette.setShader(null);
            canvas.restore();
        }
    }
}
