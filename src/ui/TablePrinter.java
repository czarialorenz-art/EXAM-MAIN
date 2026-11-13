package ui;

import java.io.PrintStream;
import java.util.List;

/** Renders simple aligned text tables. */
public final class TablePrinter {
    private static final int MAX_COLUMN_WIDTH = 42;

    private TablePrinter() {
    }

    public static void print(PrintStream out, String[] headers, List<String[]> rows) {
        out.print(render(headers, rows));
    }

    public static String render(String[] headers, List<String[]> rows) {
        String nl = System.lineSeparator();
        if (rows.isEmpty()) {
            return "  (no records found)" + nl;
        }
        int[] w = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            w[i] = headers[i].length();
        }
        for (String[] row : rows) {
            for (int i = 0; i < headers.length && i < row.length; i++) {
                w[i] = Math.max(w[i], Math.min(MAX_COLUMN_WIDTH, clean(row[i]).length()));
            }
        }
        StringBuilder sep = new StringBuilder("+");
        for (int width : w) {
            sep.append("-".repeat(width + 2)).append('+');
        }
        StringBuilder sb = new StringBuilder();
        sb.append(sep).append(nl).append(line(headers, w)).append(nl).append(sep).append(nl);
        for (String[] row : rows) {
            sb.append(line(row, w)).append(nl);
        }
        sb.append(sep).append(nl).append("  ").append(rows.size()).append(rows.size() == 1 ? " record" : " records").append(nl);
        return sb.toString();
    }

    private static String line(String[] cells, int[] w) {
        StringBuilder sb = new StringBuilder("|");
        for (int i = 0; i < w.length; i++) {
            String c = clean(i < cells.length ? cells[i] : "");
            if (c.length() > w[i]) {
                c = c.substring(0, w[i] - 3) + "...";
            }
            sb.append(' ').append(c).append(" ".repeat(w[i] - c.length())).append(" |");
        }
        return sb.toString();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }
}
