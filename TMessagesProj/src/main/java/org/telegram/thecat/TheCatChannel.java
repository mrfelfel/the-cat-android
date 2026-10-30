package org.telegram.thecat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The marketing channel this APK was built for, or "" for an un-stamped build.
 *
 * <p>Read from {@code assets/thecat_channel.txt}, which
 * {@code scripts/inject-channels.sh} writes per channel into copies of the one
 * shared base APK. The asset is stamped BEFORE signing: v2/v3 cover the whole
 * file, so an APK cannot be attributed by editing it once signed.
 *
 * <p>Values are restricted to {@code [a-z0-9_-]} by the script, which keeps the
 * result safe to concatenate into the {@code installer} field of
 * initConnection.
 */
public final class TheCatChannel {

    public static final String ASSET = "thecat_channel.txt";

    private static String cached;
    private static boolean loaded;

    private TheCatChannel() {
    }

    public static synchronized String read() {
        if (loaded) {
            return cached;
        }
        loaded = true;
        cached = "";
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(ASSET)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 512) {
                    break;
                }
            }
            cached = sanitize(out.toString("UTF-8"));
        } catch (Exception e) {
            // Absent in every build the script has not touched -- that is the
            // normal case, not an error.
            FileLog.e(e);
        }
        return cached;
    }

    private static String sanitize(String raw) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
