package b43;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class Out {
    private Out() {}

    public static String fmt(Object v) {
        if (v == null) return "-";
        if (v instanceof BigDecimal) {
            return ((BigDecimal) v).setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d)) return "-";
            return Math.abs(d) >= 1000 ? String.format("%,.2f", d) : String.format("%.4f", d);
        }
        if (v instanceof Timestamp) {
            String s = v.toString();
            int dot = s.indexOf('.');
            return dot > 0 ? s.substring(0, dot) : s;
        }
        return v.toString();
    }

    public static String money(double d) {
        return String.format("$%,.2f", d);
    }

    public static String money(BigDecimal d) {
        return d == null ? "-" : money(d.doubleValue());
    }

    public static void table(Db.Result r) {
        List<String[]> rows = new ArrayList<>();
        for (Object[] row : r.rows) {
            String[] cells = new String[row.length];
            for (int i = 0; i < row.length; i++) cells[i] = fmt(row[i]);
            rows.add(cells);
        }
        table(r.columns, rows);
    }

    public static void table(List<String> headers, List<String[]> rows) {
        if (rows.isEmpty()) {
            System.out.println("  (none)");
            return;
        }
        int n = headers.size();
        int[] w = new int[n];
        for (int i = 0; i < n; i++) w[i] = headers.get(i).length();
        for (String[] row : rows) {
            for (int i = 0; i < n; i++) {
                for (String part : row[i].split("\n")) w[i] = Math.max(w[i], Math.min(part.length(), 80));
            }
        }
        StringBuilder sep = new StringBuilder("  +");
        for (int i = 0; i < n; i++) sep.append(repeat('-', w[i] + 2)).append('+');
        System.out.println(sep);
        System.out.println(rowLine(headers.toArray(new String[0]), w));
        System.out.println(sep);
        for (String[] row : rows) {
            List<List<String>> cells = new ArrayList<>();
            int height = 1;
            for (int i = 0; i < n; i++) {
                List<String> lines = wrap(row[i], w[i]);
                cells.add(lines);
                height = Math.max(height, lines.size());
            }
            for (int h = 0; h < height; h++) {
                String[] line = new String[n];
                for (int i = 0; i < n; i++) {
                    List<String> c = cells.get(i);
                    line[i] = h < c.size() ? c.get(h) : "";
                }
                System.out.println(rowLine(line, w));
            }
        }
        System.out.println(sep);
    }

    private static List<String> wrap(String s, int width) {
        List<String> out = new ArrayList<>();
        for (String part : s.split("\n", -1)) {
            while (part.length() > width) {
                out.add(part.substring(0, width));
                part = part.substring(width);
            }
            out.add(part);
        }
        return out;
    }

    private static String rowLine(String[] cells, int[] w) {
        StringBuilder sb = new StringBuilder("  |");
        for (int i = 0; i < cells.length; i++) {
            String c = cells[i];
            boolean numeric = c.matches("[-$]?[$]?[0-9,]+(\\.[0-9]+)?%?");
            sb.append(' ');
            if (numeric) sb.append(repeat(' ', w[i] - c.length())).append(c);
            else sb.append(c).append(repeat(' ', w[i] - c.length()));
            sb.append(" |");
        }
        return sb.toString();
    }

    public static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    public static void header(String title) {
        System.out.println();
        System.out.println("== " + title + " " + repeat('=', Math.max(0, 60 - title.length())));
    }

    public static void ok(String msg) {
        System.out.println("  [OK] " + msg);
    }

    public static void error(String msg) {
        System.out.println("  [!] " + msg);
    }
}
