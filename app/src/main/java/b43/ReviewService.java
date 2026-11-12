package b43;

import java.sql.SQLException;

/**
 * Reviews of stock lists (Review, ReviewBy, ReviewFor).
 * A user writes at most one review per list they can access and can edit it.
 * Reviews of a public list are visible to everyone; otherwise a review is
 * visible only to its writer and the list's creator. The writer or the
 * list's creator can delete a review.
 */
public final class ReviewService {
    private final Db db;
    private final StockListService lists;

    public ReviewService(Db db, StockListService lists) {
        this.db = db;
        this.lists = lists;
    }

    /**
     * Query 5: the reviews on list l that user u may see.
     *   R        = sigma ListId=l ((ReviewFor |><| ReviewBy) |><| Review)   -- ReviewDetails view
     *   PublicR  = R |><| pi ListId (sigma Visibility='public' (StockList))
     *   OwnR     = sigma UserId=u (R)
     *   CreatorR = R |><| pi ListId (sigma UserId=u (Creates))
     *   Answer   = pi ReviewId, UserId, Content, LastEdited ((PublicR U OwnR) U CreatorR)
     * The answer is joined with Account to show the reviewer's email.
     */
    public Db.Result visible(int u, int l) throws SQLException {
        lists.requireAccess(u, l);
        return db.query(
                "SELECT Ans.ReviewId, a.Email AS Reviewer, Ans.LastEdited, Ans.Content " +
                "FROM Account a, " +
                "     ((SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited " +        // PublicR
                "       FROM ReviewDetails R, " +
                "            (SELECT ListId FROM StockList WHERE Visibility = 'public') P " +
                "       WHERE R.ListId = P.ListId AND R.ListId = ?) " +
                "      UNION " +
                "      (SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited " +        // OwnR
                "       FROM ReviewDetails R " +
                "       WHERE R.ListId = ? AND R.UserId = ?) " +
                "      UNION " +
                "      (SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited " +        // CreatorR
                "       FROM ReviewDetails R, " +
                "            (SELECT ListId FROM Creates WHERE UserId = ?) C " +
                "       WHERE R.ListId = C.ListId AND R.ListId = ?)) Ans " +
                "WHERE a.UserId = Ans.UserId " +
                "ORDER BY Ans.LastEdited DESC",
                l, l, u, u, l);
    }

    /** The user's review on a list, or null if they have none. */
    public Integer ownReview(int u, int l) throws SQLException {
        return (Integer) db.scalar(
                "SELECT rf.ReviewId FROM ReviewFor rf, ReviewBy rb " +
                "WHERE rf.ReviewId = rb.ReviewId AND rf.ListId = ? AND rb.UserId = ?", l, u);
    }

    public void write(int u, int l, String content) throws SQLException {
        lists.requireAccess(u, l);
        check(content);
        db.tx(() -> {
            if (ownReview(u, l) != null) throw new AppException("You have already reviewed this list.");
            Object id = db.scalar("INSERT INTO Review(Content) VALUES (?) RETURNING ReviewId", content);
            db.update("INSERT INTO ReviewBy(ReviewId, UserId) VALUES (?, ?)", id, u);
            db.update("INSERT INTO ReviewFor(ReviewId, ListId) VALUES (?, ?)", id, l);
            return null;
        });
    }

    public void edit(int u, int l, String content) throws SQLException {
        lists.requireAccess(u, l);
        check(content);
        Integer id = ownReview(u, l);
        if (id == null) throw new AppException("You have not reviewed this list yet.");
        db.update("UPDATE Review SET Content = ?, LastEdited = CURRENT_TIMESTAMP WHERE ReviewId = ?", content, id);
    }

    /** Deletes a review on list l if u wrote it or created the list. */
    public void delete(int u, int l, int reviewId) throws SQLException {
        lists.requireAccess(u, l);
        int n = db.update(
                "DELETE FROM Review " +
                "WHERE ReviewId = ? " +
                "  AND ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?) " +
                "  AND (ReviewId IN (SELECT ReviewId FROM ReviewBy WHERE UserId = ?) " +
                "       OR EXISTS (SELECT ListId FROM Creates WHERE ListId = ? AND UserId = ?))",
                reviewId, l, u, l, u);
        if (n == 0) throw new AppException("No review " + reviewId + " on this list that you can delete.");
    }

    private static void check(String content) {
        if (content.trim().isEmpty()) throw new AppException("A review cannot be empty.");
        if (content.length() > 4000) throw new AppException("A review can be at most 4000 characters.");
    }
}
