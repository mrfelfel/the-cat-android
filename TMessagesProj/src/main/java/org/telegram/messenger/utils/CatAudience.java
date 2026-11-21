package org.telegram.messenger.utils;

/**
 * Who this install is for: an Iranian user or someone outside Iran.
 *
 * <p>Drives two things that must agree with each other: which support text and
 * default language the app shows, and whether payments are offered at all. A
 * foreign install must see neither the Iranian support phone number nor any
 * payment entry point, so both read this one answer rather than each deciding
 * on its own.
 *
 * <p>Only the phone country code is conclusive. Every other signal can be
 * wrong in a way that matters: a Persian speaker abroad has a {@code fa} UI
 * locale, an Iranian on a VPN loses their network country, and a traveller
 * carries a foreign SIM. So the weaker signals only produce a GUESS, which is
 * never persisted as final and is superseded the moment {@link #fromPhoneCode}
 * has something to say. See {@link CatAudienceStore} for how that is held.
 */
public final class CatAudience {

    /** Confidently Iranian: SIM, network MCC, or the entered phone code. */
    public static final int IRAN = 1;
    /** Confidently not Iranian. */
    public static final int FOREIGN = 2;
    /**
     * Nothing conclusive yet. Callers must treat this as FOREIGN for payments
     * (never offer a payment path we cannot justify) and keep the shipped
     * default for language.
     */
    public static final int UNKNOWN = 0;

    /** Country calling code for Iran. */
    public static final String IRAN_PHONE_CODE = "98";
    /** Iran's MCC; the first three digits of a SIM/network operator code. */
    public static final String IRAN_MCC = "432";
    /** Iranian national numbers are 10 digits after the country code. */
    private static final int IRAN_NATIONAL_LENGTH = 10;
    /**
     * Shortest international number worth treating as "not Iranian". Below
     * this the string is far more likely a half-typed code than a real number.
     */
    private static final int FOREIGN_MIN_DIGITS = 8;

    private CatAudience() {
    }

    /**
     * The conclusive signal: the country code of the number the user signed in
     * with. {@code phone} may be in any shape the login field accepts ("+98…",
     * "0098…", "0912…") because {@link CatPhone} normalises it first.
     *
     * <p>Returns UNKNOWN — not FOREIGN — when the number cannot be read, so a
     * parsing gap can never silently disable payments for an Iranian user.
     */
    public static int fromPhoneCode(String phone) {
        if (phone == null) {
            return UNKNOWN;
        }
        String digits = CatPhone.digitsOnly(phone);
        if (digits.isEmpty()) {
            return UNKNOWN;
        }
        if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }

        // National form with the trunk zero ("0912…"). Only a number that
        // reduces to a full Iranian national number counts; anything else is
        // left undecided rather than guessed.
        if (digits.startsWith("0")) {
            String national = CatPhone.normalizeNational(IRAN_PHONE_CODE, digits);
            return national.length() == IRAN_NATIONAL_LENGTH ? IRAN : UNKNOWN;
        }

        // Starts with 98: Iranian only when what follows is a full national
        // number. "98" on its own, a half-typed "+98912", or a foreign number
        // that merely begins with those digits must not decide anything — an
        // accidental FOREIGN here would hide payments permanently.
        if (digits.startsWith(IRAN_PHONE_CODE)) {
            String rest = digits.substring(IRAN_PHONE_CODE.length());
            if (rest.startsWith("0")) {
                rest = rest.substring(1);
            }
            return rest.length() == IRAN_NATIONAL_LENGTH ? IRAN : UNKNOWN;
        }

        // A plausible international number with another country's code. Kept
        // deliberately conservative: too short to judge stays UNDECIDED.
        return digits.length() >= FOREIGN_MIN_DIGITS ? FOREIGN : UNKNOWN;
    }

    /**
     * The SIM's country, from {@code TelephonyManager.getSimCountryIso()}.
     * Strong but not conclusive: a dual-SIM phone can report either card, and
     * an imported handset ships whatever card its seller used.
     */
    public static int fromSimCountry(String iso) {
        if (iso == null) {
            return UNKNOWN;
        }
        String code = iso.trim().toUpperCase(java.util.Locale.US);
        if (code.isEmpty()) {
            return UNKNOWN;
        }
        return "IR".equals(code) ? IRAN : FOREIGN;
    }

    /**
     * The MCC embedded in a SIM ({@code getSimOperator}) or network
     * ({@code getNetworkOperator}) operator code. The network one survives a
     * foreign SIM (roaming onto an Iranian network still reports 432), which is
     * why both are consulted.
     */
    public static int fromOperatorCode(String operator) {
        if (operator == null || operator.length() < 3) {
            return UNKNOWN;
        }
        return IRAN_MCC.equals(operator.substring(0, 3)) ? IRAN : FOREIGN;
    }

    /**
     * Region of the device or SIM. Weakest signal of all — the region setting
     * is a locale preference, not a location — so it is only ever consulted to
     * produce a guess, never to override one.
     */
    public static int fromRegion(String region) {
        if (region == null) {
            return UNKNOWN;
        }
        String code = region.trim().toUpperCase(java.util.Locale.US);
        if (code.isEmpty()) {
            return UNKNOWN;
        }
        return "IR".equals(code) ? IRAN : FOREIGN;
    }

    /**
     * Combine the non-conclusive signals into a guess for the period before a
     * phone number exists (first launch, the login screen).
     *
     * <p>Iranian wins a tie on purpose: guessing Iranian shows the Persian UI
     * and the local support number, which is recoverable the moment the phone
     * code arrives. Guessing foreign hides payments, which an Iranian user
     * cannot recover from without signing in again. Irreversible errors should
     * fall on the reversible side.
     */
    public static int guess(int simCountry, int networkOperator, int region) {
        if (simCountry == IRAN || networkOperator == IRAN || region == IRAN) {
            return IRAN;
        }
        if (simCountry == FOREIGN || networkOperator == FOREIGN || region == FOREIGN) {
            return FOREIGN;
        }
        return UNKNOWN;
    }

    /** True only when payments may be offered. UNKNOWN is not Iranian. */
    public static boolean canPay(int audience) {
        return audience == IRAN;
    }
}
