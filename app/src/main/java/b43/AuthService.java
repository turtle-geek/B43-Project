package b43;

import java.sql.SQLException;

/** Register and log in (Account). */
public final class AuthService {
    private final Db db;

    public AuthService(Db db) {
        this.db = db;
    }

    /** Creates an account and returns its UserId. */
    public int register(String email, String password) throws SQLException {
        email = normalise(email);
        if (!email.contains("@")) throw new AppException("Please enter a valid email address.");
        if (db.exists("SELECT UserId FROM Account WHERE Email = ?", email)) {
            throw new AppException("An account with that email already exists.");
        }
        return (Integer) db.scalar(
                "INSERT INTO Account(Email, Password) VALUES (?, ?) RETURNING UserId",
                email, password);
    }

    /** Returns the UserId if the email and password match an account. */
    public int login(String email, String password) throws SQLException {
        Object id = db.scalar("SELECT UserId FROM Account WHERE Email = ? AND Password = ?",
                normalise(email), password);
        if (id == null) throw new AppException("Invalid email or password.");
        return (Integer) id;
    }

    /** UserId of the account with this email; error if there is none. */
    public static int userIdOf(Db db, String email) throws SQLException {
        Object id = db.scalar("SELECT UserId FROM Account WHERE Email = ?", normalise(email));
        if (id == null) throw new AppException("No user with that email.");
        return (Integer) id;
    }

    static String normalise(String email) {
        return email.trim().toLowerCase();
    }
}
