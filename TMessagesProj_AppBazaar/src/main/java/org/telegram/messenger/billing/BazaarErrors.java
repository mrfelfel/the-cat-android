package org.telegram.messenger.billing;

import android.app.Activity;
import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.web.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.LaunchActivity;

/**
 * Crash boundary for every Bazaar (Poolakey) code path.
 *
 * Poolakey dispatches purchase results inline from {@code onActivityResult},
 * runs billing calls on its own HandlerThread, and drops calls made while its
 * communicator is unset. Anything throwing from those places -- in our code or
 * inside the library -- takes the whole process down at the worst moment for
 * the user: right after money changed hands.
 *
 * Every entry point therefore funnels through here: the Throwable is logged and
 * turned into one readable dialog instead of a FATAL EXCEPTION. Failures are
 * latched so a retry loop cannot stack dialogs on top of each other.
 */
public final class BazaarErrors {

    /** Runs a Bazaar operation; swallows and reports anything it throws. */
    public interface Guarded {
        void run() throws Throwable;
    }

    private static volatile long lastShownAt;
    private static volatile String lastShownMessage;
    private static final long DEBOUNCE_MS = 4000L;

    private BazaarErrors() {
    }

    /**
     * Runs {@code body} and reports any Throwable through {@link #report}.
     * Returns true when the body finished without throwing.
     */
    public static boolean guard(Context context, String what, Guarded body) {
        try {
            body.run();
            return true;
        } catch (Throwable t) {
            report(context, what, t);
            return false;
        }
    }

    /**
     * Logs the failure and shows one localized dialog. Safe from any thread:
     * dialogs are posted to the UI thread and skipped when no activity is up.
     */
    public static void report(Context context, String what, Throwable t) {
        FileLog.e("Bazaar billing: " + what + " failed", t);
        final String message = describe(t);
        AndroidUtilities.runOnUIThread(() -> show(context, message));
    }

    /**
     * Logs only, no dialog. For the background receipt queue, which reports its
     * own state in the pending-purchases screen and must not interrupt the user
     * on every failed retry.
     */
    public static void reportQuietly(String what, Throwable t) {
        FileLog.e("Bazaar billing: " + what + " failed", t == null ? new RuntimeException(what) : t);
    }

    /** Reports a failure that already has a user-facing message (no Throwable). */
    public static void reportMessage(Context context, String message) {
        final String text = message == null || message.trim().isEmpty()
                ? LocaleController.getString(R.string.UnknownError)
                : message.trim();
        AndroidUtilities.runOnUIThread(() -> show(context, text));
    }

    private static void show(Context context, String message) {
        final long now = System.currentTimeMillis();
        if (message.equals(lastShownMessage) && now - lastShownAt < DEBOUNCE_MS) {
            return;
        }
        lastShownMessage = message;
        lastShownAt = now;

        final Activity activity = resolveActivity(context);
        if (activity == null || activity.isFinishing()) {
            FileLog.e("Bazaar billing: no activity to show error dialog: " + message);
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString(R.string.AppName))
                    .setMessage(LocaleController.getString(R.string.BazaarErrorMessage) + "\n\n" + message)
                    .setPositiveButton(LocaleController.getString(R.string.OK), null)
                    .setOnDismissListener(d -> lastShownMessage = null)
                    .show();
        } catch (Throwable t) {
            // A dialog must never be the thing that crashes.
            FileLog.e("Bazaar billing: could not show error dialog", t);
        }
    }

    private static Activity resolveActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        }
        try {
            final BaseFragment last = LaunchActivity.getSafeLastFragment();
            if (last != null && last.getParentActivity() != null) {
                return last.getParentActivity();
            }
        } catch (Throwable ignored) {
        }
        return LaunchActivity.instance;
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return LocaleController.getString(R.string.UnknownError);
        }
        Throwable cause = t;
        // Poolakey wraps its failures, and the outer message is often empty.
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        if (message != null && !message.trim().isEmpty()) {
            return message.trim();
        }
        return cause.getClass().getSimpleName();
    }
}
