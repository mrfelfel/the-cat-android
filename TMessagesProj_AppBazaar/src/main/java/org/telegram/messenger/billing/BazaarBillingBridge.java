package org.telegram.messenger.billing;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultRegistry;

import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.utils.BillingUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.concurrent.atomic.AtomicBoolean;

import ir.cafebazaar.poolakey.Payment;
import ir.cafebazaar.poolakey.callback.ConnectionCallback;
import ir.cafebazaar.poolakey.callback.ConsumeCallback;
import ir.cafebazaar.poolakey.callback.PurchaseCallback;
import ir.cafebazaar.poolakey.config.PaymentConfiguration;
import ir.cafebazaar.poolakey.config.SecurityCheck;
import ir.cafebazaar.poolakey.entity.PurchaseInfo;
import ir.cafebazaar.poolakey.request.PurchaseRequest;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/**
 * Poolakey-backed implementation of {@link StoreBillingBridge} for the Cafe
 * Bazaar flavor. Lives ONLY in this module so the shared :TMessagesProj library
 * never imports a Poolakey type.
 *
 * Premium is a subscription, so this uses {@code subscribeProduct}. On success
 * the raw Bazaar receipt (PurchaseInfo serialized to JSON, carrying the signed
 * dataJson + signature) is forwarded to the server via the placeholder
 * payments.assignBazaarTransaction MTProto method; the server verifies the
 * signature against Bazaar and activates premium, replying with Updates. Until
 * that server method exists the request fails with an RPC error, which is
 * surfaced to the user -- no premium is granted client-side.
 *
 * Every callback Poolakey can reach is guarded: Poolakey dispatches the
 * purchase result inline from {@code Activity.onActivityResult}, so any
 * Throwable escaping our handler kills the process at the exact moment the
 * Bazaar dialog closes.
 */
public class BazaarBillingBridge implements StoreBillingBridge {

    /**
     * Bazaar SKUs defined in the Cafe Bazaar developer panel (In-App Billing
     * tab). The server can override the mapping by populating
     * TL_premiumSubscriptionOption.store_product; otherwise months are used.
     */
    public static final String SKU_PREMIUM_MONTHLY = "thecat_premium_monthly";
    public static final String SKU_PREMIUM_YEARLY = "thecat_premium_yearly";

    /**
     * RSA public key from the Bazaar panel (In-App Billing tab). Enables
     * Poolakey's local signature check as a first line of defence; the server
     * MUST still verify independently.
     */
    private static final String BAZAAR_RSA_PUBLIC_KEY =
            "MIHNMA0GCSqGSIb3DQEBAQUAA4G7ADCBtwKBrwDYzR6TTCgSHqc4XnwB1b0uWUn5/3ek/z1P+qiYYs2e0davkBFXlDIMzZx+qRTsZDNWhdyceDsGUgTCDQBXvF+GoKK/uysaN+uDx6xYKkYNFXngUfNW2OEAYYApbSMTOqV0ZkuayKsquEcPrrUj0nULwQve+JV6bA7gDXbvDyzDG0UdlXvI7GBVXHOFsLVDuuAJgkhiXi795D/BSoFqZwwCe8n+YsnIqUFvP30Y6i8CAwEAAQ==";

    private static final Callback NO_OP_CALLBACK = new Callback() {
        @Override
        public void onSuccess() {
        }

        @Override
        public void onError(String message) {
        }

        @Override
        public void onCanceled() {
        }

        @Override
        public void onQueued() {
        }
    };

    private final Context appContext;
    /**
     * Recreated after a disconnect: Poolakey quits its HandlerThread from
     * {@code BillingConnection.disconnect()}, so a cached Payment stops
     * executing anything the moment the Bazaar service goes away -- which is
     * what usually happens right after the payment dialog closes.
     */
    private volatile Payment payment;
    private volatile boolean connected;

    public BazaarBillingBridge(Context context) {
        this.appContext = context.getApplicationContext();
        payment();
        // Let the durable outbox consume a stars/gift receipt once the server
        // finally confirms it during a delayed retry, so the user can rebuy.
        BazaarReceiptQueue.setConsumeHandler(this::consumeQuietly);
    }

    @Override
    public boolean isReady() {
        return connected;
    }

    /** Debug-only accessor so the pending-purchases screen can query the store. */
    public Payment getPaymentForDebug() {
        return payment();
    }

