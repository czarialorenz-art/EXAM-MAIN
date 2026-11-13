package util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** Small text helpers used for display. */
public final class TextUtil {
    private TextUtil() {
    }

    /** Formats a number with at most two decimals and no trailing zeros (2.0 -> "2", 2.5 -> "2.5"). */
    public static String num(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return String.valueOf(value);
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    public static String pct(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value);
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, Math.max(0, max - 3)) + "...";
    }

    public static boolean containsIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT).trim());
    }

    public static String yesNo(boolean b) {
        return b ? "Yes" : "No";
    }
}
