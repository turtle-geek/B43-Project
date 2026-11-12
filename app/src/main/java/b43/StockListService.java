package b43;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Stock lists (StockList, Creates, SharedWith, Contains).
 * A list can be seen by its creator, the friends it is shared with, and
 * everyone if it is public. Only the creator can change, share or delete it.
 */
public final class StockListService {
    /** Symbols in a stock list; used by the statistics. */
    public static final String SYMBOLS = "SELECT Symbol FROM Contains WHERE ListId = ?";

    private final Db db;
    private final StockService stocks;
    private final FriendService friends;

    public StockListService(Db db, StockService stocks, FriendService friends) {
        this.db = db;
        this.stocks = stocks;
        this.friends = friends;
    }

    /**
     * Query 3: lists the user created, lists shared with them, and public
     * lists, each tagged by visibility from the user's point of view.
     */
    public List<String[]> accessible(int u) throws SQLException {
        Db.Result r = db.query(
                "SELECT sl.ListId, sl.Name, sl.Visibility, c.UserId, a.Email " +
                "FROM StockList sl, Creates c, Account a, " +
                "     (SELECT ListId FROM Creates WHERE UserId = ? " +
                "      UNION " +
                "      SELECT ListId FROM SharedWith WHERE UserId = ? " +
                "      UNION " +
                "      SELECT ListId FROM StockList WHERE Visibility = 'public') Acc " +
                "WHERE sl.ListId = Acc.ListId AND c.ListId = sl.ListId AND a.UserId = c.UserId " +
                "ORDER BY sl.ListId", u, u);
        List<String[]> rows = new ArrayList<>();
        for (Object[] x : r.rows) {
            String vis = (String) x[2];
            String tag;
            if ((Integer) x[3] == u) tag = vis + " (mine)";
            else if (vis.equals("public")) tag = "public";
            else tag = "shared with me";
            rows.add(new String[]{x[0].toString(), (String) x[1], tag, (String) x[4]});
        }
        return rows;
    }

    public static final List<String> ACCESSIBLE_COLUMNS = Arrays.asList("ListId", "Name", "Visibility", "Creator");

    public boolean canAccess(int u, int l) throws SQLException {
        return db.exists(
                "SELECT ListId FROM StockList WHERE ListId = ? AND Visibility = 'public' " +
                "UNION SELECT ListId FROM Creates WHERE ListId = ? AND UserId = ? " +
                "UNION SELECT ListId FROM SharedWith WHERE ListId = ? AND UserId = ?",
                l, l, u, l, u);
    }

    public void requireAccess(int u, int l) throws SQLException {
        if (!canAccess(u, l)) throw new AppException("No stock list with ID " + l + " is accessible to you.");
    }

    public boolean isCreator(int u, int l) throws SQLException {
        return db.exists("SELECT ListId FROM Creates WHERE ListId = ? AND UserId = ?", l, u);
    }

    private void requireCreator(int u, int l) throws SQLException {
        if (!isCreator(u, l)) throw new AppException("Only the creator of the list can do that.");
    }

    /** {Name, Visibility, creator's Email} of a list. */
    public Object[] info(int l) throws SQLException {
        return db.query(
                "SELECT sl.Name, sl.Visibility, a.Email " +
                "FROM StockList sl, Creates c, Account a " +
                "WHERE sl.ListId = c.ListId AND c.UserId = a.UserId AND sl.ListId = ?", l).rows.get(0);
    }

    public int create(int u, String name) throws SQLException {
        return db.tx(() -> {
            int id = (Integer) db.scalar(
                    "INSERT INTO StockList(Name, Visibility) VALUES (?, 'private') RETURNING ListId", name);
            db.update("INSERT INTO Creates(ListId, UserId) VALUES (?, ?)", id, u);
            return id;
        });
    }

    /** Deletes a list and all of its reviews. */
    public void delete(int u, int l) throws SQLException {
        requireCreator(u, l);
        db.tx(() -> {
            db.update("DELETE FROM Review WHERE ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?)", l);
            db.update("DELETE FROM StockList WHERE ListId = ?", l);
            return null;
        });
    }

    /** The stocks in a list with their present market value. */
    public Db.Result contents(int u, int l) throws SQLException {
        requireAccess(u, l);
        return db.query(
                "SELECT c.Symbol, c.Shares, lp.Close AS LastClose, c.Shares * lp.Close AS MarketValue " +
                "FROM Contains c LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = c.Symbol " +
                "WHERE c.ListId = ? " +
                "ORDER BY c.Symbol", l);
    }

    public List<String> symbols(int l) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Object[] r : db.query(SYMBOLS + " ORDER BY Symbol", l).rows) out.add((String) r[0]);
        return out;
    }

    /** Adds a stock to the list, or changes its number of shares. */
    public void setStock(int u, int l, String symbol, int shares) throws SQLException {
        requireCreator(u, l);
        stocks.requireExists(symbol);
        int n = db.update("UPDATE Contains SET Shares = ? WHERE ListId = ? AND Symbol = ?", shares, l, symbol);
        if (n == 0) {
            db.update("INSERT INTO Contains(Symbol, ListId, Shares) VALUES (?, ?, ?)", symbol, l, shares);
        }
    }

    public void removeStock(int u, int l, String symbol) throws SQLException {
        requireCreator(u, l);
        if (db.update("DELETE FROM Contains WHERE ListId = ? AND Symbol = ?", l, symbol) == 0) {
            throw new AppException(symbol + " is not in this list.");
        }
    }

    /** Shares the list with a friend; a private list becomes 'shared'. */
    public void share(int u, int l, String email) throws SQLException {
        requireCreator(u, l);
        int f = AuthService.userIdOf(db, email);
        if (!friends.areFriends(u, f)) throw new AppException("You can only share lists with friends.");
        if (db.exists("SELECT ListId FROM SharedWith WHERE ListId = ? AND UserId = ?", l, f)) {
            throw new AppException("The list is already shared with that user.");
        }
        db.tx(() -> {
            db.update("INSERT INTO SharedWith(ListId, UserId) VALUES (?, ?)", l, f);
            db.update("UPDATE StockList SET Visibility = 'shared' WHERE ListId = ? AND Visibility = 'private'", l);
            return null;
        });
    }

    public void makePublic(int u, int l) throws SQLException {
        requireCreator(u, l);
        db.update("UPDATE StockList SET Visibility = 'public' WHERE ListId = ?", l);
    }
}
