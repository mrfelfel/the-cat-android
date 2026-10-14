package org.telegram.messenger.billing;

import android.app.Activity;

import org.telegram.tgnet.TLRPC;

/**
 * Store-agnostic billing entry point so UI code (PremiumPreviewFragment, etc.)
 * never references Google Play Billing types directly on the purchase path.
 *
 * The Google Play implementation stays the untouched BillingController; the
 * Bazaar (Cafe Bazaar / Poolakey) implementation lives only in the
 * TMessagesProj_AppBazaar module and registers itself here at startup, which
 * keeps Poolakey out of the shared library entirely. When no bridge is
 * registered (Play / standalone / Huawei builds) every caller falls through to
 * the existing Google or invoice/bot path unchanged.
 */
public interface StoreBillingBridge {

    /** True once the underlying store connection is usable. */
    boolean isReady();

    /**
     * Starts a premium subscription purchase for the given tier option.
     * {@code option} may be null when the server promo did not yield one; the
     * implementation then falls back to its default premium SKU. Must call
     * exactly one callback method on the UI thread.
     */
    void purchasePremiumSubscription(Activity activity, int account, TLRPC.TL_premiumSubscriptionOption option, String source, boolean upgrade, Callback callback);

    /**
     * Starts a CONSUMABLE purchase (stars topup, star gift, premium gift, ...).
     * Unlike the premium subscription, these products are consumed after the
     * server confirms, so the user can buy them again. {@code purpose} carries
     * the full store-payment intent (stars amount, gift recipient, ...);
     * {@code sku} is the store product id from the server-provided option.
     * Must call exactly one callback method on the UI thread.
     */
    void purchaseConsumable(Activity activity, int account, TLRPC.InputStorePaymentPurpose purpose, String sku, Callback callback);

    interface Callback {
        void onSuccess();

        void onError(String message);

        void onCanceled();

        /**
         * The store took the user's money but the server has not confirmed the
         * purchase yet (offline, or the server method is missing). The receipt is
         * persisted and will be re-sent automatically, so the user's purchase is
         * safe. UI should show a neutral "will activate shortly" note, not an
         * error.
         */
        void onQueued();
    }
}
