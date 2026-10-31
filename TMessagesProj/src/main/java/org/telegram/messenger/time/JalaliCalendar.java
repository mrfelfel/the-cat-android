// The Cat: Solar Hijri (Jalali) calendar conversion, display-only.
// Port of the jalaali-js algorithm (MIT, Behrang Noruzi Niya) — same math
// used by moment-jalaali and Telegram's official BidiCalendar. Valid for
// years 1..3178. Server protocol and storage remain Gregorian; this is
// only used to render and pick dates when the UI language is Persian.
package org.telegram.messenger.time;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

public class JalaliCalendar {

    private static volatile boolean enabled;

    private static final String[] MONTH_NAMES = {
            "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
            "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند"
    };

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static boolean isJalaliLanguage(String lang) {
        return lang != null && (lang.equals("fa") || lang.startsWith("fa_"));
    }

    /** Calendar whose YEAR/MONTH/DAY_OF_MONTH/DAY_OF_YEAR read as Jalali while
     *  time-of-day and zone fields keep Gregorian values. Returns a plain
     *  GregorianCalendar when Jalali display is off. */
    public static Calendar getInstance() {
        if (!enabled) {
            return Calendar.getInstance();
        }
        return new JalaliCalendarView(System.currentTimeMillis(), TimeZone.getDefault(), Locale.getDefault());
    }

    public static String getMonthName(int month1Based) {
        if (month1Based < 1 || month1Based > 12) {
            return "";
        }
        return MONTH_NAMES[month1Based - 1];
    }

    public static boolean isLeap(int jy) {
        return jalCal(jy)[0] == 0;
    }

    /** Days in the Jalali month (1..12). Months 1-6: 31, 7-11: 30, 12: 29/30. */
    public static int monthLength(int jy, int jm) {
        if (jm <= 6) {
            return 31;
        }
        if (jm <= 11) {
            return 30;
        }
        return isLeap(jy) ? 30 : 29;
    }

