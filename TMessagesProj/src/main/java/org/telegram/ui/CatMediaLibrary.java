package org.telegram.ui;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What the viewer has done with the catalogue: liked titles, what has been
 * seen, and where each episode stopped.
 *
 * <p>Local-only and deliberately small — it is the part of the page that is the
 * viewer's rather than the catalogue's, so it must survive the catalogue being
 * swapped for a real source. Keys are title ids, which a real source is expected
 * to keep stable. Main-thread reads are cheap (one SharedPreferences load); writes
 * apply asynchronously.
 */
public final class CatMediaLibrary {

    private static final String PREFS = "cat_media_library";
    private static final String KEY_LIKED = "liked";
    private static final String KEY_SEEN = "seen";
    private static final String KEY_POSITION = "pos:";
    private static final String KEY_QUALITY = "q:";
    private static final String KEY_SPEED = "speed";

    private static CatMediaLibrary instance;

    private final SharedPreferences prefs;
    private final Set<String> liked;
    private final Set<String> seen;

    public static CatMediaLibrary get() {
        if (instance == null) {
            synchronized (CatMediaLibrary.class) {
                if (instance == null) {
                    instance = new CatMediaLibrary();
                }
            }
        }
        return instance;
    }

    private CatMediaLibrary() {
        Context context = ApplicationLoader.applicationContext;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        liked = new HashSet<>(split(prefs.getString(KEY_LIKED, "")));
        seen = new HashSet<>(split(prefs.getString(KEY_SEEN, "")));
    }

    public boolean isLiked(String titleId) {
        return titleId != null && liked.contains(titleId);
    }

    public boolean setLiked(String titleId, boolean value) {
        if (titleId == null) {
            return false;
        }
        final boolean changed = value ? liked.add(titleId) : liked.remove(titleId);
        if (changed) {
            prefs.edit().putString(KEY_LIKED, join(liked)).apply();
        }
        return value;
    }

    public boolean isSeen(String titleId) {
        return titleId != null && seen.contains(titleId);
    }

    /** Marks a title watched. Only meaningful once an episode has mostly played. */
    public void markSeen(String titleId) {
        if (titleId != null && seen.add(titleId)) {
            prefs.edit().putString(KEY_SEEN, join(seen)).apply();
        }
    }

    public void clearSeen(String titleId) {
        if (titleId != null && seen.remove(titleId)) {
            prefs.edit().putString(KEY_SEEN, join(seen)).apply();
        }
    }

    public int seenCount() {
        return seen.size();
    }

    /** 0..1, or -1 when the episode has no stored position. */
    public float position(String titleId, CatMediaModels.Episode episode) {
        if (titleId == null || episode == null) {
            return -1;
        }
        final float stored = prefs.getFloat(KEY_POSITION + titleId + ":" + episode.key(), -1f);
        return stored;
    }

    /**
     * Stores a position and folds it into the episode's in-memory progress so the
     * rails show the same number without a reload. Reaching the end clears the
     * position and marks the title seen.
     */
    public void savePosition(String titleId, CatMediaModels.Episode episode, float fraction, long positionMs) {
        if (titleId == null || episode == null) {
            return;
        }
        final SharedPreferences.Editor editor = prefs.edit();
        if (fraction >= 0.97f) {
            editor.remove(KEY_POSITION + titleId + ":" + episode.key());
            episode.progress = 1f;
            markSeen(titleId);
        } else if (fraction > 0.01f) {
            editor.putFloat(KEY_POSITION + titleId + ":" + episode.key(), fraction);
            episode.progress = fraction;
        }
        editor.apply();
    }

    /**
     * The quality label the viewer last picked for this episode, resolved back to
     * its stream. Null when nothing was ever picked, or that label is gone from
     * the episode — a stale preference must not cost the viewer a playable stream.
     */
    public CatMediaModels.Quality quality(CatMediaModels.Episode episode) {
        if (episode == null) {
            return null;
        }
        final String label = prefs.getString(KEY_QUALITY + episode.key(), null);
        if (label == null) {
            return null;
        }
        for (CatMediaModels.Quality q : episode.qualities) {
            if (q.label.equals(label)) {
                return q;
            }
        }
        return null;
    }

    public void setQuality(CatMediaModels.Episode episode, CatMediaModels.Quality quality) {
        if (episode == null || quality == null) {
            return;
        }
        prefs.edit().putString(KEY_QUALITY + episode.key(), quality.label).apply();
    }

    public float speed() {
        return prefs.getFloat(KEY_SPEED, 1f);
    }

    public void setSpeed(float value) {
        prefs.edit().putFloat(KEY_SPEED, value).apply();
    }

    public boolean hasAnyHistory() {
        if (!seen.isEmpty() || !liked.isEmpty()) {
            return true;
        }
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_POSITION)) {
                return true;
            }
        }
        return false;
    }

    private static Collection<String> split(String raw) {
        final List<String> out = new ArrayList<>();
        if (raw != null && !raw.isEmpty()) {
            for (String part : raw.split(",")) {
                if (!part.isEmpty()) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    private static String join(Set<String> values) {
        final StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }
}
