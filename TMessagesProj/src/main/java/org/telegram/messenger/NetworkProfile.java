package org.telegram.messenger;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.telephony.TelephonyManager;

/**
 * How slow the link in use is, in the only granularity the connection layer can
 * act on. Deliberately not "which technology" but "what should timeouts be":
 * the native side has no use for the difference between UMTS and HSPA, only for
 * whether a handshake has a few hundred milliseconds or several seconds to
 * complete.
 *
 * <p>minSdk is 21, so this has to work across four different APIs. The order
 * below is newest-first on purpose: newer APIs are more accurate, and the older
 * branches are fallbacks rather than separate code paths to maintain.
 */
public class NetworkProfile {

    public static final int UNKNOWN = 0;
    /** GPRS, EDGE, CDMA, 1xRTT, IDEN — packet radio, ~1s round trips. */
    public static final int SLOW_2G = 1;
    /** UMTS, HSPA, EVDO — usable but latency-jittery, especially under load. */
    public static final int MEDIUM_3G = 2;
    /** LTE, 5G, WiFi, ethernet. The behaviour every prior release had. */
    public static final int FAST = 3;

    /**
     * Read the current link. Never throws: a profile we cannot determine is
     * UNKNOWN, which callers treat as FAST so no build regresses silently on
     * a device whose API we got wrong.
     */
    public static int current() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                return fromCapabilities();
            }
            return fromNetworkInfo();
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    /**
     * API 23+: NetworkCapabilities. Since API 29 the legacy NetworkInfo
     * getters are deprecated and can return null; this path also survives
     * VPNs and multi-network devices where getActiveNetworkInfo lies.
     */
    @TargetApi(23)
    private static int fromCapabilities() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return UNKNOWN;
        }
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return UNKNOWN;
        }
        Network network = cm.getActiveNetwork();
        if (network == null) {
            return UNKNOWN;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) {
            return UNKNOWN;
        }

        // A VPN can sit on top of a 2G link. The transport type then says VPN,
        // so prefer the underlying telephony reading rather than trusting it.
        boolean isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        if (!isCellular) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
                return FAST;
            }
            // VPN or other overlay, or WIFI_AWARE on API 26+. Fall through to
            // telephony, which is the only other thing that can be underneath.
            return fromTelephony();
        }
        return fromTelephony();
    }

    /** API 21/22: NetworkInfo was the only way, and it did carry a subtype. */
    @SuppressWarnings("deprecation")
    private static int fromNetworkInfo() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return UNKNOWN;
        }
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return UNKNOWN;
        }
        android.net.NetworkInfo info = cm.getActiveNetworkInfo();
        if (info == null || !info.isConnected()) {
            return UNKNOWN;
        }
        if (info.getType() != ConnectivityManager.TYPE_MOBILE) {
            return FAST;
        }
        return classifySubtype(info.getSubtype());
    }

    /**
     * The subtype is always on TelephonyManager, on every API level we ship, so
     * this is the one reading that does not need version branching.
     */
    private static int fromTelephony() {
        Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return UNKNOWN;
        }
        TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            return UNKNOWN;
        }
        return classifySubtype(tm.getNetworkType());
    }

    /**
     * Compiles against compileSdk 36 while minSdk stays 21: NETWORK_TYPE_GSM
     * (API 25) and NETWORK_TYPE_NR (API 29) are compile-time int constants, so
     * they inline into the switch rather than requiring a field lookup that
     * would throw on an older device.
     */
    @SuppressWarnings("NewApi")
    private static int classifySubtype(int subtype) {
        switch (subtype) {
            case TelephonyManager.NETWORK_TYPE_GPRS:      // 1
            case TelephonyManager.NETWORK_TYPE_EDGE:      // 2
            case TelephonyManager.NETWORK_TYPE_CDMA:      // 4
            case TelephonyManager.NETWORK_TYPE_1xRTT:     // 7
            case TelephonyManager.NETWORK_TYPE_IDEN:      // 11
            case TelephonyManager.NETWORK_TYPE_GSM:       // 16 (API 25, same enum)
                return SLOW_2G;

            case TelephonyManager.NETWORK_TYPE_UMTS:      // 3
            case TelephonyManager.NETWORK_TYPE_EVDO_0:    // 5
            case TelephonyManager.NETWORK_TYPE_EVDO_A:    // 6
            case TelephonyManager.NETWORK_TYPE_HSDPA:     // 8
            case TelephonyManager.NETWORK_TYPE_HSUPA:     // 9
            case TelephonyManager.NETWORK_TYPE_HSPA:      // 10
            case TelephonyManager.NETWORK_TYPE_EVDO_B:    // 12
            case TelephonyManager.NETWORK_TYPE_EHRPD:     // 14
            case TelephonyManager.NETWORK_TYPE_HSPAP:     // 15
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:  // 17
                return MEDIUM_3G;

            case TelephonyManager.NETWORK_TYPE_LTE:       // 13
            case TelephonyManager.NETWORK_TYPE_IWLAN:     // 18
            case TelephonyManager.NETWORK_TYPE_NR:        // 20 (API 29, same enum)
                return FAST;

            case TelephonyManager.NETWORK_TYPE_UNKNOWN:
            default:
                return UNKNOWN;
        }
    }

    /**
     * True only for the profiles that need longer timers. UNKNOWN is not slow:
     * guessing "slow" for a link we could not read would change behaviour on
     * devices that are actually fine, which is the one outcome worth avoiding.
     */
    public static boolean isSlow(int profile) {
        return profile == SLOW_2G || profile == MEDIUM_3G;
    }

    public static String describe(int profile) {
        switch (profile) {
            case SLOW_2G:   return "2G";
            case MEDIUM_3G: return "3G";
            case FAST:      return "fast";
            default:        return "unknown";
        }
    }
}
