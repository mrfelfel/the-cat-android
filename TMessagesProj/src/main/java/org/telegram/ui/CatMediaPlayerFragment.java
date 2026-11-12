package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Components.VideoSeekBarView;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen playback for one episode of a catalogue title.
 *
 * <p>Wraps the messenger's own {@link VideoPlayer} rather than a separate video
 * library: it is the same ExoPlayer setup that plays chat videos, so codecs,
 * HLS support, audio focus and hardware decoding all come along, and the app
 * ships no second player.
 */
public class CatMediaPlayerFragment extends BaseFragment {

    private CatMediaModels.Title title;
    private CatMediaModels.Episode episode;

    private VideoPlayer videoPlayer;
    private TextureView textureView;
    private VideoSeekBarView seekBar;
    private TextView positionView;
    private TextView durationView;
    private ImageView playPause;
    private View controls;
    private ControlsGradient controlsShade;
    private TextView errorView;
    private TextView qualityButton;
    private TextView speedButton;

    private CatMediaModels.Quality selectedQuality;
    private final List<CatMediaModels.Quality> qualities = new ArrayList<>();
    private float speed;

    private boolean dragging;
    private boolean controlsVisible = true;
    private boolean firstFrameShown;
    private final Runnable hideControlsRunnable = () -> setControlsVisible(false);
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (videoPlayer == null || getParentActivity() == null) {
                return;
            }
            if (!dragging) {
                updateProgress();
            }
            AndroidUtilities.runOnUIThread(this, 250);
        }
    };

    public CatMediaPlayerFragment(CatMediaModels.Title title, CatMediaModels.Episode episode) {
        this.title = title;
        this.episode = episode;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setCastShadows(false);
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        // Fullscreen player: back arrow only. The episode name lives in the
        // controls at the bottom, where it belongs on a player.
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(null);
        actionBar.setItemsColor(Color.WHITE, false);
        actionBar.setItemsBackgroundColor(0x33000000, false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(context);
        root.addView(textureView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));
        textureView.setAlpha(0f);

        controlsShade = new ControlsGradient(context);
        root.addView(controlsShade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 176, Gravity.BOTTOM));

        controls = buildControls(context);
        root.addView(controls, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        // Tap anywhere on the video toggles the controls rather than the poster,
        // which keeps the hit area out of the seek bar's way.
        textureView.setOnClickListener(v -> setControlsVisible(!controlsVisible));

        qualities.clear();
        qualities.addAll(episode.qualities);
        selectedQuality = CatMediaLibrary.get().quality(episode);
        if (selectedQuality == null) {
            selectedQuality = episode.best();
        }
        updateQualityLabel();
        speed = CatMediaLibrary.get().speed();
        if (speedButton != null) {
            speedButton.setText(speedLabel(speed));
        }
        resumeAt = CatMediaLibrary.get().position(title.id, episode);
        if (resumeAt < 0) {
            resumeAt = episode.progress > 0.01f ? episode.progress : -1;
        }
        createPlayer();
        startPlayback();
        AndroidUtilities.runOnUIThread(ticker);

        return fragmentView = root;
    }

    /** Creates the player, prepares the selected stream and starts it. */
    private void createPlayer() {
        videoPlayer = new VideoPlayer();
        videoPlayer.setTextureView(textureView);
        videoPlayer.setDelegate(delegate);
        videoPlayer.setPlaybackSpeed(speed);
    }

    private void startPlayback() {
        if (videoPlayer == null) {
            return;
        }
        videoPlayer.preparePlayer(Uri.parse(selectedQuality != null ? selectedQuality.url : episode.url), "other");
        videoPlayer.setPlayWhenReady(true);
    }

    private void rebuildPlayer() {
        createPlayer();
        startPlayback();
    }

    private final VideoPlayer.VideoPlayerDelegate delegate = new VideoPlayer.VideoPlayerDelegate() {
        @Override
        public void onStateChanged(boolean playWhenReady, int playbackState) {
            if (playbackState == 3) {
                updatePlayPauseIcon(true);
                scheduleHide();
            } else if (playbackState == 4) {
                updatePlayPauseIcon(false);
                setControlsVisible(true);
            }
            updateProgress();
        }

        @Override
        public void onError(VideoPlayer player, Exception e) {
            if (getParentActivity() != null) {
                setError(getParentActivity(), e);
            }
        }

        @Override
        public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
            applyAspect(width, height, unappliedRotationDegrees);
        }

        @Override
        public void onRenderedFirstFrame() {
            if (firstFrameShown) {
                return;
            }
            firstFrameShown = true;
            textureView.animate().alpha(1f).setDuration(220)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        }
    };

    /** Fraction of the episode to jump to once the duration is known. */
    private float resumeAt = -1;

    private void applyAspect(int width, int height, int rotation) {
        if (width <= 0 || height <= 0 || textureView == null) {
            return;
        }
        final boolean quarterTurn = rotation == 90 || rotation == 270;
        final float sourceRatio = (quarterTurn ? height / (float) width : width / (float) height);
        textureView.post(() -> {
            final ViewGroup.LayoutParams lp = textureView.getLayoutParams();
            if (!(lp instanceof FrameLayout.LayoutParams)) {
                return;
            }
            final float boxRatio = textureView.getWidth() / (float) Math.max(1, textureView.getHeight());
            final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) lp;
            if (sourceRatio > boxRatio) {
                params.width = LayoutHelper.MATCH_PARENT;
                params.height = LayoutHelper.WRAP_CONTENT;
            } else {
                params.width = LayoutHelper.WRAP_CONTENT;
                params.height = LayoutHelper.MATCH_PARENT;
            }
            textureView.setLayoutParams(params);
        });
    }

    private View buildControls(Context context) {
        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(16), 0, dp(16), dp(16));

        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        final LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);

        final TextView heading = new TextView(context);
        heading.setText(title.isSeries()
                ? "S" + episode.season + " · E" + episode.number + " · " + episode.title
                : episode.title);
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(15);
        heading.setTypeface(AndroidUtilities.bold());
        labels.addView(heading, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView subtitle = new TextView(context);
        subtitle.setText(title.name);
        subtitle.setTextColor(0xB3FFFFFF);
        subtitle.setTextSize(12);
        labels.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        top.addView(labels, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final LinearLayout transport = new LinearLayout(context);
        transport.setOrientation(LinearLayout.HORIZONTAL);
        transport.setGravity(Gravity.CENTER_VERTICAL);

        playPause = new ImageView(context);
        playPause.setImageResource(R.drawable.ic_action_pause);
        playPause.setScaleType(ImageView.ScaleType.CENTER);
        playPause.setBackground(pillBackground());
        playPause.setOnClickListener(v -> togglePlayback());
        transport.addView(playPause, LayoutHelper.createLinear(44, 44, Gravity.CENTER_VERTICAL));

        seekBar = new VideoSeekBarView(context);
        seekBar.setDelegate(progress -> {
            final long duration = videoPlayer == null ? 0 : videoPlayer.getDuration();
            if (duration > 0) {
                videoPlayer.seekTo((long) (duration * progress));
                updateProgress();
            }
        });
        seekBar.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    dragging = true;
                    AndroidUtilities.cancelRunOnUIThread(hideControlsRunnable);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    dragging = false;
                    scheduleHide();
                    break;
            }
            return false;
        });
        transport.addView(seekBar, LayoutHelper.createLinear(0, 24, 1f, 12, 0, 12, 0));

        qualityButton = pillLabel(context);
        qualityButton.setOnClickListener(v -> showQualityPicker());
        transport.addView(qualityButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 10, 0, 0, 0));

        speedButton = pillLabel(context);
        speedButton.setText(speedLabel(speed));
        speedButton.setOnClickListener(v -> showSpeedPicker());
        transport.addView(speedButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 30, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        positionView = timeLabel(context);
        transport.addView(positionView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        final TextView slash = timeLabel(context);
        slash.setText(" / ");
        transport.addView(slash, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        durationView = timeLabel(context);
        transport.addView(durationView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        column.addView(transport, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));
        return column;
    }

    private static android.graphics.drawable.Drawable pillBackground() {
        return Theme.createRoundRectDrawable(dp(22), 0x33FFFFFF);
    }

    private static TextView timeLabel(Context context) {
        final TextView tv = new TextView(context);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(11);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setText("0:00");
        return tv;
    }

    private static TextView pillLabel(Context context) {
        final TextView tv = new TextView(context);
        tv.setTextSize(11);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), 0, dp(12), 0);
        tv.setBackground(Theme.createRoundRectDrawable(dp(15), 0x33FFFFFF));
        return tv;
    }

    private static String speedLabel(float value) {
        return value == 1f ? "1x" : (value == Math.round(value) ? (int) value + "x"
                : String.format(java.util.Locale.US, "%.2f", value).replaceAll("\\.?0+$", "") + "x");
    }

    /**
     * Quality is a menu of the episode's streams with the current one ticked, and
     * the choice is remembered per episode so the next play starts where the
     * viewer left off choosing.
     */
    private void showQualityPicker() {
        if (episode.qualities.isEmpty() || getParentActivity() == null) {
            return;
        }
        final CharSequence[] labels = new CharSequence[qualities.size()];
        for (int i = 0; i < qualities.size(); i++) {
            final CatMediaModels.Quality quality = qualities.get(i);
            labels[i] = quality == selectedQuality
                    ? quality.label + "  \u2713"
                    : quality.label;
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CatMediaQuality));
        builder.setItems(labels, (dialog, which) -> {
            dialog.dismiss();
            applyQuality(qualities.get(which));
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /** Swaps the stream without losing the place in the episode. */
    private void applyQuality(CatMediaModels.Quality quality) {
        if (quality == null || quality == selectedQuality || videoPlayer == null) {
            return;
        }
        final long duration = videoPlayer.getDuration();
        final long position = videoPlayer.getCurrentPosition();
        final float fraction = duration > 0 ? position / (float) duration : 0f;
        selectedQuality = quality;
        CatMediaLibrary.get().setQuality(episode, quality);
        videoPlayer.releasePlayer(true);
        videoPlayer = null;
        resumeAt = fraction;
        rebuildPlayer();
        updateQualityLabel();
    }

    private void updateQualityLabel() {
        if (qualityButton != null) {
            final boolean multiple = qualities.size() > 1;
            qualityButton.setVisibility(multiple ? View.VISIBLE : View.GONE);
            qualityButton.setText(selectedQuality != null ? selectedQuality.label : "Auto");
        }
    }

    private void showSpeedPicker() {
        if (getParentActivity() == null) {
            return;
        }
        final float[] values = {.5f, .75f, 1f, 1.25f, 1.5f, 2f};
        final CharSequence[] labels = new CharSequence[values.length];
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] == speed
                    ? speedLabel(values[i]) + "  \u2713"
                    : speedLabel(values[i]);
        }
        final org.telegram.ui.ActionBar.AlertDialog.Builder builder =
                new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.CatMediaSpeed));
        builder.setItems(labels, (dialog, which) -> {
            dialog.dismiss();
            speed = values[which];
            CatMediaLibrary.get().setSpeed(speed);
            if (videoPlayer != null) {
                videoPlayer.setPlaybackSpeed(speed);
            }
            speedButton.setText(speedLabel(speed));
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void togglePlayback() {
        if (videoPlayer == null) {
            return;
        }
        if (videoPlayer.getPlayWhenReady()) {
            videoPlayer.pause();
            setControlsVisible(true);
        } else {
            videoPlayer.play();
            scheduleHide();
        }
    }

    private void updatePlayPauseIcon(boolean playing) {
        if (playPause != null) {
            playPause.setImageResource(playing ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
        }
    }

    private long lastSaveAt;

    private void savePosition() {
        if (videoPlayer == null || episode == null) {
            return;
        }
        final long duration = videoPlayer.getDuration();
        if (duration <= 0) {
            return;
        }
        final long position = videoPlayer.getCurrentPosition();
        CatMediaLibrary.get().savePosition(title.id, episode, position / (float) duration, position);
    }

    private void updateProgress() {
        if (videoPlayer == null || seekBar == null) {
            return;
        }
        final long duration = videoPlayer.getDuration();
        final long position = videoPlayer.getCurrentPosition();
        final long now = System.currentTimeMillis();
        if (now - lastSaveAt > 5000) {
            lastSaveAt = now;
            savePosition();
        }
        if (duration > 0) {
            if (!dragging) {
                seekBar.setProgress(Math.min(1f, position / (float) duration));
                positionView.setText(formatTime(position));
            }
            durationView.setText(formatTime(duration));
        }
        if (resumeAt >= 0 && duration > 0) {
            videoPlayer.seekTo((long) (duration * resumeAt));
            resumeAt = -1;
        }
    }

    private static String formatTime(long ms) {
        final long total = Math.max(0, ms) / 1000;
        final long hours = total / 3600;
        final long minutes = (total % 3600) / 60;
        final long seconds = total % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
    }

    private void setControlsVisible(boolean visible) {
        if (controlsVisible == visible) {
            return;
        }
        controlsVisible = visible;
        final float target = visible ? 1f : 0f;
        controls.animate().alpha(target).setDuration(180)
                .setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
        controlsShade.animate().alpha(target).setDuration(180).start();
        actionBar.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        controls.setClickable(!visible);
        if (visible) {
            scheduleHide();
        } else {
            AndroidUtilities.cancelRunOnUIThread(hideControlsRunnable);
        }
    }

    private void scheduleHide() {
        AndroidUtilities.cancelRunOnUIThread(hideControlsRunnable);
        if (videoPlayer != null && videoPlayer.getPlayWhenReady()) {
            AndroidUtilities.runOnUIThread(hideControlsRunnable, 3200);
        }
    }

    private void setError(Context context, Exception e) {
        if (errorView != null || textureView == null) {
            return;
        }
        firstFrameShown = true;
        textureView.animate().cancel();
        textureView.setAlpha(0f);
        errorView = new TextView(context);
        errorView.setText(LocaleController.getString(R.string.CatMediaUnavailable)
                + (e == null ? "" : "\n" + e.getMessage()));
        errorView.setTextColor(0xB3FFFFFF);
        errorView.setTextSize(13);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(32), 0, dp(32), 0);
        ((FrameLayout) fragmentView).addView(errorView,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (videoPlayer != null) {
            videoPlayer.pause();
        }
        savePosition();
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(ticker);
        AndroidUtilities.cancelRunOnUIThread(hideControlsRunnable);
        savePosition();
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
            videoPlayer = null;
        }
        super.onFragmentDestroy();
    }

    /** Bottom scrim, redrawn at the current width so the gradient never stretches. */
    private static class ControlsGradient extends View {
        private final Paint paint = new Paint();

        ControlsGradient(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            if (paint.getShader() == null && getWidth() > 0) {
                paint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                        0x00000000, 0xCC000000, Shader.TileMode.CLAMP));
            }
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        }
    }
}
