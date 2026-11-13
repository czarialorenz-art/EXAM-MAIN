package ui;

import exceptions.ValidationException;
import util.TimeFormatter;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * Validated console input. Every numeric/date/yes-no prompt repeats until the input is valid,
 * so bad typing can never crash the application.
 */
public class InputReader {
    private final BufferedReader reader;
    private final PrintStream out;
    private final Console console;

    public InputReader(InputStream in, PrintStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in));
        this.out = out;
        this.console = in == System.in ? System.console() : null;
    }

    public PrintStream out() {
        return out;
    }

    private String rawLine() {
        try {
            String s = reader.readLine();
            if (s == null) {
                throw new InputClosedException();
            }
            return s;
        } catch (IOException e) {
            throw new InputClosedException();
        }
    }

    /** Prints the prompt and returns the trimmed line. */
    public String line(String prompt) {
        out.print(prompt);
        out.flush();
        return rawLine().trim();
    }

    public String required(String prompt) {
        while (true) {
            String s = line(prompt);
            if (!s.isEmpty()) {
                return s;
            }
            out.println("  This field is required.");
        }
    }

    /** Shows the current value; pressing Enter keeps it. */
    public String optional(String label, String current) {
        String shown = current == null || current.isEmpty() ? "" : " [" + current + "]";
        String s = line(label + shown + ": ");
        return s.isEmpty() ? current : s;
    }

    public int integer(String prompt, int min, int max) {
        while (true) {
            String s = line(prompt);
            try {
                int v = Integer.parseInt(s);
                if (v >= min && v <= max) {
                    return v;
                }
                out.println("  Please enter a number from " + min + " to " + max + ".");
            } catch (NumberFormatException e) {
                out.println("  '" + s + "' is not a valid whole number.");
            }
        }
    }

    public int integerOrKeep(String label, int current, int min, int max) {
        while (true) {
            String s = line(label + " [" + current + "]: ");
            if (s.isEmpty()) {
                return current;
            }
            try {
                int v = Integer.parseInt(s);
                if (v >= min && v <= max) {
                    return v;
                }
                out.println("  Please enter a number from " + min + " to " + max + ".");
            } catch (NumberFormatException e) {
                out.println("  '" + s + "' is not a valid whole number.");
            }
        }
    }

    public double decimal(String prompt, double min, double max) {
        while (true) {
            String s = line(prompt);
            try {
                double v = Double.parseDouble(s);
                if (v >= min && v <= max && Double.isFinite(v)) {
                    return v;
                }
                out.println("  Please enter a number from " + util.TextUtil.num(min) + " to " + util.TextUtil.num(max) + ".");
            } catch (NumberFormatException e) {
                out.println("  '" + s + "' is not a valid number.");
            }
        }
    }

    public double decimalOrKeep(String label, double current, double min, double max) {
        while (true) {
            String s = line(label + " [" + util.TextUtil.num(current) + "]: ");
            if (s.isEmpty()) {
                return current;
            }
            try {
                double v = Double.parseDouble(s);
                if (v >= min && v <= max && Double.isFinite(v)) {
                    return v;
                }
                out.println("  Please enter a number from " + util.TextUtil.num(min) + " to " + util.TextUtil.num(max) + ".");
            } catch (NumberFormatException e) {
                out.println("  '" + s + "' is not a valid number.");
            }
        }
    }

    public boolean yesNo(String prompt) {
        while (true) {
            String s = line(prompt + " (y/n): ").toLowerCase(Locale.ROOT);
            if (s.equals("y") || s.equals("yes")) {
                return true;
            }
            if (s.equals("n") || s.equals("no")) {
                return false;
            }
            out.println("  Please answer y or n.");
        }
    }

    public boolean yesNoOrKeep(String prompt, boolean current) {
        while (true) {
            String s = line(prompt + " (y/n) [" + (current ? "y" : "n") + "]: ").toLowerCase(Locale.ROOT);
            if (s.isEmpty()) {
                return current;
            }
            if (s.equals("y") || s.equals("yes")) {
                return true;
            }
            if (s.equals("n") || s.equals("no")) {
                return false;
            }
            out.println("  Please answer y or n.");
        }
    }

    /** Hidden input when a real terminal is available; plain input otherwise (IDE consoles, scripts). */
    public String password(String prompt) {
        if (console != null) {
            char[] pw = console.readPassword(prompt);
            if (pw == null) {
                throw new InputClosedException();
            }
            return new String(pw);
        }
        return line(prompt);
    }

    /** Date/time in yyyy-MM-dd HH:mm. Enter keeps the current value, '-' clears it. */
    public LocalDateTime dateTimeOrKeep(String label, LocalDateTime current) {
        while (true) {
            String s = line(label + " (" + TimeFormatter.DATE_TIME_PATTERN + ", '-' = none) ["
                    + TimeFormatter.dateTime(current) + "]: ");
            if (s.isEmpty()) {
                return current;
            }
            if (s.equals("-")) {
                return null;
            }
            try {
                return TimeFormatter.parseDateTime(s);
            } catch (ValidationException e) {
                out.println("  " + e.getMessage());
            }
        }
    }

    public Duration duration(String prompt) {
        while (true) {
            try {
                return TimeFormatter.parseDuration(line(prompt));
            } catch (ValidationException e) {
                out.println("  " + e.getMessage());
            }
        }
    }

    /** Several lines, finished by a line containing only "." */
    public String multiline(String prompt) {
        out.println(prompt);
        StringBuilder sb = new StringBuilder();
        while (true) {
            String l = rawLine();
            if (l.trim().equals(".")) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(l);
        }
        return sb.toString().trim();
    }

    public void pause() {
        line("Press Enter to continue...");
    }
}
