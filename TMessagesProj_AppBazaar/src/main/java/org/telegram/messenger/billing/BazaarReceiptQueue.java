package org.telegram.messenger.billing;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.BillingUtilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable outbox for Bazaar purchase receipts.
 *
 * A user's money is taken by Bazaar the moment the store reports success, but
 * the Cat server still has to verify the receipt and activate premium. If the
 * server is unreachable, offline, or has not yet implemented
 * payments.assignBazaarTransaction, the receipt must NOT be dropped -- it is
 * persisted here and re-sent automatically until the server acknowledges.
 *
 * Persistence: each queued receipt stores the receipt JSON, the purpose hex
 * (whose full TL body BillingUtilities already persists under the "purchases"
 * prefs, so it survives process death), the account slot, and a dedup key (the
 * Bazaar purchaseToken, unique per purchase). Storage is a JSON array under the
 * "bazaar_receipt_queue" prefs.
 *
 * Retry: flush runs after every enqueue and on a capped exponential backoff
 * (15s -> 30 min) for as long as the queue is non-empty, so a receipt waits
 * indefinitely for the server to come up.
 */
public final class BazaarReceiptQueue {

    private static final String PREFS_NAME = "bazaar_receipt_queue";
    private static final String KEY_ITEMS = "items";

    private static final long BACKOFF_BASE_MS = 15_000L;
    private static final long BACKOFF_MAX_MS = 30 * 60_000L;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean flushing = new AtomicBoolean(false);
    private static Runnable scheduledFlush;
    private static long currentBackoffMs = BACKOFF_BASE_MS;
    /** Cleared once the receipt RPC class is found missing; stops all re-arming. */
    private static volatile boolean rpcAvailable = true;

    private BazaarReceiptQueue() {
    }

    // --- Persistence ---

    public static class QueuedReceipt {
        public final String key;
        public final int account;
        public final String receiptJson;
        public final String purposeHex;
        public final long createdAt;
        public final int attempts;
        public final String lastError;

