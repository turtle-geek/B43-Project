package b43;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class In {
    private static final BufferedReader READER =
            new BufferedReader(new InputStreamReader(System.in));

    private In() {}

    public static String line(String prompt) {
        System.out.print(prompt);
        System.out.flush();
        try {
            String s = READER.readLine();
            if (s == null) {
                System.out.println();
                System.out.println("Input closed. Goodbye.");
                System.exit(0);
            }
            return s.trim();
        } catch (IOException e) {
            throw new AppException("Could not read input: " + e.getMessage());
        }
    }

    public static String nonEmpty(String prompt) {
        while (true) {
            String s = line(prompt);
            if (!s.isEmpty()) return s;
            System.out.println("  Please enter a value.");
        }
    }

    public static String multiline(String prompt) {
        System.out.println(prompt + " (finish with a line containing only '.')");
        StringBuilder sb = new StringBuilder();
        while (true) {
            String s = line("");
            if (s.equals(".")) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }

    public static int integer(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                System.out.println("  Please enter a whole number.");
            }
        }
    }

    public static int positiveInt(String prompt) {
        while (true) {
            int v = integer(prompt);
            if (v > 0) return v;
            System.out.println("  Please enter a number greater than 0.");
        }
    }

    public static BigDecimal money(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                BigDecimal v = new BigDecimal(s);
                if (v.signum() > 0 && v.scale() <= 2) return v;
                System.out.println("  Enter a positive amount with at most 2 decimals.");
            } catch (NumberFormatException e) {
                System.out.println("  Please enter a number.");
            }
        }
    }

    public static Double optionalDouble(String prompt, Double dflt) {
        while (true) {
            String s = line(prompt);
            if (s.isEmpty()) return dflt;
            try {
                double v = Double.parseDouble(s);
                if (v >= 0) return v;
                System.out.println("  Please enter a non-negative number.");
            } catch (NumberFormatException e) {
                System.out.println("  Please enter a number.");
            }
        }
    }

    public static Date date(String prompt) {
        while (true) {
            String s = line(prompt);
            try {
                return Date.valueOf(LocalDate.parse(s));
            } catch (DateTimeParseException e) {
                System.out.println("  Use the format YYYY-MM-DD.");
            }
        }
    }

    public static boolean yes(String prompt) {
        String s = line(prompt + " [y/N]: ");
        return s.equalsIgnoreCase("y") || s.equalsIgnoreCase("yes");
    }

    public static String symbol(String prompt) {
        while (true) {
            String s = line(prompt).toUpperCase();
            if (s.matches("[A-Z.\\-]{1,5}")) return s;
            System.out.println("  A symbol is 1-5 letters, e.g. AAPL.");
        }
    }
}
