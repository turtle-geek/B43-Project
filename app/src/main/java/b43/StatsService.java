package b43;

import java.sql.Date;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class StatsService {
    private final Db db;
    private final Map<String, Db.Result[]> cache = new HashMap<>();

    public StatsService(Db db) {
        this.db = db;
    }

    public void clearCache() {
        cache.clear();
    }

    // COV = stddev / mean, Beta = Cov(stock, market) / Var(market)
    public void show(String symbolsQuery, int id, Date start, Date end) throws SQLException {
        List<String> symbols = new ArrayList<>();
        for (Object[] r : db.query(symbolsQuery + " ORDER BY Symbol", id).rows) symbols.add((String) r[0]);
        if (symbols.isEmpty()) {
            System.out.println("  There are no stocks to analyse.");
            return;
        }

        String key = String.join(",", symbols) + "|" + start + "|" + end;
        Db.Result[] results = cache.get(key);
        long t0 = System.currentTimeMillis();
        boolean cached = results != null;
        if (!cached) {
            results = new Db.Result[]{perStock(symbolsQuery, id, start, end), pairs(symbolsQuery, id, start, end)};
            cache.put(key, results);
        }
        long ms = System.currentTimeMillis() - t0;

        Out.header("Statistics from " + start + " to " + end);
        System.out.println("  " + (cached ? "From cache" : "Computed in " + ms + " ms")
                + ". Based on daily close prices; the market is the average close of all stocks.");

        List<String[]> rows = new ArrayList<>();
        for (Object[] r : results[0].rows) {
            double mean = num(r[2]), var = num(r[3]), covM = num(r[4]), varM = num(r[5]);
            String cov = mean > 0 ? String.format("%.4f", Math.sqrt(Math.max(0, var)) / mean) : "-";
            String beta = varM > 0 ? String.format("%.4f", covM / varM) : "-";
            rows.add(new String[]{(String) r[0], Out.fmt(r[1]), cov, beta});
        }
        Out.table(java.util.Arrays.asList("Symbol", "Days", "COV", "Beta"), rows);

        Map<String, double[]> m = new HashMap<>();
        for (Object[] r : results[1].rows) {
            double cov = num(r[2]), varA = num(r[3]), varB = num(r[4]);
            double corr = (varA > 0 && varB > 0) ? cov / Math.sqrt(varA * varB) : Double.NaN;
            m.put(r[0] + "|" + r[1], new double[]{cov, corr});
            m.put(r[1] + "|" + r[0], new double[]{cov, corr});
        }
        printMatrix("Covariance matrix", symbols, m, 0, "%.4f");
        printMatrix("Correlation matrix", symbols, m, 1, "%.3f");
    }

    private Db.Result perStock(String symbolsQuery, int id, Date start, Date end) throws SQLException {
        return db.query(
                "SELECT s.Symbol, COUNT(*) AS Days, AVG(s.Close) AS Mean, " +
                "       AVG(s.Close * s.Close) - AVG(s.Close) * AVG(s.Close) AS Var, " +
                "       AVG(s.Close * m.MarketClose) - AVG(s.Close) * AVG(m.MarketClose) AS CovMarket, " +
                "       AVG(m.MarketClose * m.MarketClose) - AVG(m.MarketClose) * AVG(m.MarketClose) AS VarMarket " +
                "FROM StockRecord s, MarketDaily m " +
                "WHERE s.Timestamp = m.Timestamp " +
                "  AND s.Symbol IN (" + symbolsQuery + ") " +
                "  AND s.Timestamp >= ? AND s.Timestamp <= ? " +
                "  AND m.Timestamp >= ? AND m.Timestamp <= ? " +
                "  AND s.Close IS NOT NULL " +
                "GROUP BY s.Symbol " +
                "ORDER BY s.Symbol",
                id, start, end, start, end);
    }

    private Db.Result pairs(String symbolsQuery, int id, Date start, Date end) throws SQLException {
        return db.query(
                "SELECT a.Symbol, b.Symbol, " +
                "       AVG(a.Close * b.Close) - AVG(a.Close) * AVG(b.Close) AS Cov, " +
                "       AVG(a.Close * a.Close) - AVG(a.Close) * AVG(a.Close) AS VarA, " +
                "       AVG(b.Close * b.Close) - AVG(b.Close) * AVG(b.Close) AS VarB " +
                "FROM StockRecord a, StockRecord b " +
                "WHERE a.Timestamp = b.Timestamp AND a.Symbol <= b.Symbol " +
                "  AND a.Symbol IN (" + symbolsQuery + ") " +
                "  AND b.Symbol IN (" + symbolsQuery + ") " +
                "  AND a.Timestamp >= ? AND a.Timestamp <= ? " +
                "  AND a.Close IS NOT NULL AND b.Close IS NOT NULL " +
                "GROUP BY a.Symbol, b.Symbol",
                id, id, start, end);
    }

    private static double num(Object o) {
        return o == null ? Double.NaN : ((Number) o).doubleValue();
    }

    private static void printMatrix(String title, List<String> syms, Map<String, double[]> m, int idx, String f) {
        System.out.println();
        System.out.println("  " + title);
        List<String> headers = new ArrayList<>();
        headers.add("");
        headers.addAll(syms);
        List<String[]> rows = new ArrayList<>();
        for (String a : syms) {
            String[] row = new String[syms.size() + 1];
            row[0] = a;
            for (int j = 0; j < syms.size(); j++) {
                double[] v = m.get(a + "|" + syms.get(j));
                row[j + 1] = (v == null || Double.isNaN(v[idx])) ? "-" : String.format(f, v[idx]);
            }
            rows.add(row);
        }
        Out.table(headers, rows);
    }
}
