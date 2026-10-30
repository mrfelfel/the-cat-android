package org.telegram.messenger.utils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;

/**
 * The Cat's single money-display system: every fiat amount renders as whole
 * tomans ("85,000 Toman" / "۸۵٬۰۰۰ تومان") — never dollars (user directive).
 * Star counts (XTR) and TON balances are not fiat and keep their own format.
 *
 * The toman/USD rate comes from app config ("usd_toman_rate", pushed by the
 * server) with a built-in fallback; other currencies convert through small
 * approximate USD tables. Display only — payment inputs stay in the invoice's
 * own currency.
 */
public class TheCatMoney {

    private static final double FALLBACK_USD_TOMAN_RATE = 85000;

    // Approximate USD value of 1 unit, for currencies the server may send.
    // USD conversions use the remote rate; unknown codes are treated as USD.
    private static final HashMap<String, Double> USD_PER_UNIT = new HashMap<>();

    static {
        USD_PER_UNIT.put("USD", 1.0);
        USD_PER_UNIT.put("EUR", 1.08);
        USD_PER_UNIT.put("GBP", 1.27);
        USD_PER_UNIT.put("CHF", 1.12);
        USD_PER_UNIT.put("CAD", 0.73);
        USD_PER_UNIT.put("AUD", 0.66);
        USD_PER_UNIT.put("JPY", 0.0067);
        USD_PER_UNIT.put("CNY", 0.14);
        USD_PER_UNIT.put("TRY", 0.028);
        USD_PER_UNIT.put("AED", 0.27);
        USD_PER_UNIT.put("QAR", 0.27);
        USD_PER_UNIT.put("SAR", 0.27);
        USD_PER_UNIT.put("OMR", 2.6);
        USD_PER_UNIT.put("KWD", 3.25);
        USD_PER_UNIT.put("BHD", 2.65);
        USD_PER_UNIT.put("RUB", 0.011);
        USD_PER_UNIT.put("INR", 0.012);
        USD_PER_UNIT.put("PKR", 0.0036);
        USD_PER_UNIT.put("AFN", 0.014);
    }

    public static double usdTomanRate() {
        try {
            return MessagesController.getInstance(UserConfig.selectedAccount).config.usdTomanRate.get();
        } catch (Throwable e) {
            return FALLBACK_USD_TOMAN_RATE;
        }
    }

    /** Whole tomans for {@code units} of {@code currency} (IRT already tomans, IRR rials). */
    public static long toToman(double units, String currency) {
        if (currency == null) {
            return Math.round(units);
        }
        switch (currency.toUpperCase()) {
            case "IRT":
                return Math.round(units);
            case "IRR":
                return Math.round(units / 10.0);
        }
        Double usdPerUnit = USD_PER_UNIT.get(currency.toUpperCase());
        double usd = units * (usdPerUnit != null ? usdPerUnit : 1.0);
        return Math.round(usd * usdTomanRate());
    }

    public static String formatToman(long tomans) {
        return (tomans < 0 ? "-" : "")
                + LocaleController.formatNumber(Math.abs(tomans), ',')
                + " " + LocaleController.getString(R.string.Toman);
    }

    /** Toman-only display for a fiat amount in minor units (10^exp of one unit). */
    public static String formatFiat(long amount, String currency, int exp) {
        boolean negative = amount < 0;
        double units = Math.abs(amount) / Math.pow(10, exp);
        long tomans = toToman(units, currency);
        String result = formatToman(tomans);
        return negative ? "-" + result : result;
    }
}
