package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * About Us / Contact page for The Cat.
 * Shows the Gen-Z intro, Cat Room links and the support center phone.
 * Colors all come from the Theme engine so it follows light/dark automatically.
 */
public class AboutFragment extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.AboutUs));
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

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        frame.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(24));
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // App title
        content.addView(makeTitle(context, LocaleController.getString(R.string.AppName)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Intro / about text
        content.addView(makeBody(context, LocaleController.getString(R.string.AboutUsInfo)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        // Support section
        content.addView(makeSectionHeader(context, LocaleController.getString(R.string.ContactUs)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 8, 0, 4));
        content.addView(makeLinkRow(context, LocaleController.getString(R.string.SupportPhoneInfo), LocaleController.getString(R.string.SupportPhone), "tel:02187210128"), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // Links section
        content.addView(makeSectionHeader(context, LocaleController.getString(R.string.CatRoom)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 16, 0, 4));
        content.addView(makeLinkRow(context, LocaleController.getString(R.string.CatRoom), LocaleController.getString(R.string.CatRoomUrl), LocaleController.getString(R.string.CatRoomUrl)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        content.addView(makeLinkRow(context, LocaleController.getString(R.string.PrivacyPolicy), LocaleController.getString(R.string.PrivacyPolicyUrl), LocaleController.getString(R.string.PrivacyPolicyUrl)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));
        content.addView(makeLinkRow(context, LocaleController.getString(R.string.TermsOfService), LocaleController.getString(R.string.TermsOfServiceUrl), LocaleController.getString(R.string.TermsOfServiceUrl)), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        fragmentView = frame;
        return frame;
    }

    private TextView makeTitle(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 24);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private TextView makeBody(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setLineSpacing(AndroidUtilities.dp(3), 1);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private TextView makeSectionHeader(Context context, String text) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setGravity(Gravity.START);
        return tv;
    }

    private View makeLinkRow(Context context, String title, String subtitle, String url) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        row.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));

        TextView titleView = new TextView(context);
        titleView.setText(title);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setGravity(Gravity.START);
        row.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (subtitle != null && !subtitle.equals(title)) {
            TextView subView = new TextView(context);
            subView.setText(subtitle);
            subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            subView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            subView.setGravity(Gravity.START);
            row.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
        }

        final String openUrl = url;
        row.setOnClickListener(v -> {
            if (openUrl != null && getParentActivity() != null) {
                Browser.openUrl(getParentActivity(), openUrl);
            }
        });
        return row;
    }
}
