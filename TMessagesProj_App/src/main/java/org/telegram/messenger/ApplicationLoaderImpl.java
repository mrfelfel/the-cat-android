package org.telegram.messenger;

import org.telegram.messenger.regular.BuildConfig;

public class ApplicationLoaderImpl extends ApplicationLoader {
    @Override
    protected String onGetApplicationId() {
        return BuildConfig.APPLICATION_ID;
    }

    /**
     * The Play distribution bills through The Cat's own Toman checkout, exactly
     * like the direct build.
     *
     * <p>Google Play's billing system cannot be used here at all: Iran is not a
     * supported merchant location, so there is no way to create the products or
     * receive the payouts. Rather than ship an app whose purchase buttons do
     * nothing, this build takes the direct path — which is only reached for a
     * confirmed Iranian install, since {@code BuildVars.arePaymentsAvailable()}
     * gates every entry point and a store purchase would be impossible anyway.
     *
     * <p>Note this is NOT {@code isStandalone()}. That flag would additionally
     * enable the self-update APK installer and the SMS-jobs menu, neither of
     * which belongs in a Play-distributed build.
     *
     * <p>Must stay a virtual override: the library's own R8 pass cannot see this
     * class and would fold the base value (see proguard-rules.pro).
     */
    @Override
    public boolean usesDirectPayments() {
        return true;
    }
}