        QueuedReceipt(String key, int account, String receiptJson, String purposeHex, long createdAt, int attempts, String lastError) {
            this.key = key;
            this.account = account;
            this.receiptJson = receiptJson;
            this.purposeHex = purposeHex;
            this.createdAt = createdAt;
            this.attempts = attempts;
            this.lastError = lastError;
        }
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static List<QueuedReceipt> load() {
        List<QueuedReceipt> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs().getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                result.add(new QueuedReceipt(
                        o.optString("key", ""),
                        o.optInt("account", 0),
                        o.optString("receipt", ""),
                        o.optString("purposeHex", ""),
                        o.optLong("created", 0),
                        o.optInt("attempts", 0),
                        o.optString("lastError", "")));
            }
        } catch (Exception e) {
            FileLog.e("BazaarReceiptQueue: failed to load", e);
        }
        return result;
    }

    private static void save(List<QueuedReceipt> items) {
        try {
            JSONArray array = new JSONArray();
            for (QueuedReceipt r : items) {
                JSONObject o = new JSONObject();
                o.put("key", r.key);
                o.put("account", r.account);
                o.put("receipt", r.receiptJson);
                o.put("purposeHex", r.purposeHex);
                o.put("created", r.createdAt);
                o.put("attempts", r.attempts);
                o.put("lastError", r.lastError == null ? "" : r.lastError);
                array.put(o);
            }
            prefs().edit().putString(KEY_ITEMS, array.toString()).apply();
        } catch (Exception e) {
            FileLog.e("BazaarReceiptQueue: failed to save", e);
        }
    }

    // --- Public API ---

    /** Snapshot of everything still waiting on the server. UI-safe copy. */
    public static synchronized List<QueuedReceipt> snapshot() {
        return new ArrayList<>(load());
    }

    public static synchronized int size() {
        return load().size();
    }

    public static synchronized boolean isEmpty() {
        return load().isEmpty();
    }

    /**
     * Stores a receipt that the server has not acknowledged yet and kicks the
     * retry loop. Dedup is by {@code key} (the Bazaar purchaseToken): re-adding
     * the same purchase refreshes its error but never duplicates the row.
     */
    public static synchronized void enqueue(int account, String receiptJson, String purposeHex, String key, String error) {
        if (TextUtils.isEmpty(receiptJson)) {
            return;
        }
        List<QueuedReceipt> items = load();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) {
                QueuedReceipt old = items.get(i);
                items.set(i, new QueuedReceipt(key, account, receiptJson, purposeHex, old.createdAt, old.attempts, error));
                save(items);
                scheduleFlush();
                return;
            }
        }
        items.add(new QueuedReceipt(key, account, receiptJson, purposeHex, System.currentTimeMillis(), 0, error));
        save(items);
        scheduleFlush();
    }

    public static synchronized void remove(String key) {
        List<QueuedReceipt> items = load();
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).key.equals(key)) {
                items.remove(i);
                save(items);
                return;
            }
        }
    }

    // --- Retry scheduling ---

    /** Kicks the auto-retry loop (idempotent). Safe to call on every app start. */
    public static void scheduleAutoFlush() {
        if (!rpcAvailable) {
            return;
        }
        if (isEmpty()) {
            resetBackoff();
            return;
        }
        scheduleFlush();
    }

    private static void scheduleFlush() {
        if (scheduledFlush != null) {
            handler.removeCallbacks(scheduledFlush);
        }
        scheduledFlush = () -> {
            scheduledFlush = null;
            flush(null);
        };
        long delay = isEmpty() ? 0 : currentBackoffMs;
        handler.postDelayed(scheduledFlush, delay);
    }

    /**
     * Stops the auto-retry loop for the rest of this process. Used when the
     * receipt RPC class is absent from the build: no amount of retrying can
     * succeed, and an unguarded retry loop turns a queued receipt into a crash
     * loop on every app start. Receipts stay queued so a fixed APK can send them.
     */
    private static void stopRetries() {
        if (scheduledFlush != null) {
            handler.removeCallbacks(scheduledFlush);
            scheduledFlush = null;
        }
        flushing.set(false);
    }

    private static void resetBackoff() {
        currentBackoffMs = BACKOFF_BASE_MS;
    }

    private static void bumpBackoff() {
        currentBackoffMs = Math.min(BACKOFF_MAX_MS, currentBackoffMs * 2);
    }

    /**
     * Attempts to deliver every queued receipt. Each item is sent independently;
     * a server acknowledgement removes it, anything else keeps it and records the
     * error for the debug screen. Retries keep going (with backoff) until the
     * queue drains.
     *
     * @param onDone optional callback on the UI thread once this pass finishes.
     */
    public static void flush(Runnable onDone) {
        if (!flushing.compareAndSet(false, true)) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        List<QueuedReceipt> items = snapshot();
        if (items.isEmpty()) {
            flushing.set(false);
            resetBackoff();
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        final int[] remaining = {items.size()};
        for (QueuedReceipt item : items) {
            sendOne(item, ok -> AndroidUtilities.runOnUIThread(() -> {
                if (ok) {
                    remove(item.key);
                } else {
                    recordFailure(item);
                }
                if (--remaining[0] == 0) {
                    flushing.set(false);
                    if (isEmpty()) {
                        resetBackoff();
                    } else if (rpcAvailable) {
                        bumpBackoff();
                        scheduleFlush();
                    }
                    if (onDone != null) {
                        onDone.run();
                    }
                }
            }));
        }
    }

    private static void recordFailure(QueuedReceipt item) {
        List<QueuedReceipt> items = load();
        for (int i = 0; i < items.size(); i++) {
            QueuedReceipt cur = items.get(i);
            if (cur.key.equals(item.key)) {
                items.set(i, new QueuedReceipt(cur.key, cur.account, cur.receiptJson, cur.purposeHex, cur.createdAt, cur.attempts + 1, cur.lastError));
                save(items);
                return;
            }
        }
    }

    private static void sendOne(QueuedReceipt item, Utilities.Callback<Boolean> done) {
        TLRPC.InputStorePaymentPurpose purpose;
        try {
            purpose = BillingUtilities.getPurpose(item.purposeHex);
        } catch (Exception e) {
            // Purpose is unrecoverable (prefs wiped / corrupt). The receipt can
            // never be attributed, so drop it rather than retry forever.
            FileLog.e("BazaarReceiptQueue: dropping " + item.key + ", purpose unrecoverable", e);
            remove(item.key);
            done.run(true);
            return;
        }
        final TLRPC.TL_payments_assignBazaarTransaction req;
        try {
            req = new TLRPC.TL_payments_assignBazaarTransaction();
        } catch (LinkageError e) {
            // The TL class is missing from the APK (shrinker dropped it). Retrying
            // can never succeed, so stop the loop instead of crash-looping on
            // every app start; the receipt stays queued for a fixed build.
            FileLog.e("BazaarReceiptQueue: receipt RPC missing from this build, pausing retries", e);
            rpcAvailable = false;
            stopRetries();
            done.run(false);
            return;
        }
        req.receipt = new TLRPC.TL_dataJSON();
        req.receipt.data = item.receiptJson;
        req.purpose = purpose;

        final AccountInstance acc;
        try {
            acc = AccountInstance.getInstance(item.account);
        } catch (Throwable t) {
            BazaarErrors.reportQuietly("receipt queue", t);
            done.run(false);
            return;
        }
        int flags = ConnectionsManager.RequestFlagFailOnServerErrors | ConnectionsManager.RequestFlagInvokeAfter;
        acc.getConnectionsManager().sendRequest(req, (response, error) -> {
            boolean ok = response instanceof TLRPC.Updates;
            if (ok) {
                try {
                    acc.getMessagesController().processUpdates((TLRPC.Updates) response, false);
                } catch (Throwable t) {
                    // The payment itself is fine; only applying the updates failed.
                    BazaarErrors.reportQuietly("apply receipt " + item.key, t);
                }
            } else {
                FileLog.e("BazaarReceiptQueue: " + item.key + " not accepted: " + (error == null ? "null response" : error.text));
            }
            done.run(ok);
        }, flags);
    }
}
