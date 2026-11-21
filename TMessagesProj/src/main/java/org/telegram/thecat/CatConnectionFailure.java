package org.telegram.thecat;

import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.tgnet.ConnectionsManager;

/**
 * Detects the one failure that means "this build may no longer work": a device
 * with a working network that still cannot reach the server through any path.
 *
 * <p>The distinction matters, because the two look identical on screen but need
 * opposite responses:
 *
 * <ul>
 *   <li><b>No network</b> — nothing to say; the app already shows
 *       "Waiting for network" and that is true.
 *   <li><b>Network, but nothing connects</b> — every address the finder knows
 *       has been tried, the seed failed too, enough time has passed. Either the
 *       server is down or this client is too old to be served. Only the second
 *       case is the user's problem, and only the user can fix it.
 * </ul>
 *
 * <p>Deliberately conservative: it requires a real elapsed time window and a
 * completed address-finder attempt, so a slow handshake, a flaky tunnel, or a
 * moment of packet loss cannot trigger a "your app is broken" dialog.
 */
public final class CatConnectionFailure {

    /** How long a network-connected device may fail before we speak. */
    private static final long SILENCE_BEFORE_PROMPT_MS = 90_000L;
    /** After the user dismisses it, do not ask again in a hurry. */
    private static final long RETRY_AFTER_DISMISS_MS = 10 * 60_000L;

    // Explicit "have we started timing yet" flag rather than a 0 sentinel:
    // elapsedRealtime() can legitimately be near zero shortly after boot, and
    // a 0 sentinel would then restart the window on every check and never fire.
    private static boolean timing;
    private static long failingSince;
    private static long lastPromptAt;

    private CatConnectionFailure() {
    }

    /**
     * Called periodically with the current connection state. Returns true when
     * the "this version may no longer work" prompt should be shown — at most
     * once per {@link #RETRY_AFTER_DISMISS_MS}.
     */
    public static boolean shouldPrompt(int connectionState, int account) {
        boolean networkUp;
        try {
            networkUp = ApplicationLoader.isNetworkOnline();
        } catch (Throwable t) {
            return false;
        }
        if (!networkUp) {
            // No link at all: this is not our failure and not the user's app's
            // fault. Reset, so the clock starts fresh when the link returns.
            timing = false;
            failingSince = 0;
            return false;
        }

        boolean connecting = connectionState == ConnectionsManager.ConnectionStateConnecting
                || connectionState == ConnectionsManager.ConnectionStateConnectingToProxy;
        if (!connecting) {
            // Connected, or waiting on something that is not our concern.
            timing = false;
            failingSince = 0;
            return false;
        }

        final long now = SystemClock.elapsedRealtime();
        if (!timing) {
            timing = true;
            failingSince = now;
        }
        if (now - failingSince < SILENCE_BEFORE_PROMPT_MS) {
            return false;
        }
        if (lastPromptAt != 0 && now - lastPromptAt < RETRY_AFTER_DISMISS_MS) {
            return false;
        }
        // The address finder must have finished its attempt. If it is still
        // working, the app has not exhausted what it knows how to do yet, and
        // telling the user to update would be premature and wrong.
        try {
            if (CatNetResolver.getInstance().isRunning()) {
                return false;
            }
        } catch (Throwable t) {
            FileLog.d("CatConnectionFailure: resolver state unavailable: " + t);
        }
        lastPromptAt = now;
        return true;
    }

    /** Call when the prompt has been shown, so the window restarts. */
    public static void onPromptShown() {
        lastPromptAt = SystemClock.elapsedRealtime();
        timing = false;
        failingSince = 0;
    }

    /** Test seam: forget all timing. */
    static void resetForTest() {
        timing = false;
        failingSince = 0;
        lastPromptAt = 0;
    }
}
