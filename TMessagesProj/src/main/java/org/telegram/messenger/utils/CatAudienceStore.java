package org.telegram.messenger.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Holds the {@link CatAudience} decision and reads the device signals that lead
 * to it.
 *
 * <p>Two rules shape everything here:
 *
 * <ul>
 *   <li><b>A guess is never final.</b> Before a phone number is known the
 *       audience is only a guess, cached so the first screen already looks
 *       right, and marked as such. The first confirmed phone code replaces it.
 *   <li><b>A confirmed answer does not flip.</b> Once {@link CatAudience#fromPhoneCode}
 *       has decided, nothing else may override it — not a SIM swap, not a
 *       network change. A user who signed up with +98 is Iranian for the life
 *       of the install even if they travel.
 * </ul>
 */
public final class CatAudienceStore {

    private static final String PREFS = "cat_audience";
    private static final String KEY_AUDIENCE = "audience";
    private static final String KEY_CONFIRMED = "confirmed";

    private CatAudienceStore() {
    }

    private static SharedPreferences prefs() {
        Context ctx = ApplicationLoader.applicationContext;
        return ctx == null ? null : ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * The audience to act on right now. Never returns a stale confirmed value
     * for a different account: callers that have a phone number should prefer
     * {@link #forPhone} so the answer cannot lag behind the account in view.
     */
    public static int current() {
        SharedPreferences p = prefs();
        if (p == null) {
            return CatAudience.UNKNOWN;
        }
        return p.getInt(KEY_AUDIENCE, CatAudience.UNKNOWN);
    }

    /** True when the stored answer came from a phone code, not a guess. */
    public static boolean isConfirmed() {
        SharedPreferences p = prefs();
        return p != null && p.getBoolean(KEY_CONFIRMED, false);
    }

    /**
     * The audience for an account whose phone number is known. Confirms and
     * caches on the first conclusive reading, then keeps that answer.
     */
    public static int forPhone(String phone) {
        int decided = CatAudience.fromPhoneCode(phone);
        if (decided == CatAudience.UNKNOWN) {
            return current();
        }
        SharedPreferences p = prefs();
        if (p == null) {
            return decided;
        }
        if (isConfirmed()) {
            // Already locked by an earlier sign-in. A second account on the
            // same install must not re-open payments, and must not take them
            // away either: the store decision is per install.
            return p.getInt(KEY_AUDIENCE, decided);
        }
        p.edit()
                .putInt(KEY_AUDIENCE, decided)
                .putBoolean(KEY_CONFIRMED, true)
                .apply();
        return decided;
    }

    /**
     * Settles the answer for this launch: confirms from any already-signed-in
     * account first, then falls back to the device guess. Never overwrites a
     * confirmed answer, so this is safe to call on every launch.
     *
     * <p>The confirmed-account pass is not redundant with the sign-in hook. An
     * existing user who updates the app never passes through
     * {@code LoginActivity.onAuthSuccess} again, so without reading their
     * stored number here they would keep whatever the device signals guessed —
     * and a guessed FOREIGN would hide payments from them permanently.
     */
    public static int refreshGuess() {
        if (!isConfirmed()) {
            String phone = firstSignedInPhone();
            if (phone != null && forPhone(phone) != CatAudience.UNKNOWN) {
                return current();
            }
        }
        if (isConfirmed()) {
            return current();
        }
        int simCountry = CatAudience.fromSimCountry(simCountryIso());
        int operator = CatAudience.fromOperatorCode(operatorCode());
        int region = CatAudience.fromRegion(regionCode());
        int guess = CatAudience.guess(simCountry, operator, region);
        SharedPreferences p = prefs();
        if (p != null && guess != CatAudience.UNKNOWN) {
            p.edit().putInt(KEY_AUDIENCE, guess).apply();
        }
        return guess == CatAudience.UNKNOWN ? current() : guess;
    }

    /** Phone of the first signed-in account, or null when none is active. */
    private static String firstSignedInPhone() {
        try {
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                org.telegram.messenger.UserConfig config = org.telegram.messenger.UserConfig.getInstance(a);
                if (config != null && config.isClientActivated()) {
                    String phone = config.getClientPhone();
                    if (!TextUtils.isEmpty(phone)) {
                        return phone;
                    }
                }
            }
        } catch (Throwable t) {
            FileLog.d("CatAudience: no signed-in account readable: " + t);
        }
        return null;
    }

    /**
     * True when the user should be told to turn off their VPN for a better
     * connection.
     *
     * <p>Only when three things hold at once: the audience is Iranian, a VPN is
     * actually in the path, and the current link is measurably poor. The last
     * condition is what keeps this from nagging everyone who uses a VPN on
     * purpose — an Iranian who needs one to reach the internet at all would
     * otherwise get the prompt constantly while their connection is fine.
     */
    public static boolean shouldSuggestVpnOff(int networkProfile, boolean vpnActive) {
        return current() == CatAudience.IRAN
                && vpnActive
                && org.telegram.messenger.NetworkProfile.isSlow(networkProfile);
    }

    // --- Device signal readers. Each returns null on any failure: an unreadable
    // signal must degrade to "no opinion", never to a wrong country. ---

    private static TelephonyManager telephony() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        try {
            return (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String simCountryIso() {
        TelephonyManager tm = telephony();
        if (tm == null) {
            return null;
        }
        try {
            String iso = tm.getSimCountryIso();
            return TextUtils.isEmpty(iso) ? null : iso;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Prefers the SIM's own operator code and falls back to the network's. A
     * foreign SIM roaming in Iran reports its home MCC from the SIM and 432
     * from the network, so consulting both is what catches that case.
     *
     * <p>Both getters need {@code READ_PHONE_STATE} on API 22+ and throw
     * SecurityException without it, so each is guarded on its own: the app
     * never asks for that permission merely to classify the audience, and a
     * signal we are not allowed to read has to degrade to "no opinion" rather
     * than crash a startup path.
     */
    private static String operatorCode() {
        TelephonyManager tm = telephony();
        if (tm == null) {
            return null;
        }
        try {
            String sim = tm.getSimOperator();
            if (!TextUtils.isEmpty(sim)) {
                return sim;
            }
        } catch (Throwable t) {
            FileLog.d("CatAudience: getSimOperator unavailable: " + t);
        }
        try {
            String network = tm.getNetworkOperator();
            return TextUtils.isEmpty(network) ? null : network;
        } catch (Throwable t) {
            FileLog.d("CatAudience: getNetworkOperator unavailable: " + t);
            return null;
        }
    }

    private static String regionCode() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.content.res.Configuration c = ApplicationLoader.applicationContext.getResources().getConfiguration();
                return c.getLocales().isEmpty() ? null : c.getLocales().get(0).getCountry();
            }
            return java.util.Locale.getDefault().getCountry();
        } catch (Throwable t) {
            return null;
        }
    }
}
