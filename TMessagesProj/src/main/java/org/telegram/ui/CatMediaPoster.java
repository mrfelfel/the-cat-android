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
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;

import java.util.Random;

/**
 * Placeholder artwork drawn from a title's two colours and a seed, so the page
 * needs no image assets and every title still looks distinct. Only the base
 * gradient is seeded — the text and badge drawn over it are not, so posters
 * read the same on the grid and in the detail header.
 */
public class CatMediaPoster extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    private int colorTop;
    private int colorBottom;
    private int seed;
    private String label;
    private float cornerRadiusDp = 12;

    public CatMediaPoster(Context context) {
        super(context);
    }

    public CatMediaPoster setColors(CatMediaModels.Title title) {
        colorTop = title.colorTop;
        colorBottom = title.colorBottom;
        seed = title.seed;
        label = initials(title.name);
        invalidate();
        return this;
    }

    public CatMediaPoster setColors(CatMediaModels.Category category) {
        colorTop = category.titles.isEmpty() ? 0xFF2A2A2A : category.titles.get(0).colorTop;
        colorBottom = 0xFF121212;
        seed = category.id.hashCode();
        label = initials(category.name);
        invalidate();
        return this;
    }

    public CatMediaPoster setCornerRadius(float dpValue) {
        cornerRadiusDp = dpValue;
        invalidate();
        return this;
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        final float radius = dp(cornerRadiusDp);

        clip.rewind();
        rect.set(0, 0, w, h);
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);

        fill.setShader(new LinearGradient(0, 0, w * .35f, h, colorTop, colorBottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, fill);
        fill.setShader(null);

        final Random random = new Random(seed);
        for (int i = 0; i < 5; i++) {
            blob.setColor(0x14FFFFFF);
            canvas.drawCircle(random.nextFloat() * w, random.nextFloat() * h,
                    w * (.22f + random.nextFloat() * .38f), blob);
        }

        shade.setShader(new LinearGradient(0, h * .35f, 0, h, 0x00000000, 0x66000000, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, shade);
        shade.setShader(null);

        if (!TextUtils.isEmpty(label)) {
            text.setColor(0x22FFFFFF);
            text.setTypeface(AndroidUtilities.bold());
            text.setTextSize(w * .42f);
            text.setTextAlign(Paint.Align.CENTER);
            final float baseline = h * .58f - (text.descent() + text.ascent()) / 2f;
            canvas.drawText(label, w / 2f, baseline, text);
        }
        canvas.restore();
    }

    private static String initials(String name) {
        if (TextUtils.isEmpty(name)) {
            return "";
        }
        final String[] words = name.trim().split("\\s+");
        if (words.length == 1) {
            return words[0].substring(0, Math.min(2, words[0].length())).toUpperCase();
        }
        return (words[0].charAt(0) + "" + words[1].charAt(0)).toUpperCase();
    }
}
