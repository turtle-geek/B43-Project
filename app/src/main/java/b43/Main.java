package b43;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Command-line interface of the stock social network.
 *
 * Database connection (environment variables, defaults in brackets):
 *   DB_URL [jdbc:postgresql://localhost:5432/mydb], DB_USER [postgres], DB_PASSWORD [postgres]
 */
public final class Main {

    /** A menu action that may use the database. */
    private interface Action {
        void run() throws SQLException;
    }

    private final AuthService auth;
    private final FriendService friends;
    private final StockService stocks;
    private final StatsService stats;
    private final PortfolioService portfolios;
    private final StockListService lists;
    private final ReviewService reviews;

    private int user = -1;
    private String email;

    private Main(Db db) {
        auth = new AuthService(db);
        friends = new FriendService(db);
        stocks = new StockService(db);
        stats = new StatsService(db);
        portfolios = new PortfolioService(db, stocks);
        lists = new StockListService(db, stocks, friends);
        reviews = new ReviewService(db, lists);
    }

    public static void main(String[] args) {
        String url = env("DB_URL", "jdbc:postgresql://localhost:5432/mydb");
        try (Db db = new Db(url, env("DB_USER", "postgres"), env("DB_PASSWORD", "postgres"))) {
            new Main(db).run();
        } catch (SQLException e) {
            System.err.println("Could not connect to " + url + ": " + e.getMessage());
            System.exit(1);
        }
    }

    private static String env(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? dflt : v;
    }

    /** Runs an action and prints any error instead of exiting. */
    private void attempt(Action a) {
        try {
            a.run();
        } catch (AppException e) {
            Out.error(e.getMessage());
        } catch (SQLException e) {
            Out.error("Database error: " + e.getMessage());
        }
    }

    private void run() {
        System.out.println("Stock Social Network (CSCC43 project)");
        while (true) {
            if (user < 0) {
                Out.header("Welcome");
                System.out.println("  1. Register");
                System.out.println("  2. Log in");
                System.out.println("  0. Exit");
                String c = In.line("> ");
                if (c.equals("1")) attempt(this::register);
                else if (c.equals("2")) attempt(this::login);
                else if (c.equals("0")) return;
            } else {
                Out.header("Main menu (" + email + ")");
                System.out.println("  1. Portfolios");
                System.out.println("  2. Stock lists");
                System.out.println("  3. Friends");
                System.out.println("  4. Stocks (history, prediction, record new data)");
                System.out.println("  9. Log out");
                System.out.println("  0. Exit");
                String c = In.line("> ");
                if (c.equals("1")) portfoliosMenu();
                else if (c.equals("2")) listsMenu();
                else if (c.equals("3")) friendsMenu();
                else if (c.equals("4")) stocksMenu();
                else if (c.equals("9")) user = -1;
                else if (c.equals("0")) return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Account
    // ------------------------------------------------------------------

    private void register() throws SQLException {
        String e = In.nonEmpty("Email: ");
        String p = In.nonEmpty("Password: ");
        user = auth.register(e, p);
        email = AuthService.normalise(e);
        Out.ok("Account created. Logged in as " + email + ".");
    }

    private void login() throws SQLException {
        String e = In.nonEmpty("Email: ");
        String p = In.nonEmpty("Password: ");
        user = auth.login(e, p);
        email = AuthService.normalise(e);
        Out.ok("Logged in as " + email + ".");
    }

    // ------------------------------------------------------------------
    // Portfolios
    // ------------------------------------------------------------------

    private void portfoliosMenu() {
        while (true) {
            Out.header("My portfolios");
            attempt(() -> Out.table(portfolios.list(user)));
            System.out.println("  1. Open a portfolio");
            System.out.println("  2. Create a portfolio");
            System.out.println("  3. Transfer cash between my portfolios");
            System.out.println("  0. Back");
            String c = In.line("> ");
            if (c.equals("1")) {
                int p = In.integer("Portfolio ID: ");
                attempt(() -> {
                    portfolios.name(user, p);   // checks ownership
                    portfolioMenu(p);
                });
            } else if (c.equals("2")) {
                attempt(() -> Out.ok("Created portfolio " + portfolios.create(user, In.nonEmpty("Name: ")) + "."));
            } else if (c.equals("3")) {
                attempt(() -> {
                    int from = In.integer("From portfolio ID: ");
                    int to = In.integer("To portfolio ID: ");
                    BigDecimal amount = In.money("Amount: ");
                    portfolios.transfer(user, from, to, amount);
                    Out.ok("Transferred " + Out.money(amount) + ".");
                });
            } else if (c.equals("0")) {
                return;
            }
        }
    }

    private void portfolioMenu(int p) {
        while (true) {
            attempt(() -> {
                Out.header("Portfolio " + p + ": " + portfolios.name(user, p));
                Out.table(portfolios.holdings(user, p));
                double[] t = portfolios.totals(p);
                System.out.println("  Cash: " + Out.money(t[0]) + "   Stocks: " + Out.money(t[1])
                        + "   Total market value: " + Out.money(t[0] + t[1]));
            });
            System.out.println("  1. Deposit cash      2. Withdraw cash");
            System.out.println("  3. Buy stock         4. Sell stock");
            System.out.println("  5. Statistics (COV, Beta, covariance/correlation matrix)");
            System.out.println("  6. Past prices of a holding");
            System.out.println("  7. Predicted prices of a holding");
            System.out.println("  0. Back");
            String c = In.line("> ");
            switch (c) {
                case "1":
                    attempt(() -> {
                        portfolios.deposit(user, p, In.money("Amount: "));
                        Out.ok("Deposited.");
                    });
                    break;
                case "2":
                    attempt(() -> {
                        portfolios.withdraw(user, p, In.money("Amount: "));
                        Out.ok("Withdrawn.");
                    });
                    break;
                case "3":
                    attempt(() -> {
                        String s = In.symbol("Symbol: ");
                        stocks.requireExists(s);
                        System.out.println("  Latest close: " + Out.fmt(stocks.latestClose(s)));
                        int n = In.positiveInt("Shares to buy: ");
                        Out.ok("Bought " + n + " " + s + " for " + Out.money(portfolios.buy(user, p, s, n)) + ".");
                    });
                    break;
                case "4":
                    attempt(() -> {
                        String s = In.symbol("Symbol: ");
                        int n = In.positiveInt("Shares to sell: ");
                        Out.ok("Sold " + n + " " + s + " for " + Out.money(portfolios.sell(user, p, s, n)) + ".");
                    });
                    break;
                case "5":
                    attempt(() -> {
                        Date[] iv = pickInterval(stocks.lastDate());
                        stats.show(PortfolioService.SYMBOLS, p, iv[0], iv[1]);
                    });
                    break;
                case "6":
                    attempt(() -> showHistory(pickSymbol(holdingsOf(p))));
                    break;
                case "7":
                    attempt(() -> showPrediction(pickSymbol(holdingsOf(p))));
                    break;
                case "0":
                    return;
                default:
                    break;
            }
        }
    }

    /** Asks for one of the given stocks. */
    private String pickSymbol(List<String> syms) {
        if (syms.isEmpty()) throw new AppException("There are no stocks here.");
        System.out.println("  Stocks: " + String.join(", ", syms));
        String s = In.symbol("Symbol: ");
        if (!syms.contains(s)) throw new AppException(s + " is not one of these stocks.");
        return s;
    }

    private List<String> holdingsOf(int p) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Object[] r : portfolios.holdings(user, p).rows) out.add((String) r[0]);
        return out;
    }

    // ------------------------------------------------------------------
    // Stocks: history, prediction, new data
    // ------------------------------------------------------------------

    /** Interval ending at the given date: week, month, quarter, year, 5 years or custom. */
    private Date[] pickInterval(Date end) {
        if (end == null) throw new AppException("There is no stock data.");
        LocalDate e = end.toLocalDate();
        System.out.println("  Interval ending " + e + ":");
        System.out.println("  1. Week  2. Month  3. Quarter  4. Year  5. Five years  6. Custom dates");
        switch (In.line("> ")) {
            case "1": return new Date[]{Date.valueOf(e.minusWeeks(1)), end};
            case "2": return new Date[]{Date.valueOf(e.minusMonths(1)), end};
            case "3": return new Date[]{Date.valueOf(e.minusMonths(3)), end};
            case "4": return new Date[]{Date.valueOf(e.minusYears(1)), end};
            case "5": return new Date[]{Date.valueOf(e.minusYears(5)), end};
            case "6": {
                Date s = In.date("Start date (YYYY-MM-DD): ");
                Date t = In.date("End date (YYYY-MM-DD): ");
                if (s.after(t)) throw new AppException("The start date is after the end date.");
                return new Date[]{s, t};
            }
            default: throw new AppException("Unknown choice.");
        }
    }

    /** Table and plot of a stock's past close prices. */
    private void showHistory(String symbol) throws SQLException {
        stocks.requireExists(symbol);
        Date[] iv = pickInterval(stocks.lastDate(symbol));
        Db.Result r = stocks.history(symbol, iv[0], iv[1]);
        Out.header(symbol + " from " + iv[0] + " to " + iv[1]);
        if (r.isEmpty()) {
            System.out.println("  No data in this interval.");
            return;
        }
        List<String> dates = new ArrayList<>();
        double[] close = new double[r.size()];
        for (int i = 0; i < r.size(); i++) {
            dates.add(r.get(i, 0).toString());
            close[i] = r.get(i, 4) == null ? Double.NaN : ((Number) r.get(i, 4)).doubleValue();
        }
        AsciiChart.plot(symbol + " close price", dates,
                Arrays.asList(new AsciiChart.Series("Close", '*', close)));
        if (r.size() <= 30 || In.yes("Show all " + r.size() + " rows as a table?")) Out.table(r);
    }

    /** Plot and table of predicted close prices. */
    private void showPrediction(String symbol) throws SQLException {
        stocks.requireExists(symbol);
        System.out.println("  Predict how far ahead?");
        System.out.println("  1. Week  2. Month  3. Quarter  4. Year");
        int days;
        switch (In.line("> ")) {
            case "1": days = 5; break;
            case "2": days = 21; break;
            case "3": days = 63; break;
            case "4": days = 252; break;
            default: throw new AppException("Unknown choice.");
        }
        StockService.Prediction p = stocks.predict(symbol, days);
        int n = p.dates.size();
        double[] past = new double[n], pred = new double[n];
        for (int i = 0; i < n; i++) {
            past[i] = p.past.get(i) == null ? Double.NaN : p.past.get(i);
            pred[i] = p.predicted.get(i) == null ? Double.NaN : p.predicted.get(i);
        }
        Out.header("Predicted close price of " + symbol);
        AsciiChart.plot(symbol + ": recent and predicted close", p.dates, Arrays.asList(
                new AsciiChart.Series("Past", '*', past),
                new AsciiChart.Series("Predicted", 'o', pred)));
        System.out.printf("  Straight-line fit on the last year of closes: %+.4f per day.%n", p.slopePerDay);

        List<String[]> rows = new ArrayList<>();
        int step = Math.max(1, days / 10);
        for (int i = n - days; i < n; i += step) {
            rows.add(new String[]{p.dates.get(i), String.format("%.2f", pred[i])});
        }
        if ((days - 1) % step != 0) rows.add(new String[]{p.dates.get(n - 1), String.format("%.2f", pred[n - 1])});
        Out.table(Arrays.asList("Date", "Predicted close"), rows);
    }

    private void stocksMenu() {
        while (true) {
            Out.header("Stocks");
            System.out.println("  1. Past prices of a stock");
            System.out.println("  2. Predicted prices of a stock");
            System.out.println("  3. Record new daily stock data");
            System.out.println("  0. Back");
            String c = In.line("> ");
            if (c.equals("1")) {
                attempt(() -> showHistory(In.symbol("Symbol: ")));
            } else if (c.equals("2")) {
                attempt(() -> showPrediction(In.symbol("Symbol: ")));
            } else if (c.equals("3")) {
                attempt(() -> {
                    String s = In.symbol("Symbol: ");
                    Date d = In.date("Date (YYYY-MM-DD): ");
                    Double open = In.optionalDouble("Open (blank if unknown): ", null);
                    Double high = In.optionalDouble("High (blank if unknown): ", null);
                    Double low = In.optionalDouble("Low (blank if unknown): ", null);
                    Double close = null;
                    while (close == null) close = In.optionalDouble("Close: ", null);
                    Double vol = In.optionalDouble("Volume (blank if unknown): ", null);
                    stocks.addDaily(s, d, open, high, low, close, vol == null ? null : vol.longValue());
                    stats.clearCache();
                    Out.ok("Recorded " + s + " on " + d + ".");
                });
            } else if (c.equals("0")) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Stock lists and reviews
    // ------------------------------------------------------------------

    private void listsMenu() {
        while (true) {
            Out.header("Stock lists I can see");
            attempt(() -> Out.table(StockListService.ACCESSIBLE_COLUMNS, lists.accessible(user)));
            System.out.println("  1. Open a stock list");
            System.out.println("  2. Create a stock list");
            System.out.println("  0. Back");
            String c = In.line("> ");
            if (c.equals("1")) {
                int l = In.integer("List ID: ");
                attempt(() -> {
                    lists.requireAccess(user, l);
                    listMenu(l);
                });
            } else if (c.equals("2")) {
                attempt(() -> Out.ok("Created stock list " + lists.create(user, In.nonEmpty("Name: ")) + "."));
            } else if (c.equals("0")) {
                return;
            }
        }
    }

    private void listMenu(int l) throws SQLException {
        while (lists.canAccess(user, l)) {
            boolean mine = lists.isCreator(user, l);
            Object[] info = lists.info(l);
            Out.header("Stock list " + l + ": " + info[0]);
            System.out.println("  Creator: " + info[2] + "   Visibility: " + info[1]);
            attempt(() -> Out.table(lists.contents(user, l)));
            System.out.println("  1. View reviews        2. Write or edit my review");
            System.out.println("  3. Delete a review     4. Statistics");
            System.out.println("  5. Past prices of a stock in the list");
            System.out.println("  6. Predicted prices of a stock in the list");
            if (mine) {
                System.out.println("  7. Add a stock or change its shares    8. Remove a stock");
                System.out.println("  9. Share with a friend               10. Make public");
                System.out.println(" 11. Delete this list");
            }
            System.out.println("  0. Back");
            String c = In.line("> ");
            if (!mine && c.matches("7|8|9|10|11")) c = "";
            switch (c) {
                case "1":
                    attempt(() -> Out.table(reviews.visible(user, l)));
                    break;
                case "2":
                    attempt(() -> {
                        Integer own = reviews.ownReview(user, l);
                        String text = In.multiline(own == null ? "Your review" : "Your new review text");
                        if (own == null) reviews.write(user, l, text);
                        else reviews.edit(user, l, text);
                        Out.ok("Review saved.");
                    });
                    break;
                case "3":
                    attempt(() -> {
                        reviews.delete(user, l, In.integer("Review ID: "));
                        Out.ok("Review deleted.");
                    });
                    break;
                case "4":
                    attempt(() -> {
                        Date[] iv = pickInterval(stocks.lastDate());
                        stats.show(StockListService.SYMBOLS, l, iv[0], iv[1]);
                    });
                    break;
                case "5":
                    attempt(() -> showHistory(pickSymbol(lists.symbols(l))));
                    break;
                case "6":
                    attempt(() -> showPrediction(pickSymbol(lists.symbols(l))));
                    break;
                case "7":
                    attempt(() -> {
                        String s = In.symbol("Symbol: ");
                        lists.setStock(user, l, s, In.positiveInt("Shares: "));
                        Out.ok("Saved.");
                    });
                    break;
                case "8":
                    attempt(() -> {
                        lists.removeStock(user, l, In.symbol("Symbol: "));
                        Out.ok("Removed.");
                    });
                    break;
                case "9":
                    attempt(() -> {
                        System.out.println("  My friends:");
                        Out.table(friends.friends(user));
                        lists.share(user, l, In.nonEmpty("Friend's email: "));
                        Out.ok("Shared.");
                    });
                    break;
                case "10":
                    attempt(() -> {
                        lists.makePublic(user, l);
                        Out.ok("The list is now public.");
                    });
                    break;
                case "11":
                    if (In.yes("Delete this list and all its reviews?")) {
                        attempt(() -> {
                            lists.delete(user, l);
                            Out.ok("Deleted.");
                        });
                    }
                    break;
                case "0":
                    return;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Friends
    // ------------------------------------------------------------------

    private void friendsMenu() {
        while (true) {
            Out.header("Friends");
            attempt(() -> {
                System.out.println("  My friends:");
                Out.table(friends.friends(user));
                System.out.println("  Incoming requests:");
                Out.table(friends.incoming(user));
                System.out.println("  Outgoing requests:");
                Out.table(friends.outgoing(user));
            });
            System.out.println("  1. Send a request   2. Accept a request   3. Reject a request");
            System.out.println("  4. Remove a friend  0. Back");
            String c = In.line("> ");
            if (c.equals("1")) {
                attempt(() -> {
                    friends.send(user, In.nonEmpty("Email: "));
                    Out.ok("Request sent.");
                });
            } else if (c.equals("2")) {
                attempt(() -> {
                    friends.respond(user, In.integer("Request ID: "), true);
                    Out.ok("Accepted.");
                });
            } else if (c.equals("3")) {
                attempt(() -> {
                    friends.respond(user, In.integer("Request ID: "), false);
                    Out.ok("Rejected.");
                });
            } else if (c.equals("4")) {
                attempt(() -> {
                    friends.remove(user, In.nonEmpty("Friend's email: "));
                    Out.ok("Friend removed.");
                });
            } else if (c.equals("0")) {
                return;
            }
        }
    }
}
