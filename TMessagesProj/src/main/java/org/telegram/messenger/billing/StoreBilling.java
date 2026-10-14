package org.telegram.messenger.billing;

import android.app.Activity;

import org.telegram.tgnet.TLRPC;

/**
 * Holds the optional store-specific {@link StoreBillingBridge}. On Google Play,
 * standalone and Huawei builds nothing is ever registered and every caller uses
 * the existing Google / invoice path. The Bazaar module registers its Poolakey
 * bridge here during ApplicationLoader startup, before any UI can trigger a
 * purchase.
 */
public final class StoreBilling {

    private static volatile StoreBillingBridge bridge;

    private StoreBilling() {
    }

    public static void register(StoreBillingBridge implementation) {
        bridge = implementation;
    }

    /** Returns the registered store bridge, or null on non-Bazaar builds. */
    public static StoreBillingBridge get() {
        return bridge;
    }

    /** Convenience: true only on builds that registered a store bridge. */
    public static boolean isAvailable() {
        StoreBillingBridge b = bridge;
        return b != null;
    }

    /**
     * Starts a consumable store purchase when a bridge is registered (Bazaar);
     * returns true if it did. Returns false otherwise so the caller falls
     * through to the Google Play or invoice/bot path unchanged. Mirrors what
     * the premium path does with {@code isBazaarStoreApp() && isAvailable()},
     * packaged so stars/gifts call sites stay one line.
     */
    public static boolean tryPurchaseConsumable(Activity activity, int account, TLRPC.InputStorePaymentPurpose purpose, String sku, StoreBillingBridge.Callback callback) {
        StoreBillingBridge b = bridge;
        if (b == null) {
            return false;
        }
        b.purchaseConsumable(activity, account, purpose, sku, callback);
        return true;
    }
}
