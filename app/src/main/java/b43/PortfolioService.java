package b43;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;

/**
 * Portfolios (Portfolio, OwnsPortfolio, Holds). Portfolios are private, so
 * every operation first checks that the logged-in user owns the portfolio.
 */
public final class PortfolioService {
    /** Symbols in a portfolio; used by the statistics. */
    public static final String SYMBOLS = "SELECT Symbol FROM Holds WHERE PortfolioId = ?";

    private final Db db;
    private final StockService stocks;

    public PortfolioService(Db db, StockService stocks) {
        this.db = db;
        this.stocks = stocks;
    }

    /** The user's portfolios with their cash and the market value of their stocks. */
    public Db.Result list(int u) throws SQLException {
        return db.query(
                "SELECT p.PortfolioId, p.Name, p.Cash, SUM(h.Shares * lp.Close) AS StockValue " +
                "FROM Portfolio p " +
                "JOIN OwnsPortfolio o ON o.PortfolioId = p.PortfolioId " +
                "LEFT OUTER JOIN Holds h ON h.PortfolioId = p.PortfolioId " +
                "LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol " +
                "WHERE o.UserId = ? " +
                "GROUP BY p.PortfolioId, p.Name, p.Cash " +
                "ORDER BY p.PortfolioId", u);
    }

    public int create(int u, String name) throws SQLException {
        return db.tx(() -> {
            int id = (Integer) db.scalar("INSERT INTO Portfolio(Name, Cash) VALUES (?, 0) RETURNING PortfolioId", name);
            db.update("INSERT INTO OwnsPortfolio(PortfolioId, UserId) VALUES (?, ?)", id, u);
            return id;
        });
    }

    /** Name of the portfolio; error if the user does not own it. */
    public String name(int u, int p) throws SQLException {
        Object n = db.scalar(
                "SELECT p.Name FROM Portfolio p, OwnsPortfolio o " +
                "WHERE p.PortfolioId = o.PortfolioId AND p.PortfolioId = ? AND o.UserId = ?", p, u);
        if (n == null) throw new AppException("You do not own a portfolio with ID " + p + ".");
        return (String) n;
    }

    /**
     * Query 4: each holding's market value = Shares * latest Close
     * (LatestPrice is the Latest/Price part of the relational algebra).
     */
    public Db.Result holdings(int u, int p) throws SQLException {
        name(u, p);
        return db.query(
                "SELECT h.Symbol, h.Shares, lp.LastTime AS PriceDate, lp.Close AS LastClose, " +
                "       h.Shares * lp.Close AS MarketValue " +
                "FROM Holds h LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol " +
                "WHERE h.PortfolioId = ? " +
                "ORDER BY h.Symbol", p);
    }

    /** Query 4: returns {Cash, StockTotal}. The portfolio's value is their sum. */
    public double[] totals(int p) throws SQLException {
        Object cash = db.scalar("SELECT Cash FROM Portfolio WHERE PortfolioId = ?", p);
        Object stock = db.scalar(
                "SELECT SUM(h.Shares * lp.Close) " +
                "FROM Holds h, LatestPrice lp " +
                "WHERE h.Symbol = lp.Symbol AND h.PortfolioId = ?", p);
        return new double[]{
                ((Number) cash).doubleValue(),
                stock == null ? 0 : ((Number) stock).doubleValue()};
    }

    public void deposit(int u, int p, BigDecimal amount) throws SQLException {
        name(u, p);
        db.update("UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?", amount, p);
    }

    public void withdraw(int u, int p, BigDecimal amount) throws SQLException {
        name(u, p);
        int n = db.update("UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?",
                amount, p, amount);
        if (n == 0) throw new AppException("Not enough cash in the portfolio.");
    }

    /** Moves cash from one of the user's portfolios to another. */
    public void transfer(int u, int from, int to, BigDecimal amount) throws SQLException {
        if (from == to) throw new AppException("Choose two different portfolios.");
        name(u, from);
        name(u, to);
        db.tx(() -> {
            int n = db.update("UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?",
                    amount, from, amount);
            if (n == 0) throw new AppException("Not enough cash in portfolio " + from + ".");
            db.update("UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?", amount, to);
            return null;
        });
    }

    /** Buys shares at the latest close price. Returns the cost. */
    public BigDecimal buy(int u, int p, String symbol, int shares) throws SQLException {
        name(u, p);
        stocks.requireExists(symbol);
        BigDecimal cost = value(symbol, shares);
        db.tx(() -> {
            int n = db.update("UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?",
                    cost, p, cost);
            if (n == 0) throw new AppException("Not enough cash: the purchase costs " + Out.money(cost) + ".");
            int m = db.update("UPDATE Holds SET Shares = Shares + ? WHERE PortfolioId = ? AND Symbol = ?",
                    shares, p, symbol);
            if (m == 0) {
                db.update("INSERT INTO Holds(Symbol, PortfolioId, Shares) VALUES (?, ?, ?)", symbol, p, shares);
            }
            return null;
        });
        return cost;
    }

    /** Sells shares at the latest close price. Returns the proceeds. */
    public BigDecimal sell(int u, int p, String symbol, int shares) throws SQLException {
        name(u, p);
        Object held = db.scalar("SELECT Shares FROM Holds WHERE PortfolioId = ? AND Symbol = ?", p, symbol);
        int have = held == null ? 0 : (Integer) held;
        if (have < shares) throw new AppException("You only hold " + have + " shares of " + symbol + ".");
        BigDecimal proceeds = value(symbol, shares);
        db.tx(() -> {
            int n;
            if (have == shares) {
                n = db.update("DELETE FROM Holds WHERE PortfolioId = ? AND Symbol = ? AND Shares = ?",
                        p, symbol, shares);
            } else {
                n = db.update("UPDATE Holds SET Shares = Shares - ? " +
                              "WHERE PortfolioId = ? AND Symbol = ? AND Shares > ?",
                        shares, p, symbol, shares);
            }
            if (n == 0) throw new AppException("Your holding changed; please try again.");
            db.update("UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?", proceeds, p);
            return null;
        });
        return proceeds;
    }

    /** shares * latest close, rounded to cents. */
    private BigDecimal value(String symbol, int shares) throws SQLException {
        Double price = stocks.latestClose(symbol);
        if (price == null) throw new AppException("No price data for " + symbol + ".");
        return BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(shares)).setScale(2, RoundingMode.HALF_UP);
    }
}
