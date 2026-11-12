package b43;

import java.sql.SQLException;

/**
 * Friends and friend requests (FriendsWith, FriendRequest, RequestTargets).
 * Every query is for the logged-in user u only, so nobody else can see a
 * user's friends or requests.
 */
public final class FriendService {
    private final Db db;

    public FriendService(Db db) {
        this.db = db;
    }

    /** Query 1: the user's friends. A friendship is stored once, so union both columns. */
    public Db.Result friends(int u) throws SQLException {
        return db.query(
                "SELECT a.Email " +
                "FROM Account a, " +
                "     (SELECT UserB AS FriendId FROM FriendsWith WHERE UserA = ? " +
                "      UNION " +
                "      SELECT UserA AS FriendId FROM FriendsWith WHERE UserB = ?) F " +
                "WHERE a.UserId = F.FriendId " +
                "ORDER BY a.Email", u, u);
    }

    /** Query 2: incoming pending requests. */
    public Db.Result incoming(int u) throws SQLException {
        return db.query(
                "SELECT rt.RequestId, a.Email AS SentBy, fr.SentAt " +
                "FROM RequestTargets rt, FriendRequest fr, Account a " +
                "WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentBy " +
                "  AND rt.SentTo = ? AND fr.Status = 'pending' " +
                "ORDER BY fr.SentAt", u);
    }

    /** Outgoing requests that have not been answered yet. */
    public Db.Result outgoing(int u) throws SQLException {
        return db.query(
                "SELECT rt.RequestId, a.Email AS SentTo, fr.SentAt " +
                "FROM RequestTargets rt, FriendRequest fr, Account a " +
                "WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentTo " +
                "  AND rt.SentBy = ? AND fr.Status = 'pending' " +
                "ORDER BY fr.SentAt", u);
    }

    public boolean areFriends(int u, int v) throws SQLException {
        return db.exists("SELECT UserA FROM FriendsWith WHERE UserA = ? AND UserB = ?",
                Math.min(u, v), Math.max(u, v));
    }

    /** Sends a friend request from u to the user with this email. */
    public void send(int u, String email) throws SQLException {
        int t = AuthService.userIdOf(db, email);
        if (t == u) throw new AppException("You cannot send a friend request to yourself.");

        db.tx(() -> {
            if (areFriends(u, t)) throw new AppException("You are already friends.");

            // No duplicate requests: nothing pending in either direction
            if (db.exists(
                    "SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr " +
                    "WHERE rt.RequestId = fr.RequestId AND fr.Status = 'pending' " +
                    "  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?))",
                    u, t, t, u)) {
                throw new AppException("There is already a pending request between you and this user.");
            }

            // A rejected request (or a removed friend) can be re-sent only
            // 5 minutes after RespondedAt
            if (db.exists(
                    "SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr " +
                    "WHERE rt.RequestId = fr.RequestId AND fr.Status = 'rejected' " +
                    "  AND fr.RespondedAt > CURRENT_TIMESTAMP - INTERVAL '5 minutes' " +
                    "  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?))",
                    u, t, t, u)) {
                throw new AppException("A request between you was rejected (or the friendship was removed) "
                        + "less than 5 minutes ago. Please wait and try again.");
            }

            Object id = db.scalar("INSERT INTO FriendRequest(Status) VALUES ('pending') RETURNING RequestId");
            db.update("INSERT INTO RequestTargets(RequestId, SentBy, SentTo) VALUES (?, ?, ?)", id, u, t);
            return null;
        });
    }

    /** Accepts or rejects a pending request sent to u. */
    public void respond(int u, int requestId, boolean accept) throws SQLException {
        db.tx(() -> {
            Object sender = db.scalar(
                    "SELECT rt.SentBy FROM RequestTargets rt, FriendRequest fr " +
                    "WHERE rt.RequestId = fr.RequestId AND rt.RequestId = ? " +
                    "  AND rt.SentTo = ? AND fr.Status = 'pending'", requestId, u);
            if (sender == null) throw new AppException("No pending request with that ID was sent to you.");
            int s = (Integer) sender;

            db.update("UPDATE FriendRequest SET Status = ?, RespondedAt = CURRENT_TIMESTAMP " +
                      "WHERE RequestId = ?", accept ? "accepted" : "rejected", requestId);
            if (accept) {
                db.update("INSERT INTO FriendsWith(UserA, UserB) VALUES (?, ?)", Math.min(u, s), Math.max(u, s));
            }
            return null;
        });
    }

    /** Removes a friend. The request that made them friends becomes 'rejected' now,
     *  so the 5 minute limit also applies to deleted friends. */
    public void remove(int u, String email) throws SQLException {
        int f = AuthService.userIdOf(db, email);
        db.tx(() -> {
            int n = db.update("DELETE FROM FriendsWith WHERE UserA = ? AND UserB = ?",
                    Math.min(u, f), Math.max(u, f));
            if (n == 0) throw new AppException("You are not friends with that user.");

            db.update(
                    "UPDATE FriendRequest SET Status = 'rejected', RespondedAt = CURRENT_TIMESTAMP " +
                    "WHERE Status = 'accepted' AND RequestId IN " +
                    "  (SELECT RequestId FROM RequestTargets " +
                    "   WHERE (SentBy = ? AND SentTo = ?) OR (SentBy = ? AND SentTo = ?))",
                    u, f, f, u);
            return null;
        });
    }
}
