package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import androidx.core.content.FileProvider;

import org.json.JSONObject;
import org.telegram.messenger.billing.BazaarBillingBridge;
import org.telegram.messenger.billing.BazaarReceiptQueue;
import org.telegram.messenger.billing.StoreBillingBridge;
import org.telegram.messenger.web.BuildConfig;
import org.telegram.messenger.web.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.BazaarPendingPurchasesFragment;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;

import java.io.File;

/**
 * Cafe Bazaar flavor. Identical to the standalone loader minus the SMS jobs
 * feature, and with isBazaarBuild() reporting true so the premium purchase path
 * routes through the Poolakey store bridge instead of Google Play or the
 * invoice/bot flow.
 */
public class ApplicationLoaderImpl extends ApplicationLoader {

    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    @Override
    protected boolean isBazaarBuild() {
        return true;
    }

    /**
     * Provides the Poolakey-backed store bridge. The base ApplicationLoader.onCreate()
     * assigns it into StoreBilling.bridge. Overriding this virtual method (instead of
     * writing StoreBilling.bridge directly from here) is deliberate: R8 mangled a
     * cross-module app->library field/method reference during minification, causing
     * NoSuchField/NoSuchMethodError crashes at startup. A virtual override is resolved
     * by normal dispatch and R8 processes it consistently.
     */
    @Override
    protected StoreBillingBridge onCreateStoreBillingBridge() {
        return new BazaarBillingBridge(this);
    }

    @Override
    protected void startAppCenterInternal(Activity context) {
    }

    @Override
    protected void checkForUpdatesInternal() {
    }

    @Override
    protected void appCenterLogInternal(Throwable e) {
    }

    @Override
    protected void logDualCameraInternal(boolean success, boolean vendor) {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // The store bridge itself is provided by onCreateStoreBillingBridge()
        // above and registered by the base ApplicationLoader.onCreate(), so it is
        // in place before any UI can trigger a purchase. Kept out of this module
        // deliberately: writing StoreBilling.bridge from here made R8 mangle the
        // cross-module reference and crash at startup.
        // Re-send any receipts the server never acknowledged (purchases made
        // while offline or before the server method existed). Idempotent: only
        // schedules work when the queue is non-empty.
        BazaarReceiptQueue.scheduleAutoFlush();
    }

    @Override
    public boolean checkApkInstallPermissions(final Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ApplicationLoader.applicationContext.getPackageManager().canRequestPackageInstalls()) {
            AlertsCreator.createApkRestrictedDialog(context, null).show();
            return false;
        }
        return true;
    }

    @Override
    public boolean openApkInstall(Activity activity, TLRPC.Document document) {
        boolean exists = false;
        try {
            String fileName = FileLoader.getAttachFileName(document);
            File f = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true);
            if (exists = f.exists()) {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

                if (Build.VERSION.SDK_INT >= 24) {
                    intent.setDataAndType(FileProvider.getUriForFile(activity, ApplicationLoader.getApplicationId() + ".provider", f), "application/vnd.android.package-archive");
                } else {
                    intent.setDataAndType(Uri.fromFile(f), "application/vnd.android.package-archive");
                }
                try {
                    activity.startActivityForResult(intent, 500);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return exists;
    }

    @Override
    public boolean showUpdateAppPopup(Context context, TLRPC.TL_help_appUpdate update, int account) {
        return super.showUpdateAppPopup(context, update, account);
    }

    @Override
    public boolean checkRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        return super.checkRequestPermissionResult(requestCode, permissions, grantResults);
    }

    @Override
    public void addItemOptions(ItemOptions itemOptions) {
        // Debug/operator entry: opens the pending-purchases screen so an admin
        // can inspect receipts the server has not confirmed, resend them, or
        // drop a bad one. Module-local, so it only ships in the Bazaar flavor.
        final int pending = BazaarReceiptQueue.size();
        String label = "Pending purchases";
        if (pending > 0) {
            label = label + " (" + pending + ")";
        }
        itemOptions.add(R.drawable.msg_info, label, () -> {
            BaseFragment lastFragment = LaunchActivity.getSafeLastFragment();
            if (lastFragment != null) {
                lastFragment.presentFragment(new BazaarPendingPurchasesFragment());
            }
        });
    }

    @Override
    public boolean consumePush(int account, JSONObject json) {
        return false;
    }

    @Override
    public boolean onPause() {
        return super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public BaseFragment openSettings(int n) {
        return null;
    }
}
