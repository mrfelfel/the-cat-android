package org.telegram.messenger.utils;

/**
 * The Cat: phone input normalisation.
 *
 * Persian and Arabic keyboards produce U+06F0..U+06F9 and U+0660..U+0669 digits,
 * and users type the country code in many shapes ("0910…", "+98 910…",
 * "0098…", "98…", "98 0910…"). The login field previously dropped every
 * non-ASCII digit, so a Persian-keyboard number vanished entirely; and the
 * leading trunk zero went to the server untouched.
 *
 * Kept free of Android imports so it can be unit tested on the JVM.
 */
public class CatPhone {

    /** Iran's calling code; the fallback country and the trunk-zero rule below. */
    public static final String IRAN_CODE = "98";
    /** Iranian mobile numbers are 10 digits after the country code. */
    private static final int IRAN_NATIONAL_LENGTH = 10;

    /** Rewrite Persian/Arabic-Indic digits to ASCII, leaving everything else intact. */
    public static String normalizeDigits(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            char mapped = c;
            if (c >= '۰' && c <= '۹') {          // U+06F0..U+06F9
                mapped = (char) ('0' + (c - '۰'));
            } else if (c >= '٠' && c <= '٩') {   // U+0660..U+0669
                mapped = (char) ('0' + (c - '٠'));
            }
            if (out == null) {
                if (mapped == c) {
                    continue;
                }
                out = new StringBuilder(str.length());
                out.append(str, 0, i);
            }
            out.append(mapped);
        }
        return out == null ? str : out.toString();
    }

    /**
     * The national part of a typed number, accepting the shapes users actually
     * type for Iran: "09101234567", "+989101234567", "00989101234567",
     * "989101234567", "98 0910 123 4567", "0910 123 4567", "910 123 4567".
     *
     * The country code is only stripped when exactly a full national number is
     * left over, so a genuine number that happens to start with 98 survives.
     */
    public static String normalizeNational(String code, String number) {
        String digits = digitsOnly(normalizeDigits(number));
        if (digits.isEmpty()) {
            return "";
        }
        final String countryCode = digitsOnly(code == null ? "" : code);
        if (countryCode.isEmpty()) {
            return digits;
        }

        // "0098…" and "+98…" typed into the number field.
        if (digits.startsWith("00" + countryCode)
                && leavesNational(digits.substring(countryCode.length() + 2))) {
            digits = digits.substring(countryCode.length() + 2);
        } else if (digits.startsWith(countryCode)
                && leavesNational(digits.substring(countryCode.length()))) {
            digits = digits.substring(countryCode.length());
        }

        // Trunk zero: for Iran the mobile plan is 9xx and land lines start 2x,
        // so a single leading 0 is the domestic prefix, never part of the number.
        if (IRAN_CODE.equals(countryCode) && digits.length() == IRAN_NATIONAL_LENGTH + 1
                && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    /**
     * A national number that is a full mobile/land-line length, optionally with
     * the trunk zero still on it — i.e. what is left after the country code.
     */
    private static boolean leavesNational(String rest) {
        return rest.length() == IRAN_NATIONAL_LENGTH
                || (rest.length() == IRAN_NATIONAL_LENGTH + 1 && rest.startsWith("0"));
    }

    public static String digitsOnly(String str) {
        if (str == null) {
            return "";
        }
        str = normalizeDigits(str);
        StringBuilder out = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            final char c = str.charAt(i);
            if (c >= '0' && c <= '9') {
                out.append(c);
            }
        }
        return out.toString();
    }
}
