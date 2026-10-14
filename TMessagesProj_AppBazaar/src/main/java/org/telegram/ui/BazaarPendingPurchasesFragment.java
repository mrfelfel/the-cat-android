package org.telegram.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.billing.BazaarBillingBridge;
import org.telegram.messenger.billing.BazaarReceiptQueue;
import org.telegram.messenger.billing.StoreBilling;
import org.telegram.messenger.web.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ir.cafebazaar.poolakey.Payment;
import ir.cafebazaar.poolakey.callback.PurchaseQueryCallback;
import ir.cafebazaar.poolakey.entity.PurchaseInfo;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * Operator-facing screen listing Bazaar purchases that are not yet reflected on
 * the Cat server.
 *
 * Two sources, merged into one list:
 *   1. The durable local outbox ({@link BazaarReceiptQueue}) -- receipts the
 *      client paid for but the server has not acknowledged. These are re-sent
 *      automatically; this screen is how an operator inspects failures (each row
 *      shows the last server error) and can force a resend or drop one.
 *   2. The store's own record (Poolakey getSubscribedProducts/getPurchasedProducts)
 *      so an operator can see what Bazaar thinks the user owns, independent of
 *      our queue.
 */
public class BazaarPendingPurchasesFragment extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_QUEUE = 1;
    private static final int TYPE_STORE = 2;
    private static final int TYPE_ACTION = 3;

    private RecyclerListView listView;
    private ListAdapter adapter;

    private final List<Object> rows = new ArrayList<>();
    private final List<BazaarReceiptQueue.QueuedReceipt> queued = new ArrayList<>();
    private final List<PurchaseInfo> storePurchases = new ArrayList<>();
    private String storeError;

    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    @Override
    public boolean onFragmentCreate() {
        rebuild();
        loadStorePurchases();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle("Pending purchases");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frame;

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setVerticalScrollBarEnabled(false);
        frame.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        return fragmentView;
    }

    private void rebuild() {
        queued.clear();
        queued.addAll(BazaarReceiptQueue.snapshot());

        rows.clear();
        rows.add("queue_header");
        if (queued.isEmpty()) {
            rows.add("queue_empty");
        } else {
            rows.addAll(queued);
            rows.add("resend_all");
        }

        rows.add("store_header");
        if (storeError != null) {
            rows.add("store_error");
        } else if (storePurchases.isEmpty()) {
            rows.add("store_empty");
        } else {
            rows.addAll(storePurchases);
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Queries Bazaar directly for what the user owns, bypassing our queue. */
    private void loadStorePurchases() {
        if (StoreBilling.get() instanceof BazaarBillingBridge) {
            Payment payment = ((BazaarBillingBridge) StoreBilling.get()).getPaymentForDebug();
            payment.getSubscribedProducts(new Function1<PurchaseQueryCallback, Unit>() {
                @Override
                public Unit invoke(PurchaseQueryCallback cb) {
                    cb.querySucceed(new Function1<List<PurchaseInfo>, Unit>() {
                        @Override
                        public Unit invoke(List<PurchaseInfo> list) {
                            AndroidUtilities.runOnUIThread(() -> {
                                storeError = null;
                                storePurchases.clear();
                                if (list != null) {
                                    storePurchases.addAll(list);
                                }
                                rebuild();
                            });
                            return Unit.INSTANCE;
                        }
                    });
                    cb.queryFailed(new Function1<Throwable, Unit>() {
                        @Override
                        public Unit invoke(Throwable t) {
                            FileLog.e("BazaarPending: store query failed", t);
                            AndroidUtilities.runOnUIThread(() -> {
                                storeError = t.getMessage() == null ? "Bazaar store query failed." : t.getMessage();
                                rebuild();
                            });
                            return Unit.INSTANCE;
                        }
                    });
                    return Unit.INSTANCE;
                }
            });
        }
    }

    private void resendAll() {
        BazaarReceiptQueue.flush(() -> AndroidUtilities.runOnUIThread(this::rebuild));
    }

    private void drop(final BazaarReceiptQueue.QueuedReceipt receipt) {
        BazaarReceiptQueue.remove(receipt.key);
        rebuild();
    }

    private String formatTime(long millis) {
        if (millis <= 0) {
            return "";
        }
        return timeFormat.format(new Date(millis));
    }

    private class ListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof BazaarReceiptQueue.QueuedReceipt) return TYPE_QUEUE;
            if (o instanceof PurchaseInfo) return TYPE_STORE;
            if ("resend_all".equals(o)) return TYPE_ACTION;
            return TYPE_HEADER;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            switch (type) {
                case TYPE_QUEUE: {
                    TextView tv = new TextView(context);
                    tv.setTextSize(14);
                    tv.setGravity(Gravity.START | Gravity.TOP);
                    tv.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                    return new Holder(tv);
                }
                case TYPE_STORE: {
                    TextView tv = new TextView(context);
                    tv.setTextSize(14);
                    tv.setGravity(Gravity.START | Gravity.TOP);
                    tv.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                    return new Holder(tv);
                }
                case TYPE_ACTION: {
                    TextView tv = new TextView(context);
                    tv.setTextSize(15);
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    tv.setGravity(Gravity.CENTER);
                    tv.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
                    return new Holder(tv);
                }
                default: {
                    TextView tv = new TextView(context);
                    tv.setTextSize(13);
                    tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    tv.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(18), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
                    return new Holder(tv);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            TextView tv = (TextView) holder.itemView;
            if (o instanceof BazaarReceiptQueue.QueuedReceipt) {
                BazaarReceiptQueue.QueuedReceipt r = (BazaarReceiptQueue.QueuedReceipt) o;
                String title = "Queued receipt";
                String body = "key: " + r.key + "\n"
                        + "time: " + formatTime(r.createdAt) + "\n"
                        + "retries: " + r.attempts + "\n"
                        + (TextUtils.isEmpty(r.lastError) ? "" : "last error: " + r.lastError);
                tv.setText(AndroidUtilities.replaceTags(title + "\n" + escape(body)));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                tv.setOnClickListener(v -> drop(r));
                tv.setOnLongClickListener(v -> {
                    resendAll();
                    return true;
                });
            } else if (o instanceof PurchaseInfo) {
                PurchaseInfo p = (PurchaseInfo) o;
                String body = "product: " + p.getProductId() + "\n"
                        + "orderId: " + p.getOrderId() + "\n"
                        + "state: " + p.getPurchaseState() + "\n"
                        + "time: " + formatTime(p.getPurchaseTime());
                tv.setText(AndroidUtilities.replaceTags(escape(body)));
                tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                tv.setOnClickListener(null);
            } else if ("resend_all".equals(o)) {
                tv.setText("Resend all queued");
                tv.setOnClickListener(v -> resendAll());
            } else if ("queue_header".equals(o)) {
                tv.setText("Waiting for server (" + queued.size() + ")");
            } else if ("store_header".equals(o)) {
                tv.setText("Bazaar store record");
            } else if ("queue_empty".equals(o)) {
                tv.setText("Nothing waiting. All purchases are confirmed.");
            } else if ("store_empty".equals(o)) {
                tv.setText("Bazaar reports no active purchases.");
            } else if ("store_error".equals(o)) {
                tv.setText("Store query error: " + storeError);
            }
        }

        private String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