    /** Gregorian (year, month 1-12, day) -> Jalali, using the default timezone's wall-clock date. */
    public static int[] toJalali(long epochMillis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(epochMillis);
        return toJalali(calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    public static int[] toJalali(int gy, int gm, int gd) {
        return d2j(g2d(gy, gm, gd));
    }

    /** Jalali (year, month 1-12, day) -> Gregorian (year, month 1-12, day). */
    public static int[] toGregorian(int jy, int jm, int jd) {
        int[] g = d2g(j2d(jy, jm, jd));
        return new int[]{g[0], g[1], g[2]};
    }

    // Yearless birthdays: TL stores Gregorian day+month only. Map through the
    // Gregorian span of Jalali year 1379 so display and save are exact
    // inverses. Ambiguous only near Nowruz and for Esfand 30 (needs a leap
    // year) — inherent to yearless cross-calendar dates.

    /** Gregorian month/day (no year) -> Jalali {month, day}. */
    public static int[] toJalaliYearless(int gm, int gd) {
        int[] nowruz = toGregorian(1379, 1, 1); // Jalali 1379 spans [nowruz 2000, nowruz 2001)
        final int gy;
        if (gm == 2 && gd == 29) {
            gy = 2000; // leap year; outside the 1379 span, so reopen may shift by a day
        } else if (gm >= 4) {
            gy = 2000;
        } else if (gm <= 2) {
            gy = 2001;
        } else {
            gy = gd >= nowruz[2] ? 2000 : 2001;
        }
        int[] j = toJalali(gy, gm, gd);
        return new int[]{j[1], j[2]};
    }

    /** Jalali month/day (no year) -> Gregorian {month, day}. */
    public static int[] toGregorianYearless(int jm, int jd) {
        int jy = 1379;
        if (jm == 12 && jd > monthLength(jy, 12)) {
            jy = 1403; // Esfand 30 only exists in leap years
        }
        int[] g = toGregorian(jy, jm, jd);
        return new int[]{g[1], g[2]};
    }

    public static boolean isSameDay(long millisA, long millisB) {
        if (!enabled) {
            Calendar a = Calendar.getInstance();
            a.setTimeInMillis(millisA);
            Calendar b = Calendar.getInstance();
            b.setTimeInMillis(millisB);
            return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                    && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
        }
        int[] a = toJalali(millisA);
        int[] b = toJalali(millisB);
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    public static int getYear(long epochMillis) {
        if (!enabled) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(epochMillis);
            return calendar.get(Calendar.YEAR);
        }
        return toJalali(epochMillis)[0];
    }

    public static int getDayOfYear(long epochMillis) {
        if (!enabled) {
            Calendar calendar = Calendar.getInstance();
            calendar.setTimeInMillis(epochMillis);
            return calendar.get(Calendar.DAY_OF_YEAR);
        }
        int[] jd = toJalali(epochMillis);
        return dayOfYear(jd[0], jd[1], jd[2]);
    }

    private static int dayOfYear(int jy, int jm, int jd) {
        int day = jd;
        for (int m = 1; m < jm; m++) {
            day += monthLength(jy, m);
        }
        return day;
    }

    /** Jalali view over any Calendar's instant, preserving its timezone. */
    static Calendar wrap(Calendar calendar) {
        return new JalaliCalendarView(calendar.getTimeInMillis(), calendar.getTimeZone(), Locale.getDefault());
    }

    /** Rewrite ASCII digits in buf[from..] to Persian digits, in place. */
    static void persianizeDigits(StringBuffer buf, int from) {
        for (int i = from; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c >= '0' && c <= '9') {
                buf.setCharAt(i, (char) (c - '0' + '۰'));
            }
        }
    }

    /** ASCII digits -> Persian digits (for NumberPicker formatters etc.). */
    public static String persianDigits(String s) {
        if (s == null) {
            return null;
        }
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                if (out == null) {
                    out = new StringBuilder(s.length());
                    out.append(s, 0, i);
                }
                out.append((char) (c - '0' + '۰'));
            } else if (out != null) {
                out.append(c);
            }
        }
        return out == null ? s : out.toString();
    }

    // --- conversion core (jalaali-js, MIT) ---
    // div/mod must truncate toward zero, matching JS ~~() — not Math.floor.

    private static final int[] BREAKS = {
            -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635,
            2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178
    };

    private static int div(int a, int b) {
        return a / b;
    }

    private static int mod(int a, int b) {
        return a - (a / b) * b;
    }

    private static long div(long a, long b) {
        return a / b;
    }

    private static long mod(long a, long b) {
        return a - (a / b) * b;
    }

    private static int[] jalCal(int jy) {
        int bl = BREAKS.length;
        int gy = jy + 621;
        int leapJ = -14;
        int jp = BREAKS[0];
        int jm = 0;
        int jump = 0;
        int leapG;
        int march;
        int n;
        int i;

        if (jy < jp || jy >= BREAKS[bl - 1]) {
            throw new IllegalArgumentException("Invalid Jalaali year " + jy);
        }

        for (i = 1; i < bl; i += 1) {
            jm = BREAKS[i];
            jump = jm - jp;
            if (jy < jm) {
                break;
            }
            leapJ = leapJ + div(jump, 33) * 8 + div(mod(jump, 33), 4);
            jp = jm;
        }
        n = jy - jp;

        leapJ = leapJ + div(n, 33) * 8 + div(mod(n, 33) + 3, 4);
        if (mod(jump, 33) == 4 && jump - n == 4) {
            leapJ += 1;
        }

        leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150;

        march = 20 + leapJ - leapG;

        if (jump - n < 6) {
            n = n - jump + div(jump + 4, 33) * 33;
        }
        int leap = mod(mod(n + 1, 33) - 1, 4);
        if (leap == -1) {
            leap = 4;
        }

        return new int[]{leap, gy, march};
    }

    private static long g2d(int gy, int gm, int gd) {
        long d = div((long) (gy + div(gm - 8, 6) + 100100) * 1461, 4)
                + div(153L * mod(gm + 9, 12) + 2, 5)
                + gd - 34840408L;
        d = d - div((long) div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4) + 752;
        return d;
    }

    private static int[] d2g(long jdn) {
        long j = 4 * jdn + 139361631L;
        j = j + div(div(4 * jdn + 183187720L, 146097) * 3, 4) * 4 - 3908;
        int i = (int) (div(mod(j, 1461), 4) * 5 + 308);
        int gd = (int) (div(mod(i, 153), 5) + 1);
        int gm = (int) (mod(div(i, 153), 12) + 1);
        int gy = (int) (div(j, 1461) - 100100 + div(8 - gm, 6));
        return new int[]{gy, gm, gd};
    }

    private static long j2d(int jy, int jm, int jd) {
        int[] r = jalCal(jy);
        return g2d(r[1], 3, r[2]) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1;
    }

    private static int[] d2j(long jdn) {
        int gy = d2g(jdn)[0];
        int jy = gy - 621;
        int[] r = jalCal(jy);
        long jdn1f = g2d(r[1], 3, r[2]);
        int jd;
        int jm;
        long k = jdn - jdn1f;

        if (k >= 0) {
            if (k <= 185) {
                jm = 1 + (int) div(k, 31);
                jd = (int) mod(k, 31) + 1;
                return new int[]{jy, jm, jd};
            }
            k -= 186;
        } else {
            jy -= 1;
            k += 179;
            if (r[0] == 1) {
                k += 1;
            }
        }
        jm = 7 + (int) div(k, 30);
        jd = (int) mod(k, 30) + 1;
        return new int[]{jy, jm, jd};
    }

    /** GregorianCalendar view that answers date fields in Jalali. */
    static final class JalaliCalendarView extends GregorianCalendar {

        private int[] jalali;
        private long computedFor = Long.MIN_VALUE;

        JalaliCalendarView(long timeMillis, TimeZone zone, Locale locale) {
            super(zone, locale);
            setTimeInMillis(timeMillis);
        }

        @Override
        public void setTimeInMillis(long millis) {
            super.setTimeInMillis(millis);
            jalali = null;
            computedFor = Long.MIN_VALUE;
        }

        private void ensureJalali() {
            long millis = getTimeInMillis();
            if (jalali == null || computedFor != millis) {
                int gy = super.get(GregorianCalendar.YEAR);
                int gm = super.get(GregorianCalendar.MONTH) + 1;
                int gd = super.get(GregorianCalendar.DAY_OF_MONTH);
                jalali = toJalali(gy, gm, gd);
                computedFor = millis;
            }
        }

        @Override
        public int get(int field) {
            switch (field) {
                case YEAR:
                    ensureJalali();
                    return jalali[0];
                case MONTH:
                    ensureJalali();
                    return jalali[1] - 1;
                case DAY_OF_MONTH:
                    ensureJalali();
                    return jalali[2];
                case DAY_OF_YEAR:
                    ensureJalali();
                    return dayOfYear(jalali[0], jalali[1], jalali[2]);
                default:
                    return super.get(field);
            }
        }
    }

}