    @Override
    public void purchasePremiumSubscription(Activity activity, int account, TLRPC.TL_premiumSubscriptionOption option, String source, boolean upgrade, Callback callback) {
        final GuardedCallback guarded = new GuardedCallback(callback);
        if (activity == null) {
            guarded.error("Unable to start payment: no active screen.");
            return;
        }
        if (!(activity instanceof ComponentActivity)) {
            guarded.error("Unable to start payment: unsupported screen.");
            return;
        }
        final ActivityResultRegistry registry = ((ComponentActivity) activity).getActivityResultRegistry();
        final String sku = skuForOption(option);
        final AccountInstance accountInstance = AccountInstance.getInstance(account);

        // Reuse the persisted purpose machinery so the server can learn WHY the
        // user is paying (subscription vs upgrade) from the purchase payload.
        TLRPC.TL_inputStorePaymentPremiumSubscription purpose = new TLRPC.TL_inputStorePaymentPremiumSubscription();
        purpose.restore = false;
        purpose.upgrade = upgrade;
        final String payload = BillingUtilities.savePurpose(purpose);

        ensureConnected(new Runnable() {
            @Override
            public void run() {
                PaymentRequestHolder holder = new PaymentRequestHolder(sku, payload, accountInstance, purpose, guarded, false);
                startPurchase(registry, holder, true);
            }
        }, guarded);
    }

    @Override
    public void purchaseConsumable(Activity activity, int account, TLRPC.InputStorePaymentPurpose purpose, String sku, Callback callback) {
        final GuardedCallback guarded = new GuardedCallback(callback);
        if (activity == null) {
            guarded.error("Unable to start payment: no active screen.");
            return;
        }
        if (!(activity instanceof ComponentActivity)) {
            guarded.error("Unable to start payment: unsupported screen.");
            return;
        }
        if (TextUtils.isEmpty(sku)) {
            guarded.error("This item is not available for purchase yet.");
            return;
        }
        final ActivityResultRegistry registry = ((ComponentActivity) activity).getActivityResultRegistry();
        final AccountInstance accountInstance = AccountInstance.getInstance(account);
        final String payload = BillingUtilities.savePurpose(purpose);

        ensureConnected(new Runnable() {
            @Override
            public void run() {
                PaymentRequestHolder holder = new PaymentRequestHolder(sku, payload, accountInstance, purpose, guarded, true);
                startPurchase(registry, holder, false);
            }
        }, guarded);
    }

    private void startPurchase(ActivityResultRegistry registry, PaymentRequestHolder holder, boolean subscription) {
        final PurchaseRequest request = new PurchaseRequest(holder.sku, holder.payload, null);
        try {
            if (subscription) {
                payment().subscribeProduct(registry, request, purchaseCallbacks(holder));
            } else {
                payment().purchaseProduct(registry, request, purchaseCallbacks(holder));
            }
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: could not start " + holder.sku, t);
            holder.callback.error("Could not open Bazaar payment: " + messageOf(t));
            return;
        }
        // Anything Poolakey hands back from inside the call above is a leftover
        // of an earlier attempt whose launcher was unregistered, not a result of
        // this one -- only results arriving after the flow is on screen count.
        holder.armed = true;
    }

    private Function1<PurchaseCallback, Unit> purchaseCallbacks(final PaymentRequestHolder holder) {
        return new Function1<PurchaseCallback, Unit>() {
            @Override
            public Unit invoke(PurchaseCallback cb) {
                cb.purchaseSucceed(new Function1<PurchaseInfo, Unit>() {
                    @Override
                    public Unit invoke(PurchaseInfo info) {
                        if (holder.armed) {
                            guard(() -> onPurchaseSucceed(holder, info));
                        }
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseCanceled(new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        if (holder.armed) {
                            guard(() -> holder.callback.canceled());
                        }
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseFailed(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        if (holder.armed) {
                            FileLog.e("Bazaar billing: purchase failed", t);
                            guard(() -> holder.callback.error("Payment failed: " + messageOf(t)));
                        }
                        return Unit.INSTANCE;
                    }
                });
                cb.failedToBeginFlow(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: failed to begin flow", t);
                        guard(() -> holder.callback.error("Could not open Bazaar payment: " + messageOf(t)));
                        return Unit.INSTANCE;
                    }
                });
                return Unit.INSTANCE;
            }
        };
    }

