/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import org.telegram.messenger.utils.CatAudience;
import org.telegram.messenger.utils.CatAudienceStore;

import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    public static boolean USE_CLOUD_STRINGS = true;
    public static boolean CHECK_UPDATES = true;
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;

    public static int APP_ID = 3893395;
    public static String APP_HASH = "e2adbe11ee0f092ced6043c4e051fdda";

    // SafetyNet key for Google Identity SDK, set it to empty to disable
    public static String SAFETYNET_KEY = "AIzaSyDqt8P-7F7CPCseMkOiVRgb1LY8RN1bvH8";
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=com.thecat.messenger";
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    public static String GOOGLE_AUTH_CLIENT_ID = "760348033671-81kmi3pi84p11ub8hp9a1funsv0rn2p9.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    /**
     * canalyze ingest keys and endpoint.
     *
     * <p>More than one key is carried on purpose: the server accepts several
     * per app, so a build that ships both the current and the previous key
     * keeps working whichever one the server still honours. That is what makes
     * a rotation produce no 401 at all — the SDK tries them in order and
     * remembers the first that authenticates.
     *
     * <p>Order matters: the current key first, the previous one second. A dead
     * key costs one wasted round trip on the first flush after a restart, then
     * is skipped for the life of the process.
     *
     * <p>The key is not a secret in the cryptographic sense — it ships inside
     * the APK and is extractable from any build — but it is the only thing that
     * lets a batch be accepted.
     */
    public static String[] CANALYZE_APP_KEYS = {
            "ca_771f537e470d714d38fbda7a6c6ef9ece6177cc37ec786b4", // v2-rollout
            "ca_8c145f184d3a0cc75b3602ab4a48c9f37354f42f77077f9d", // migrated (revoked)
    };
    public static String CANALYZE_ENDPOINT = "https://canalyze.cr0.ir";

    /**
     * The id canalyze registered for this app — NOT the Android package name.
     *
     * <p>The server compares the batch's `app` field against the registered id
     * and rejects a mismatch with 400, which the SDK drops as unrecoverable.
     * Passing `BuildConfig.APPLICATION_ID` here shipped every batch to a silent
     * 400 for the whole first integration; the package a flavor installs under
     * and the id an app is registered under are two different things.
     *
     * <p>Per-store separation does not need a different id: the SDK already
     * sends `install_source` (the installer package), which is where a store
     * distinction belongs.
     */
    public static String CANALYZE_APP_ID = "thecat";

    // The Cat has no FCM push infra, so without this the OS kills the socket in
    // background and messages/calls arrive late. Off by default to save battery;
    // users can enable Keep-Alive Service in Settings > Notifications manually.
    public static boolean FORCE_BACKGROUND_WORK = false;

    // You can use this flag to disable Google Play Billing (If you're making fork and want it to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = false;

    // works only on official app ids, disable on your forks
    public static boolean SUPPORTS_PASSKEYS = true;

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
            if (LOGS_ENABLED) {
                final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                    FileLog.fatal(exception, false);
                    if (pastHandler != null) {
                        pastHandler.uncaughtException(thread, exception);
                    }
                });
            }
        }
    }

    /**
     * Whether payments are offered to this install at all.
     *
     * <p>An install outside Iran gets none: neither our own checkout nor Google
     * Play's, since a Play-billed purchase would be revenue Google cannot pay
     * out to an Iranian merchant anyway. This gate is checked before the
     * billing-path choice below so the two can never disagree — a store build
     * whose audience is foreign hides payment instead of falling through to
     * Play Billing.
     *
     * <p>UNKNOWN (no conclusive signal yet) also hides payments: it is
     * reversible the moment a phone code arrives, whereas wrongly showing
     * payments to a foreign user is not.
     */
    public static boolean arePaymentsAvailable() {
        return CatAudience.canPay(CatAudienceStore.current()) && remotePaymentsEnabled();
    }

    /**
     * The distribution name this build reports to the address document, so a
     * config published there can switch one distribution without the others.
     */
    public static String buildName() {
        if (isBazaarStoreApp()) {
            return "bazaar";
        }
        if (ApplicationLoader.isStandaloneBuild()) {
            return "standalone";
        }
        return "play";
    }

    /**
     * The operator's remote switch, read from the signed address document.
     *
     * <p>Placed above the audience check in {@link #arePaymentsAvailable()}
     * because it is the only kill switch that still works when the server —
     * and thus {@code help.getAppConfig} — is unreachable, which is exactly
     * when an operator needs to turn payments off. Defaults to true when the
     * document says nothing, so a client with no config behaves as before.
     *
     * <p>The document states which authority it claims:
     *
     * <ul>
     *   <li>{@code server} — it imposes nothing; the server's own answer decides
     *       (this is also what a client with no config gets, preserving the
     *       behaviour every build had before).
     *   <li>{@code config} — the document decides; a server that allows
     *       purchases cannot enable ones the document disables.
     *   <li>{@code off} — off everywhere, whatever the server says.
     * </ul>
     *
     * <p>A server answer is "the server has not blocked purchases", which is
     * what {@code premium_purchase_blocked} reports and is applied separately
     * at each entry point.
     */
    private static boolean remotePaymentsEnabled() {
        try {
            org.telegram.thecat.CatNetResolver r = org.telegram.thecat.CatNetResolver.getInstance();
            String mode = r.paymentMode();
            if (mode == null || "server".equals(mode)) {
                // The document defers, or we have not read one yet.
                return true;
            }
            if ("off".equals(mode)) {
                return false;
            }
            Boolean enabled = r.paymentsEnabled(buildName());
            return enabled == null || enabled;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean useInvoiceBilling() {
        if (!arePaymentsAvailable()) {
            return false;
        }
        if (isBazaarStoreApp() && !isBazaarAppInstalled()) {
            // The Bazaar flavor running on a phone without Cafe Bazaar has no
            // store to bill through, so it behaves like the direct build and
            // falls through to the invoice/WebView path for every purchase.
            return true;
        }
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || ApplicationLoader.usesDirectPaymentsStatic() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static boolean isBazaarStoreApp() {
        return ApplicationLoader.isBazaarStoreBuild();
    }

    private static volatile Boolean bazaarAppInstalled;

    /**
     * Whether Cafe Bazaar itself is on the device. Only meaningful for the
     * Bazaar flavor: every other build bills through no store app, so it
     * reports true and leaves those callers untouched. Cached because
     * {@link #useInvoiceBilling()} is read constantly by the UI.
     */
    public static boolean isBazaarAppInstalled() {
        if (!isBazaarStoreApp()) {
            return true;
        }
        Boolean installed = bazaarAppInstalled;
        if (installed == null) {
            if (ApplicationLoader.applicationContext == null) {
                return true;
            }
            try {
                installed = ApplicationLoader.applicationContext.getPackageManager().getLaunchIntentForPackage("com.farsitel.bazaar") != null;
            } catch (Exception e) {
                installed = false;
            }
            bazaarAppInstalled = installed;
        }
        return installed;
    }

    public static String getSmsHash() {
        return DEBUG_VERSION ? "jb4o1Aqp73Z" : "ED+rxATGMlz";
    }
}
