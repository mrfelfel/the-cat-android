package org.telegram.messenger.billing;

import android.app.Activity;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.tgnet.TLRPC;

/**
 * Holds the optional store-specific {@link StoreBillingBridge}. On Google Play,
 * standalone and Huawei builds nothing is ever registered and every caller uses
 * the existing Google / invoice path. The Bazaar module's loader overrides
 * {@code ApplicationLoader.getStoreBillingBridge()} to lazily provide a Poolakey
 * bridge; callers here read it through {@code applicationLoaderInstance}.
 *
 * This class and the getter it calls are protected by keep rules in the
 * LIBRARY's proguard-rules.pro — not the app module's. The library runs its own
 * R8 pass (minifyReleaseWithR8) where no Bazaar subclass is visible, and has
 * twice constant-folded unkept variants of this chain to null/false, silently
 * disabling every store purchase and routing users to the invoice WebView.
 * See the javadoc on ApplicationLoader.getStoreBillingBridge().
 */
public final class StoreBilling {

    private StoreBilling() {
    }

    /** Returns the registered store bridge, or null on non-Bazaar builds. */
    public static StoreBillingBridge get() {
        return ApplicationLoader.applicationLoaderInstance.getStoreBillingBridge();
    }

    /** Convenience: true only on builds that registered a store bridge. */
    public static boolean isAvailable() {
        return get() != null;
    }

    /**
     * Starts a consumable store purchase when a bridge is registered (Bazaar);
     * returns true if it did. Returns false otherwise so the caller falls
     * through to the Google Play or invoice/bot path unchanged. Mirrors what
     * the premium path does with {@code isBazaarStoreApp() && isAvailable()},
     * packaged so stars/gifts call sites stay one line.
     */
    public static boolean tryPurchaseConsumable(Activity activity, int account, TLRPC.InputStorePaymentPurpose purpose, String sku, StoreBillingBridge.Callback callback) {
        final StoreBillingBridge b = get();
        if (b == null) {
            return false;
        }
        b.purchaseConsumable(activity, account, purpose, sku, callback);
        return true;
    }
}
