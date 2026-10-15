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

    private final Payment payment;
    private volatile boolean connected;

    public BazaarBillingBridge(Context context) {
        SecurityCheck securityCheck = TextUtils.isEmpty(BAZAAR_RSA_PUBLIC_KEY)
                ? SecurityCheck.Disable.INSTANCE
                : new SecurityCheck.Enable(BAZAAR_RSA_PUBLIC_KEY);
        this.payment = new Payment(context, new PaymentConfiguration(securityCheck, true));
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
        return payment;
    }

    @Override
    public void purchasePremiumSubscription(Activity activity, int account, TLRPC.TL_premiumSubscriptionOption option, String source, boolean upgrade, Callback callback) {
        if (activity == null) {
            callback.onError("Unable to start payment: no active screen.");
            return;
        }
        if (!(activity instanceof ComponentActivity)) {
            callback.onError("Unable to start payment: unsupported screen.");
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
                PaymentRequestHolder holder = new PaymentRequestHolder(sku, payload, accountInstance, purpose, callback, false);
                startSubscribe(registry, holder);
            }
        }, callback);
    }

    @Override
    public void purchaseConsumable(Activity activity, int account, TLRPC.InputStorePaymentPurpose purpose, String sku, Callback callback) {
        if (activity == null) {
            callback.onError("Unable to start payment: no active screen.");
            return;
        }
        if (!(activity instanceof ComponentActivity)) {
            callback.onError("Unable to start payment: unsupported screen.");
            return;
        }
        if (TextUtils.isEmpty(sku)) {
            callback.onError("This item is not available for purchase yet.");
            return;
        }
        final ActivityResultRegistry registry = ((ComponentActivity) activity).getActivityResultRegistry();
        final AccountInstance accountInstance = AccountInstance.getInstance(account);
        final String payload = BillingUtilities.savePurpose(purpose);

        ensureConnected(new Runnable() {
            @Override
            public void run() {
                PaymentRequestHolder holder = new PaymentRequestHolder(sku, payload, accountInstance, purpose, callback, true);
                startConsumable(registry, holder);
            }
        }, callback);
    }

    /** INAPP purchase for stars/gifts; mirrors startSubscribe but for one-time products. */
    private void startConsumable(ActivityResultRegistry registry, PaymentRequestHolder holder) {
        final PurchaseRequest request = new PurchaseRequest(holder.sku, holder.payload, null);
        payment.purchaseProduct(registry, request, new Function1<PurchaseCallback, Unit>() {
            @Override
            public Unit invoke(PurchaseCallback cb) {
                cb.purchaseSucceed(new Function1<PurchaseInfo, Unit>() {
                    @Override
                    public Unit invoke(PurchaseInfo info) {
                        onPurchaseSucceed(holder, info);
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseCanceled(new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onCanceled());
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseFailed(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: consumable purchase failed", t);
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onError("Payment failed: " + t.getMessage()));
                        return Unit.INSTANCE;
                    }
                });
                cb.failedToBeginFlow(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: failed to begin consumable flow", t);
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onError("Could not open Bazaar payment: " + t.getMessage()));
                        return Unit.INSTANCE;
                    }
                });
                return Unit.INSTANCE;
            }
        });
    }

    private void startSubscribe(ActivityResultRegistry registry, PaymentRequestHolder holder) {
        final PurchaseRequest request = new PurchaseRequest(holder.sku, holder.payload, null);
        payment.subscribeProduct(registry, request, new Function1<PurchaseCallback, Unit>() {
            @Override
            public Unit invoke(PurchaseCallback cb) {
                cb.purchaseSucceed(new Function1<PurchaseInfo, Unit>() {
                    @Override
                    public Unit invoke(PurchaseInfo info) {
                        onPurchaseSucceed(holder, info);
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseCanceled(new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onCanceled());
                        return Unit.INSTANCE;
                    }
                });
                cb.purchaseFailed(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: purchase failed", t);
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onError("Payment failed: " + t.getMessage()));
                        return Unit.INSTANCE;
                    }
                });
                cb.failedToBeginFlow(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: failed to begin flow", t);
                        AndroidUtilities.runOnUIThread(() -> holder.callback.onError("Could not open Bazaar payment: " + t.getMessage()));
                        return Unit.INSTANCE;
                    }
                });
                return Unit.INSTANCE;
            }
        });
    }

    private void onPurchaseSucceed(PaymentRequestHolder holder, PurchaseInfo info) {
        // The store already took the user's money. Forward the signed receipt to
        // the server, which verifies it and activates premium via Updates. If the
        // server is unreachable (offline, or the method is not implemented yet)
        // the receipt is NOT dropped -- it is queued durably and re-sent until
        // the server acknowledges, so the purchase survives process death and
        // connectivity loss.
        String receiptJson = buildReceiptJson(info);
        String key = info.getPurchaseToken() != null ? info.getPurchaseToken() : info.getOrderId();

        TLRPC.TL_payments_assignBazaarTransaction req = new TLRPC.TL_payments_assignBazaarTransaction();
        req.receipt = new TLRPC.TL_dataJSON();
        req.receipt.data = receiptJson;
        req.purpose = holder.purpose;

        int flags = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagInvokeAfter;
        holder.accountInstance.getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.Updates) {
                holder.accountInstance.getMessagesController().processUpdates((TLRPC.Updates) response, false);
                // A consumable (stars/gift) must be consumed at the store once the
                // server has credited it, or the user can never buy it again.
                if (holder.consumable) {
                    consumeQuietly(key);
                }
                holder.callback.onSuccess();
            } else {
                // Queue for automatic retry instead of failing the purchase. The
                // user paid; only confirmation is pending.
                String errText = error == null ? "no response" : error.text;
                FileLog.e("Bazaar billing: assignBazaarTransaction failed (" + errText + "), queueing receipt " + key);
                BazaarReceiptQueue.enqueue(holder.accountInstance.getCurrentAccount(), receiptJson, holder.payload, key, errText, holder.consumable);
                holder.callback.onQueued();
            }
        }), flags);
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
        payment.consumeProduct(purchaseToken, new Function1<ConsumeCallback, Unit>() {
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

    private void ensureConnected(Runnable onConnected, Callback callback) {
        if (connected) {
            onConnected.run();
            return;
        }
        payment.connect(new Function1<ConnectionCallback, Unit>() {
            @Override
            public Unit invoke(ConnectionCallback cb) {
                cb.connectionSucceed(new Function0<Unit>() {
                    @Override
                    public Unit invoke() {
                        connected = true;
                        onConnected.run();
                        return Unit.INSTANCE;
                    }
                });
                cb.connectionFailed(new Function1<Throwable, Unit>() {
                    @Override
                    public Unit invoke(Throwable t) {
                        FileLog.e("Bazaar billing: connection failed", t);
                        AndroidUtilities.runOnUIThread(() -> callback.onError("Could not connect to Bazaar: " + t.getMessage()));
                        return Unit.INSTANCE;
                    }
                });
                return Unit.INSTANCE;
            }
        });
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

    private static final class PaymentRequestHolder {
        final String sku;
        final String payload;
        final AccountInstance accountInstance;
        final TLRPC.InputStorePaymentPurpose purpose;
        final Callback callback;
        /** True for stars/gifts: consume at the store once the server confirms. */
        final boolean consumable;

        PaymentRequestHolder(String sku, String payload, AccountInstance accountInstance, TLRPC.InputStorePaymentPurpose purpose, Callback callback, boolean consumable) {
            this.sku = sku;
            this.payload = payload;
            this.accountInstance = accountInstance;
            this.purpose = purpose;
            this.callback = callback;
            this.consumable = consumable;
        }
    }
}
