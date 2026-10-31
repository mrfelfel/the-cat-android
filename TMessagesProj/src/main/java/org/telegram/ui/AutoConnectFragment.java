package org.telegram.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;
import org.telegram.thecat.TheCatServer;
import org.telegram.thecat.TheCatServers;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Auto-connects an account to a server and lands on {@link LoginActivity}
 * without ever showing the multi-server picker.
 *
 * The Cat ships as a self-contained app whose default backend is its own
 * server, so first launch and add-account both land here and silently join
 * The Cat -- the picker stays under the hood,
 * reachable only from an operator's {@code thecat://addserver} flow. This is
 * the picker's connection half (bind server -> wait for MTProto -> learn
 * email-signup -> LoginActivity) as a self-running fragment, so it also works
 * where a fragment must be returned synchronously
 * (LaunchActivity#getClientNotActivatedFragment).
 */
public class AutoConnectFragment extends BaseFragment {

    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int POLL_INTERVAL_MS = 200;

    // When >= 0, this is the account slot to log into (add-account flow).
    // When -1, use currentAccount (first-launch flow).
    private final int loginAccount;

    public AutoConnectFragment() {
        this.loginAccount = -1;
    }

    public AutoConnectFragment(int loginAccount) {
        this.loginAccount = loginAccount;
    }

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable connectPoll;
    private AlertDialog progress;
    private boolean destroyed;

    @Override
    public boolean onFragmentCreate() {
        int targetAccount = targetAccount();
        TheCatServer server = TheCatServers.thecatServer();
        TheCatServers.setServerForAccount(server.id, targetAccount);
        TheCatServers.applyServerToAccount(server, targetAccount); // resetKeys=true
        ConnectionsManager.getInstance(targetAccount).resumeNetworkMaybe();
        return true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setTitle(org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.AppName));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout frame = new FrameLayout(context);
        frame.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        ProgressBar progressBar = new ProgressBar(context);
        frame.addView(progressBar, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        fragmentView = frame;

        waitForConnection(targetAccount(), TheCatServers.thecatServer());
        return fragmentView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        if (connectPoll != null) {
            uiHandler.removeCallbacks(connectPoll);
        }
        dismissDialog(progress);
        super.onFragmentDestroy();
    }

    private int targetAccount() {
        return (loginAccount >= 0) ? loginAccount : currentAccount;
    }

    private void waitForConnection(int account, TheCatServer server) {
        final long start = SystemClock.elapsedRealtime();
        progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);

        connectPoll = new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                int state = ConnectionsManager.native_getConnectionState(account);
                if (state == ConnectionsManager.ConnectionStateConnected) {
                    dismissDialog(progress);
                    checkEmailSignupAndGoToLogin(account, server);
                } else if (SystemClock.elapsedRealtime() - start > CONNECT_TIMEOUT_MS) {
                    dismissDialog(progress);
                    showConnectFailed(server);
                } else {
                    uiHandler.postDelayed(this, POLL_INTERVAL_MS);
                }
            }
        };
        uiHandler.postDelayed(connectPoll, POLL_INTERVAL_MS);
    }

    // A raw, one-off help.getAppConfig -- MessagesController.loadAppConfig()'s
    // cache fetcher doesn't set the unauthenticated-request flags this needs
    // pre-login. Resolves whether LoginActivity's phone screen should become an
    // email screen before its view is built.
    private void checkEmailSignupAndGoToLogin(int account, TheCatServer server) {
        TLRPC.TL_help_getAppConfig req = new TLRPC.TL_help_getAppConfig();
        req.hash = 0;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            boolean emailSignupEnabled = false;
            if (response instanceof TLRPC.TL_help_appConfig) {
                TLRPC.JSONValue config = ((TLRPC.TL_help_appConfig) response).config;
                if (config instanceof TLRPC.TL_jsonObject) {
                    for (TLRPC.TL_jsonObjectValue entry : ((TLRPC.TL_jsonObject) config).value) {
                        if ("email_signup_enabled".equals(entry.key) && entry.value instanceof TLRPC.TL_jsonBool) {
                            emailSignupEnabled = ((TLRPC.TL_jsonBool) entry.value).value;
                            break;
                        }
                    }
                }
            }
            if (destroyed) return;
            goToLogin(server, emailSignupEnabled);
        }), ConnectionsManager.RequestFlagWithoutLogin | ConnectionsManager.RequestFlagEnableUnauthorized | ConnectionsManager.RequestFlagFailOnServerErrors);
    }

    private void showConnectFailed(TheCatServer server) {
        if (getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity());
        b.setTitle("Connection failed");
        b.setMessage("Couldn't establish a connection to " + server.name + ". You can continue to the login screen and keep trying, or go back.");
        b.setPositiveButton("Continue", (d, w) -> goToLogin(server, false));
        b.setNegativeButton("Back", null);
        showDialog(b.create());
    }

    private void goToLogin(TheCatServer server, boolean emailSignupEnabled) {
        if (destroyed) return;
        // Parameterless LoginActivity for first login (target == selected account):
        // LoginActivity(n) sets newAccount=true and later calls switchToAccount(n),
        // which early-returns when n == selectedAccount, leaving an empty stack.
        LoginActivity login = (loginAccount >= 0 && loginAccount != UserConfig.selectedAccount)
                ? new LoginActivity(loginAccount)
                : new LoginActivity();
        login.setEmailSignupEnabled(emailSignupEnabled);
        presentFragment(login, true);
    }

    private void dismissDialog(AlertDialog dialog) {
        try {
            if (dialog != null && dialog.isShowing()) dialog.dismiss();
        } catch (Exception ignore) {
        }
    }
}
