package b43;

import java.sql.Date;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public final class StockService {
    private final Db db;

    public StockService(Db db) {
        this.db = db;
    }

    public void requireExists(String symbol) throws SQLException {
        if (!db.exists("SELECT Symbol FROM Stock WHERE Symbol = ?", symbol)) {
            throw new AppException("Unknown stock symbol: " + symbol);
        }
    }

    public Double latestClose(String symbol) throws SQLException {
        Object c = db.scalar("SELECT Close FROM LatestPrice WHERE Symbol = ?", symbol);
        return c == null ? null : ((Number) c).doubleValue();
    }

    public Date lastDate(String symbol) throws SQLException {
        return (Date) db.scalar("SELECT MAX(Timestamp) FROM StockRecord WHERE Symbol = ?", symbol);
    }

    public Date lastDate() throws SQLException {
        return (Date) db.scalar("SELECT MAX(Timestamp) FROM StockRecord");
    }

    public Db.Result history(String symbol, Date start, Date end) throws SQLException {
        return db.query(
                "SELECT Timestamp, Open, High, Low, Close, Volume " +
                "FROM StockRecord " +
                "WHERE Symbol = ? AND Timestamp >= ? AND Timestamp <= ? " +
                "ORDER BY Timestamp", symbol, start, end);
    }

    public void addDaily(String symbol, Date date, Double open, Double high, Double low,
                         double close, Long volume) throws SQLException {
        if (close <= 0) throw new AppException("Close must be positive.");
        db.tx(() -> {
            if (!db.exists("SELECT Symbol FROM Stock WHERE Symbol = ?", symbol)) {
                db.update("INSERT INTO Stock(Symbol) VALUES (?)", symbol);
            }
            if (db.exists("SELECT Symbol FROM StockHistory WHERE Symbol = ? AND Timestamp = ?", symbol, date)) {
                throw new AppException("The historic data already has " + symbol + " on " + date
                        + " (historic data is read-only).");
            }
            int n = db.update(
                    "UPDATE StockDaily SET Open = ?, High = ?, Low = ?, Close = ?, Volume = ? " +
                    "WHERE Symbol = ? AND Timestamp = ?",
                    open, high, low, close, volume, symbol, date);
            if (n == 0) {
                db.update(
                        "INSERT INTO StockDaily(Symbol, Timestamp, Open, High, Low, Close, Volume) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                        symbol, date, open, high, low, close, volume);
            }
            return null;
        });
    }

    public static final class Prediction {
        public final List<String> dates = new ArrayList<>();
        public final List<Double> past = new ArrayList<>();
        public final List<Double> predicted = new ArrayList<>();
        public double slopePerDay;
    }

    // least squares line through the last year of closes
    public Prediction predict(String symbol, int tradingDays) throws SQLException {
        Date last = lastDate(symbol);
        if (last == null) throw new AppException("No data for " + symbol + ".");

        Object[] s = db.query(
                "SELECT COUNT(*), SUM(x), SUM(Close), SUM(x * x), SUM(x * Close) " +
                "FROM (SELECT Timestamp - CAST(? AS DATE) AS x, Close " +
                "      FROM StockRecord " +
                "      WHERE Symbol = ? AND Close IS NOT NULL AND Timestamp > CAST(? AS DATE) - 365) Pts",
                last, symbol, last).rows.get(0);
        double n = ((Number) s[0]).doubleValue();
        if (n < 2) throw new AppException("Not enough data for " + symbol + " to make a prediction.");
        double sx = ((Number) s[1]).doubleValue();
        double sy = ((Number) s[2]).doubleValue();
        double sxx = ((Number) s[3]).doubleValue();
        double sxy = ((Number) s[4]).doubleValue();
        double slope = (n * sxy - sx * sy) / (n * sxx - sx * sx);
        double intercept = (sy - slope * sx) / n;

        Prediction p = new Prediction();
        p.slopePerDay = slope;

        Db.Result recent = db.query(
                "SELECT Timestamp, Close FROM StockRecord " +
                "WHERE Symbol = ? AND Close IS NOT NULL AND Timestamp > CAST(? AS DATE) - 90 " +
                "ORDER BY Timestamp", symbol, last);
        for (Object[] r : recent.rows) {
            p.dates.add(r[0].toString());
            p.past.add(((Number) r[1]).doubleValue());
            p.predicted.add(null);
        }

        LocalDate lastDay = last.toLocalDate();
        LocalDate d = lastDay;
        for (int i = 0; i < tradingDays; i++) {
            d = d.plusDays(1);
            while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
                d = d.plusDays(1);
            }
            double x = d.toEpochDay() - lastDay.toEpochDay();
            p.dates.add(d.toString());
            p.past.add(null);
            p.predicted.add(Math.max(0, intercept + slope * x));
        }
        return p;
    }
}