    private void onPurchaseSucceed(PaymentRequestHolder holder, PurchaseInfo info) {
        // The store already took the user's money. Forward the signed receipt to
        // the server, which verifies it and activates premium via Updates. If the
        // server is unreachable (offline, or the method is not implemented yet)
        // the receipt is NOT dropped -- it is queued durably and re-sent until
        // the server acknowledges, so the purchase survives process death and
        // connectivity loss.
        String built = null;
        String token = null;
        boolean sent = false;
        try {
            built = buildReceiptJson(info);
            token = TextUtils.isEmpty(info.getPurchaseToken()) ? info.getOrderId() : info.getPurchaseToken();

            final String receiptJson = built;
            final String key = token;
            TLRPC.TL_payments_assignBazaarTransaction req = new TLRPC.TL_payments_assignBazaarTransaction();
            req.receipt = new TLRPC.TL_dataJSON();
            req.receipt.data = receiptJson;
            req.purpose = holder.purpose;

            int flags = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagInvokeAfter;
            holder.accountInstance.getConnectionsManager().sendRequest(req, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> onReceiptAnswer(holder, receiptJson, key, response, error)), flags);
            sent = true;
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: failed to forward receipt " + token, t);
            if (sent) {
                return;
            }
            // Money already taken: keep the receipt so it retries, and tell the
            // UI the purchase is safe rather than lost.
            queueReceipt(holder, built, token, messageOf(t));
            holder.callback.queued();
        }
    }

    private void onReceiptAnswer(PaymentRequestHolder holder, String receiptJson, String key, Object response, TLRPC.TL_error error) {
        try {
            if (response instanceof TLRPC.Updates) {
                holder.accountInstance.getMessagesController().processUpdates((TLRPC.Updates) response, false);
                // A consumable (stars/gift) must be consumed at the store once the
                // server has credited it, or the user can never buy it again.
                if (holder.consumable) {
                    consumeQuietly(key);
                }
                holder.callback.success();
            } else {
                // Queue for automatic retry instead of failing the purchase. The
                // user paid; only confirmation is pending.
                String errText = error == null ? "no response" : error.text;
                FileLog.e("Bazaar billing: assignBazaarTransaction failed (" + errText + "), queueing receipt " + key);
                queueReceipt(holder, receiptJson, key, errText);
                holder.callback.queued();
            }
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: could not apply receipt " + key, t);
            queueReceipt(holder, receiptJson, key, messageOf(t));
            holder.callback.queued();
        }
    }

    private static void queueReceipt(PaymentRequestHolder holder, String receiptJson, String key, String error) {
        if (TextUtils.isEmpty(receiptJson)) {
            return;
        }
        try {
            BazaarReceiptQueue.enqueue(holder.accountInstance.getCurrentAccount(), receiptJson, holder.payload, key, error, holder.consumable);
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: could not queue receipt " + key, t);
        }
    }

    /**
     * Consumes a purchased INAPP product so the user can buy it again. Fire and
     * forget: the receipt has already been accepted by the server, so a consume
     * failure must not affect the purchase result.
     */
    private void consumeQuietly(String purchaseToken) {
        if (TextUtils.isEmpty(purchaseToken)) {
            return;
        }
        try {
            payment().consumeProduct(purchaseToken, new Function1<ConsumeCallback, Unit>() {
                @Override
                public Unit invoke(ConsumeCallback cb) {
                    cb.consumeSucceed(new Function0<Unit>() {
                        @Override
                        public Unit invoke() {
                            FileLog.d("Bazaar billing: consumed " + purchaseToken);
                            return Unit.INSTANCE;
                        }
                    });
                    cb.consumeFailed(new Function1<Throwable, Unit>() {
                        @Override
                        public Unit invoke(Throwable t) {
                            FileLog.e("Bazaar billing: consume failed for " + purchaseToken, t);
                            return Unit.INSTANCE;
                        }
                    });
                    return Unit.INSTANCE;
                }
            });
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: consume threw for " + purchaseToken, t);
        }
    }

    /**
     * Serializes the Bazaar purchase into the JSON the server verifies. Carries
     * the raw signed payload (dataJson) and its RSA signature, plus the fields
     * the server cross-checks (productId / packageName / orderId / purchaseTime).
     */
    private String buildReceiptJson(PurchaseInfo info) {
        try {
            JSONObject root = new JSONObject();
            root.put("productId", info.getProductId());
            root.put("orderId", info.getOrderId());
            root.put("purchaseToken", info.getPurchaseToken());
            root.put("packageName", info.getPackageName());
            root.put("purchaseTime", info.getPurchaseTime());
            root.put("purchaseState", String.valueOf(info.getPurchaseState()));
            root.put("payload", info.getPayload() == null ? "" : info.getPayload());
            root.put("dataJson", info.getOriginalJson() == null ? "" : info.getOriginalJson());
            root.put("signature", info.getDataSignature() == null ? "" : info.getDataSignature());
            return root.toString();
        } catch (Exception e) {
            FileLog.e("Bazaar billing: failed to build receipt", e);
            // Fall back to the raw signed payload so the server at least gets
            // the data it verifies.
            return info.getOriginalJson() == null ? "{}" : info.getOriginalJson();
        }
    }

    private void ensureConnected(Runnable onConnected, GuardedCallback callback) {
        if (connected) {
            startOrReport(onConnected, callback);
            return;
        }
        try {
            payment().connect(new Function1<ConnectionCallback, Unit>() {
                @Override
                public Unit invoke(ConnectionCallback cb) {
                    cb.connectionSucceed(new Function0<Unit>() {
                        @Override
                        public Unit invoke() {
                            connected = true;
                            startOrReport(onConnected, callback);
                            return Unit.INSTANCE;
                        }
                    });
                    cb.connectionFailed(new Function1<Throwable, Unit>() {
                        @Override
                        public Unit invoke(Throwable t) {
                            connected = false;
                            FileLog.e("Bazaar billing: connection failed", t);
                            guard(() -> callback.error(connectionErrorMessage(t)));
                            return Unit.INSTANCE;
                        }
                    });
                    cb.disconnected(new Function0<Unit>() {
                        @Override
                        public Unit invoke() {
                            // Drop the dead Payment so the next attempt binds again
                            // instead of silently posting into a quit HandlerThread.
                            connected = false;
                            payment = null;
                            return Unit.INSTANCE;
                        }
                    });
                    return Unit.INSTANCE;
                }
            });
        } catch (Throwable t) {
            connected = false;
            FileLog.e("Bazaar billing: connect threw", t);
            guard(() -> callback.error(connectionErrorMessage(t)));
        }
    }

    private Payment payment() {
        Payment p = payment;
        if (p == null) {
            synchronized (this) {
                p = payment;
                if (p == null) {
                    SecurityCheck securityCheck = TextUtils.isEmpty(BAZAAR_RSA_PUBLIC_KEY)
                            ? SecurityCheck.Disable.INSTANCE
                            : new SecurityCheck.Enable(BAZAAR_RSA_PUBLIC_KEY);
                    p = payment = new Payment(appContext, new PaymentConfiguration(securityCheck, true));
                }
            }
        }
        return p;
    }

    private static String connectionErrorMessage(Throwable t) {
        if (t instanceof SecurityException) {
            return "Bazaar did not grant payment permission: " + messageOf(t);
        }
        return "Could not connect to Bazaar: " + messageOf(t);
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        final String message = t.getMessage();
        return TextUtils.isEmpty(message) ? t.getClass().getSimpleName() : message;
    }

    private static void guard(Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: callback threw", t);
        }
    }

    private static void startOrReport(Runnable body, GuardedCallback callback) {
        try {
            body.run();
        } catch (Throwable t) {
            FileLog.e("Bazaar billing: could not start purchase", t);
            callback.error("Could not open Bazaar payment: " + messageOf(t));
        }
    }

    /**
     * Picks the Bazaar SKU for a tier: prefers the server-provided
     * store_product, else maps by subscription length (>=12 months = yearly).
     */
    private String skuForOption(TLRPC.TL_premiumSubscriptionOption option) {
        if (option != null && !TextUtils.isEmpty(option.store_product)) {
            return option.store_product;
        }
        if (option != null && option.months >= 12) {
            return SKU_PREMIUM_YEARLY;
        }
        return SKU_PREMIUM_MONTHLY;
    }

    /**
     * Wraps the caller's callback so it fires at most once and never lets a
     * Throwable escape: the UI handlers (bulletins, presentFragment, fireworks)
     * routinely run against a fragment that the payment dialog has since
     * detached, and an exception there is fatal because Poolakey invokes us
     * straight off the activity-result dispatch.
     */
    private static final class GuardedCallback {

        private final Callback delegate;
        private final AtomicBoolean fired = new AtomicBoolean();

        GuardedCallback(Callback callback) {
            this.delegate = callback == null ? NO_OP_CALLBACK : callback;
        }

        void success() {
            fire("onSuccess", delegate::onSuccess);
        }

        void error(String message) {
            fire("onError", () -> delegate.onError(message));
        }

        void canceled() {
            fire("onCanceled", delegate::onCanceled);
        }

        void queued() {
            fire("onQueued", delegate::onQueued);
        }

        private void fire(String what, Runnable body) {
            if (!fired.compareAndSet(false, true)) {
                return;
            }
            try {
                body.run();
            } catch (Throwable t) {
                FileLog.e("Bazaar billing: " + what + " callback threw", t);
            }
        }
    }

    private static final class PaymentRequestHolder {
        final String sku;
        final String payload;
        final AccountInstance accountInstance;
        final TLRPC.InputStorePaymentPurpose purpose;
        final GuardedCallback callback;
        /** True for stars/gifts: consume at the store once the server confirms. */
        final boolean consumable;
        /** False until the purchase flow is on screen; filters stale results. */
        volatile boolean armed;

        PaymentRequestHolder(String sku, String payload, AccountInstance accountInstance, TLRPC.InputStorePaymentPurpose purpose, GuardedCallback callback, boolean consumable) {
            this.sku = sku;
            this.payload = payload;
            this.accountInstance = accountInstance;
            this.purpose = purpose;
            this.callback = callback;
            this.consumable = consumable;
        }
    }
}
