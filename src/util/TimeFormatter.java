package util;

import exceptions.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Formatting and parsing of dates, times and durations. */
public final class TimeFormatter {
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm";
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern UNITS = Pattern.compile(
            "^(?:(\\d+)\\s*h(?:ours?|rs?)?)?\\s*(?:(\\d+)\\s*m(?:in(?:utes?|s)?)?)?\\s*(?:(\\d+)\\s*s(?:ec(?:onds?|s)?)?)?$");

    private TimeFormatter() {
    }

    /** "HH:MM:SS"; negative durations show as 00:00:00, null as "No limit". */
    public static String hms(Duration d) {
        if (d == null) {
            return "No limit";
        }
        long s = Math.max(0, d.getSeconds());
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** Human readable, e.g. "1 hour 30 minutes". */
    public static String human(Duration d) {
        if (d == null) {
            return "no limit";
        }
        long s = Math.max(0, d.getSeconds());
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h).append(h == 1 ? " hour" : " hours");
        }
        if (m > 0) {
            sb.append(sb.length() > 0 ? " " : "").append(m).append(m == 1 ? " minute" : " minutes");
        }
        if (sec > 0 || sb.length() == 0) {
            sb.append(sb.length() > 0 ? " " : "").append(sec).append(sec == 1 ? " second" : " seconds");
        }
        return sb.toString();
    }

    public static String stamp(Instant instant, ZoneId zone) {
        return instant == null ? "-" : STAMP.format(instant.atZone(zone));
    }

    public static String dateTime(LocalDateTime t) {
        return t == null ? "-" : DATE_TIME.format(t);
    }

    /** Parses "yyyy-MM-dd HH:mm". */
    public static LocalDateTime parseDateTime(String text) throws ValidationException {
        try {
            return LocalDateTime.parse(text.trim(), DATE_TIME);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ValidationException("'" + text + "' is not a valid date/time. Use the format " + DATE_TIME_PATTERN + " (e.g. 2026-11-20 13:30).");
        }
    }

    /**
     * Parses a duration. Accepted forms: "90" (minutes), "1:30" (h:mm), "01:30:15" (hh:mm:ss),
     * "1h30m", "45m", "2h", "1h 30m 15s".
     */
    public static Duration parseDuration(String text) throws ValidationException {
        if (text == null || text.isBlank()) {
            throw new ValidationException("Duration is required.");
        }
        String t = text.trim().toLowerCase(Locale.ROOT);
        Duration result;
        try {
            if (t.matches("\\d+")) {
                result = Duration.ofMinutes(Long.parseLong(t));
            } else if (t.matches("\\d+:\\d{1,2}(:\\d{1,2})?")) {
                String[] p = t.split(":");
                long h = Long.parseLong(p[0]);
                long m = Long.parseLong(p[1]);
                long s = p.length == 3 ? Long.parseLong(p[2]) : 0;
                if (m > 59 || s > 59) {
                    throw new ValidationException("Minutes and seconds must be between 0 and 59.");
                }
                result = Duration.ofHours(h).plusMinutes(m).plusSeconds(s);
            } else {
                Matcher m = UNITS.matcher(t);
                if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
                    throw new ValidationException("'" + text + "' is not a valid duration. Examples: 30, 1:30, 01:30:00, 1h30m, 45m.");
                }
                result = Duration.ZERO;
                if (m.group(1) != null) {
                    result = result.plusHours(Long.parseLong(m.group(1)));
                }
                if (m.group(2) != null) {
                    result = result.plusMinutes(Long.parseLong(m.group(2)));
                }
                if (m.group(3) != null) {
                    result = result.plusSeconds(Long.parseLong(m.group(3)));
                }
            }
        } catch (NumberFormatException | ArithmeticException e) {
            throw new ValidationException("'" + text + "' is not a valid duration.");
        }
        if (result.isZero()) {
            throw new ValidationException("Duration must be greater than zero.");
        }
        return result;
    }
}
