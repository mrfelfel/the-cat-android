package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BillingController;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.utils.TheCatMoney;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_fragment;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Stars.StarsController;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.util.ArrayList;

public class FragmentUsernameBottomSheet {

    public static final int TYPE_USERNAME = 0;
    public static final int TYPE_PHONE = 1;

    public static void open(
        Context context,
        int type,
        String name,
        TLObject owner,
        TL_fragment.TL_collectibleInfo info,
        Theme.ResourcesProvider resourcesProvider
    ) {
        BottomSheet sheet = new BottomSheet(context, false, resourcesProvider);
        sheet.fixNavigationBar(Theme.getColor(Theme.key_dialogBackground, resourcesProvider));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), 0, dp(16), 0);

        FrameLayout imageContainerView = new FrameLayout(context);
        imageContainerView.setBackground(Theme.createCircleDrawable(dp(80), Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider)));
        layout.addView(imageContainerView, LayoutHelper.createLinear(80, 80, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 16));

        RLottieImageView imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        int sz = type == TYPE_USERNAME ? 70 : 78;
        imageView.setAnimation(type == TYPE_USERNAME ? R.raw.fragment_username : R.raw.fragment, sz, sz);
        imageView.playAnimation();
        imageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        if (type == TYPE_USERNAME) {
            imageView.setScaleX(0.86f);
            imageView.setScaleY(0.86f);
        } else {
            imageView.setTranslationY(dp(2));
        }
        imageContainerView.addView(imageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER));

        final String ownerName;
        if (owner instanceof TLRPC.User) {
            ownerName = UserObject.getUserName((TLRPC.User) owner);
        } else if (owner instanceof TLRPC.Chat) {
            ownerName = ((TLRPC.Chat) owner).title;
        } else {
            ownerName = "";
        }

        final String money = BillingController.getInstance().formatCurrency(info.amount, info.currency);
        // The Cat: the server sends amount=0 with an empty currency for a star
        // purchase. formatCurrency then returns the bare "0", which used to be
        // printed in parentheses as a second price. Only show the fiat part when
        // it carries a real amount.
        final boolean hasFiat = !TextUtils.isEmpty(info.currency) && info.amount != 0;
        final String fiatPart = hasFiat ? "(" + money + ")" : "";

        final String title;
        final String link;
        if (type == TYPE_USERNAME) {
            title = LocaleController.formatString(R.string.FragmentUsernameTitle, "@" + name);
            link = MessagesController.getInstance(UserConfig.selectedAccount).linkPrefix + "/" + name;
        } else if (type == TYPE_PHONE) {
            title = LocaleController.formatString(R.string.FragmentPhoneTitle, PhoneFormat.getInstance().format("+" + name));
            link = PhoneFormat.getInstance().format("+" + name);
        } else {
            return;
        }

        // The toman equivalent needs the star catalog, which often has not been
        // cached yet. Load it and re-render the price line once it arrives.
        StarsController.getInstance(UserConfig.selectedAccount).getOptions();
        final boolean waitingForStars = "XTR".equalsIgnoreCase(info.crypto_currency) && tomanForStars(info.crypto_amount) <= 0;
        final String message = buildMessage(type, info, fiatPart);

        Runnable copy = link != null ? () -> {
            AndroidUtilities.addToClipboard(link);
            if (type == TYPE_PHONE) {
                BulletinFactory.of(sheet.getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.PhoneCopied)).show();
            } else {
                BulletinFactory.of(sheet.getContainer(), resourcesProvider).createCopyLinkBulletin().show();
            }
        } : null;

        CharSequence titleSpanned = AndroidUtilities.replaceSingleTag(title, copy);

        CharSequence messageSpanned = decorateIcons(message);

        TextView headerView = new LinkSpanDrawable.LinksTextView(context);
        headerView.setTypeface(AndroidUtilities.bold());
        headerView.setGravity(Gravity.CENTER);
        headerView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        headerView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourcesProvider));
        headerView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        headerView.setText(titleSpanned);
        layout.addView(headerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 42, 0, 42, 0));

        FrameLayout chipLayout = new FrameLayout(context);
        chipLayout.setBackground(Theme.createRoundRectDrawable(dp(28), dp(28), Theme.getColor(Theme.key_groupcreate_spanBackground, resourcesProvider)));

        BackupImageView chipAvatar = new BackupImageView(context);
        chipAvatar.setRoundRadius(dp(28));
        AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(owner);
        chipAvatar.setForUserOrChat(owner, avatarDrawable);
        chipLayout.addView(chipAvatar, LayoutHelper.createFrame(28, 28, Gravity.LEFT | Gravity.TOP));

        TextView chipText = new TextView(context);
        chipText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        chipText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chipText.setSingleLine();
        chipText.setText(Emoji.replaceEmoji(ownerName, chipText.getPaint().getFontMetricsInt(), false));
        chipLayout.addView(chipText, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.LEFT, 37, 0, 10, 0));

        layout.addView(chipLayout, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 28, Gravity.CENTER_HORIZONTAL, 42, 10, 42, 18));

        TextView descriptionView = new TextView(context);
        descriptionView.setGravity(Gravity.CENTER);
        descriptionView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        // The Cat: a programmatically built TextView does not inherit the theme's
        // fontFamily (that only reaches XML-inflated views), so it has to be set.
        descriptionView.setTypeface(AndroidUtilities.vazir());
        descriptionView.setText(messageSpanned);
        if (waitingForStars) {
            // Catalog still loading: re-render the price line once prices arrive.
            final int account = UserConfig.selectedAccount;
            final int event = NotificationCenter.starOptionsLoaded;
            final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
            holder[0] = (id, account1, args) -> {
                NotificationCenter.getInstance(account).removeObserver(holder[0], event);
                descriptionView.setText(decorateIcons(buildMessage(type, info, fiatPart)));
            };
            NotificationCenter.getInstance(account).addObserver(holder[0], event);
            sheet.setOnDismissListener((Runnable) () -> NotificationCenter.getInstance(account).removeObserver(holder[0], event));
        }
        layout.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 32, 0, 32, 19));

        ButtonWithCounterView button = new ButtonWithCounterView(context, resourcesProvider).setRound();
        button.setText(getString(R.string.FragmentUsernameOpen), false);
        button.setOnClickListener(v -> {
            Browser.openUrl(context, info.url);
        });
        layout.addView(button, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 6, 0, 6, 0));

        if (copy != null) {
            ButtonWithCounterView button2 = new ButtonWithCounterView(context, resourcesProvider).setRound().setNeutral();
            button2.setText(getString(type == TYPE_USERNAME ? R.string.FragmentUsernameCopy : R.string.FragmentPhoneCopy), false);
            button2.setOnClickListener(v -> {
                copy.run();
                sheet.dismiss();
            });
            layout.addView(button2, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 6, 6, 6, 0));
        }

        sheet.setCustomView(layout);
        sheet.show();
    }

    /**
     * The price sentence for a collectible, with the star count and the fiat part.
     * Kept separate so it can be rebuilt when the star catalog arrives and the
     * toman equivalent becomes available.
     */
    private static String buildMessage(int type, TL_fragment.TL_collectibleInfo info, String fiatPart) {
        final String crypto_money = formatStars(info.crypto_amount, info.crypto_currency);
        final int textRes = type == TYPE_USERNAME ? R.string.FragmentUsernameMessage : R.string.FragmentPhoneMessage;
        return LocaleController.formatString(textRes, LocaleController.formatShortDateTime(info.purchase_date), crypto_money, fiatPart);
    }

    /**
     * The Cat: render the currency marker as its icon instead of the raw code —
     * the star glyph for XTR, the gram for TON. The codes are replacement tokens
     * inside the message string, swapped here for drawable spans.
     */
    private static CharSequence decorateIcons(String message) {
        final SpannableString tonIcon = new SpannableString("TON");
        ColoredImageSpan span = new ColoredImageSpan(R.drawable.mini_gram_16);
        span.setWidth(dp(13));
        tonIcon.setSpan(span, 0, tonIcon.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final SpannableString starIcon = new SpannableString("XTR");
        ColoredImageSpan starSpan = new ColoredImageSpan(R.drawable.star_small_inner);
        // Keep the star's own golden gradient; the span would otherwise flatten
        // it to the dialog text colour (same choice StarsIntroActivity makes).
        starSpan.recolorDrawable = false;
        starSpan.setWidth(dp(14));
        starIcon.setSpan(starSpan, 0, starIcon.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return AndroidUtilities.replaceCharSequence("XTR",
                AndroidUtilities.replaceCharSequence("TON", AndroidUtilities.replaceTags(message), tonIcon), starIcon);
    }

    /**
     * The currency part of a collectible's price. Star amounts render as the star
     * icon plus a count and, when the star catalog is known, the toman equivalent
     * in parentheses — "1,000 stars ≈ 85,000 Toman" in the Cat's money system.
     */
    private static String formatStars(long amount, String currency) {
        if (currency == null || currency.isEmpty()) {
            // No currency means no price to show at all; formatCurrency would
            // return the bare amount ("0") and it would read as a real price.
            return "";
        }
        if (!"XTR".equalsIgnoreCase(currency)) {
            return BillingController.getInstance().formatCurrency(amount, currency);
        }
        String stars = "XTR" + LocaleController.formatNumber(amount, ',');
        long toman = tomanForStars(amount);
        if (toman > 0) {
            stars += " (" + TheCatMoney.formatToman(toman) + ")";
        }
        return stars;
    }

    /**
     * Toman value of {@code amount} stars, priced off the cheapest catalog tier.
     * Returns 0 when the catalog has not loaded yet, so callers can omit the
     * equivalent instead of printing a wrong number.
     */
    private static long tomanForStars(long amount) {
        try {
            ArrayList<TL_stars.TL_starsTopupOption> options =
                    StarsController.getInstance(UserConfig.selectedAccount).getOptionsCached();
            if (options == null || options.isEmpty() || amount <= 0) {
                return 0;
            }
            TL_stars.TL_starsTopupOption cheapest = null;
            for (TL_stars.TL_starsTopupOption option : options) {
                if (option.stars <= 0 || option.currency == null) {
                    continue;
                }
                if (cheapest == null || option.stars < cheapest.stars) {
                    cheapest = option;
                }
            }
            if (cheapest == null) {
                return 0;
            }
            int exp = BillingController.getInstance().getCurrencyExp(cheapest.currency);
            long tierToman = TheCatMoney.toToman(cheapest.amount / Math.pow(10, exp), cheapest.currency);
            if (tierToman <= 0) {
                return 0;
            }
            return Math.round((double) tierToman / cheapest.stars * amount);
        } catch (Throwable e) {
            // Catalog not ready, or the account has no star prices: omit.
            return 0;
        }
    }

}
