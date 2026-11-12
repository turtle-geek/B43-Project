-- =====================================================================
-- The SQL behind each function of the application, for the demo.
-- ? marks a parameter: u = logged-in UserId, p = PortfolioId, l = ListId.
-- The exact statements are in app/src/main/java/b43/*Service.java.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------
INSERT INTO Account(Email, Password) VALUES (?, ?) RETURNING UserId;      -- register
SELECT UserId FROM Account WHERE Email = ? AND Password = ?;              -- log in

-- ---------------------------------------------------------------------
-- Friends
-- ---------------------------------------------------------------------
-- Query 1: friends of u.  F1 U F2
SELECT a.Email
FROM Account a,
     (SELECT UserB AS FriendId FROM FriendsWith WHERE UserA = ?   -- u
      UNION
      SELECT UserA AS FriendId FROM FriendsWith WHERE UserB = ?) F -- u
WHERE a.UserId = F.FriendId;

-- Query 2: incoming requests (pending, sent to u)
SELECT rt.RequestId, a.Email AS SentBy, fr.SentAt
FROM RequestTargets rt, FriendRequest fr, Account a
WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentBy
  AND rt.SentTo = ? AND fr.Status = 'pending';

-- Outgoing requests not answered yet
SELECT rt.RequestId, a.Email AS SentTo, fr.SentAt
FROM RequestTargets rt, FriendRequest fr, Account a
WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentTo
  AND rt.SentBy = ? AND fr.Status = 'pending';

-- Send a request from u to t (one transaction). It is refused if:
--   they are already friends (UserA = smaller id, UserB = larger id)
SELECT UserA FROM FriendsWith WHERE UserA = ? AND UserB = ?;
--   a request is already pending in either direction
SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr
WHERE rt.RequestId = fr.RequestId AND fr.Status = 'pending'
  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?));
--   a request was rejected (or the friend removed) less than 5 minutes ago
SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr
WHERE rt.RequestId = fr.RequestId AND fr.Status = 'rejected'
  AND fr.RespondedAt > CURRENT_TIMESTAMP - INTERVAL '5 minutes'
  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?));
-- otherwise
INSERT INTO FriendRequest(Status) VALUES ('pending') RETURNING RequestId;
INSERT INTO RequestTargets(RequestId, SentBy, SentTo) VALUES (?, ?, ?);

-- Accept (reject is the same UPDATE with 'rejected' and no INSERT)
UPDATE FriendRequest SET Status = 'accepted', RespondedAt = CURRENT_TIMESTAMP WHERE RequestId = ?;
INSERT INTO FriendsWith(UserA, UserB) VALUES (?, ?);

-- Remove a friend: the request that made them friends becomes 'rejected'
-- now, so the 5 minute limit applies to the removed friend too
DELETE FROM FriendsWith WHERE UserA = ? AND UserB = ?;
UPDATE FriendRequest SET Status = 'rejected', RespondedAt = CURRENT_TIMESTAMP
WHERE Status = 'accepted' AND RequestId IN
      (SELECT RequestId FROM RequestTargets
       WHERE (SentBy = ? AND SentTo = ?) OR (SentBy = ? AND SentTo = ?));

-- ---------------------------------------------------------------------
-- Portfolios (every statement first checks that u owns p)
-- ---------------------------------------------------------------------
SELECT p.Name FROM Portfolio p, OwnsPortfolio o
WHERE p.PortfolioId = o.PortfolioId AND p.PortfolioId = ? AND o.UserId = ?;

-- Create
INSERT INTO Portfolio(Name, Cash) VALUES (?, 0) RETURNING PortfolioId;
INSERT INTO OwnsPortfolio(PortfolioId, UserId) VALUES (?, ?);

-- My portfolios with cash and stock value
SELECT p.PortfolioId, p.Name, p.Cash, SUM(h.Shares * lp.Close) AS StockValue
FROM Portfolio p
JOIN OwnsPortfolio o ON o.PortfolioId = p.PortfolioId
LEFT OUTER JOIN Holds h ON h.PortfolioId = p.PortfolioId
LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol
WHERE o.UserId = ?
GROUP BY p.PortfolioId, p.Name, p.Cash;

-- Query 4: market value of each holding, the stock total, and the cash.
-- LatestPrice (schema.sql) is Latest and Price from the relational algebra.
SELECT h.Symbol, h.Shares, lp.LastTime, lp.Close, h.Shares * lp.Close AS MarketValue
FROM Holds h LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol
WHERE h.PortfolioId = ?;
SELECT SUM(h.Shares * lp.Close) FROM Holds h, LatestPrice lp
WHERE h.Symbol = lp.Symbol AND h.PortfolioId = ?;                         -- StockTotal
SELECT Cash FROM Portfolio WHERE PortfolioId = ?;                        -- PortfolioCash

-- Deposit / withdraw
UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?;
UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?;
-- Transfer = withdraw from one portfolio + deposit into the other, in one transaction

-- Buy n shares at the latest close (one transaction)
SELECT Close FROM LatestPrice WHERE Symbol = ?;
UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?;
UPDATE Holds SET Shares = Shares + ? WHERE PortfolioId = ? AND Symbol = ?;
INSERT INTO Holds(Symbol, PortfolioId, Shares) VALUES (?, ?, ?);           -- if nothing was updated

-- Sell n shares at the latest close (one transaction)
UPDATE Holds SET Shares = Shares - ? WHERE PortfolioId = ? AND Symbol = ? AND Shares > ?;
DELETE FROM Holds WHERE PortfolioId = ? AND Symbol = ? AND Shares = ?;    -- when selling all shares
UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?;

-- ---------------------------------------------------------------------
-- Stock data
-- ---------------------------------------------------------------------
-- Past prices over an interval (historic + new data through the view)
SELECT Timestamp, Open, High, Low, Close, Volume
FROM StockRecord
WHERE Symbol = ? AND Timestamp >= ? AND Timestamp <= ?
ORDER BY Timestamp;

-- Record new daily data (historic data is read-only)
SELECT Symbol FROM StockHistory WHERE Symbol = ? AND Timestamp = ?;      -- refuse if found
UPDATE StockDaily SET Open = ?, High = ?, Low = ?, Close = ?, Volume = ?
WHERE Symbol = ? AND Timestamp = ?;
INSERT INTO StockDaily(Symbol, Timestamp, Open, High, Low, Close, Volume)
VALUES (?, ?, ?, ?, ?, ?, ?);                                            -- if nothing was updated

-- Prediction: least-squares line through the last year of closes.
-- x = days before the latest record; slope and intercept come from these sums.
SELECT COUNT(*), SUM(x), SUM(Close), SUM(x * x), SUM(x * Close)
FROM (SELECT Timestamp - CAST(? AS DATE) AS x, Close
      FROM StockRecord
      WHERE Symbol = ? AND Close IS NOT NULL AND Timestamp > CAST(? AS DATE) - 365) Pts;

-- ---------------------------------------------------------------------
-- Statistics of a portfolio or stock list over [start, end]
-- (for a list, replace the subquery with SELECT Symbol FROM Contains WHERE ListId = ?)
-- Var(X) = AVG(X*X) - AVG(X)^2,  Cov(X,Y) = AVG(X*Y) - AVG(X)*AVG(Y)
-- COV = sqrt(Var) / Mean,  Beta = Cov(stock, market) / Var(market),
-- Correlation = Cov(A,B) / sqrt(Var(A) * Var(B))
-- ---------------------------------------------------------------------
SELECT s.Symbol, COUNT(*) AS Days, AVG(s.Close) AS Mean,
       AVG(s.Close * s.Close) - AVG(s.Close) * AVG(s.Close) AS Var,
       AVG(s.Close * m.MarketClose) - AVG(s.Close) * AVG(m.MarketClose) AS CovMarket,
       AVG(m.MarketClose * m.MarketClose) - AVG(m.MarketClose) * AVG(m.MarketClose) AS VarMarket
FROM StockRecord s, MarketDaily m
WHERE s.Timestamp = m.Timestamp
  AND s.Symbol IN (SELECT Symbol FROM Holds WHERE PortfolioId = ?)
  AND s.Timestamp >= ? AND s.Timestamp <= ?
  AND m.Timestamp >= ? AND m.Timestamp <= ?
  AND s.Close IS NOT NULL
GROUP BY s.Symbol;

SELECT a.Symbol, b.Symbol,
       AVG(a.Close * b.Close) - AVG(a.Close) * AVG(b.Close) AS Cov,
       AVG(a.Close * a.Close) - AVG(a.Close) * AVG(a.Close) AS VarA,
       AVG(b.Close * b.Close) - AVG(b.Close) * AVG(b.Close) AS VarB
FROM StockRecord a, StockRecord b
WHERE a.Timestamp = b.Timestamp AND a.Symbol <= b.Symbol
  AND a.Symbol IN (SELECT Symbol FROM Holds WHERE PortfolioId = ?)
  AND b.Symbol IN (SELECT Symbol FROM Holds WHERE PortfolioId = ?)
  AND a.Timestamp >= ? AND a.Timestamp <= ?
  AND a.Close IS NOT NULL AND b.Close IS NOT NULL
GROUP BY a.Symbol, b.Symbol;

-- ---------------------------------------------------------------------
-- Stock lists
-- ---------------------------------------------------------------------
-- Query 3: lists u can see.  StockList |><| ((Own U Shared) U Public)
SELECT sl.ListId, sl.Name, sl.Visibility, a.Email AS Creator
FROM StockList sl, Creates c, Account a,
     (SELECT ListId FROM Creates WHERE UserId = ?
      UNION
      SELECT ListId FROM SharedWith WHERE UserId = ?
      UNION
      SELECT ListId FROM StockList WHERE Visibility = 'public') Acc
WHERE sl.ListId = Acc.ListId AND c.ListId = sl.ListId AND a.UserId = c.UserId;

-- Create
INSERT INTO StockList(Name, Visibility) VALUES (?, 'private') RETURNING ListId;
INSERT INTO Creates(ListId, UserId) VALUES (?, ?);

-- Contents with market value
SELECT c.Symbol, c.Shares, lp.Close, c.Shares * lp.Close AS MarketValue
FROM Contains c LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = c.Symbol
WHERE c.ListId = ?;

-- Add a stock / change its shares (creator only)
UPDATE Contains SET Shares = ? WHERE ListId = ? AND Symbol = ?;
INSERT INTO Contains(Symbol, ListId, Shares) VALUES (?, ?, ?);           -- if nothing was updated
DELETE FROM Contains WHERE ListId = ? AND Symbol = ?;                     -- remove a stock

-- Share with a friend / make public (creator only)
INSERT INTO SharedWith(ListId, UserId) VALUES (?, ?);
UPDATE StockList SET Visibility = 'shared' WHERE ListId = ? AND Visibility = 'private';
UPDATE StockList SET Visibility = 'public' WHERE ListId = ?;

-- Delete a list and its reviews (one transaction); Contains, Creates,
-- SharedWith and ReviewFor rows go with ON DELETE CASCADE
DELETE FROM Review WHERE ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?);
DELETE FROM StockList WHERE ListId = ?;

-- ---------------------------------------------------------------------
-- Reviews
-- ---------------------------------------------------------------------
-- Query 5: reviews on list l that u may see.
--   R        = sigma ListId=l ((ReviewFor |><| ReviewBy) |><| Review)  -- ReviewDetails view
--   PublicR  = R |><| pi ListId (sigma Visibility='public' (StockList))
--   OwnR     = sigma UserId=u (R)
--   CreatorR = R |><| pi ListId (sigma UserId=u (Creates))
--   Answer   = pi ReviewId, UserId, Content, LastEdited ((PublicR U OwnR) U CreatorR)
-- (the app also joins Answer with Account to show the reviewer's email)
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited                    -- PublicR
 FROM ReviewDetails R,
      (SELECT ListId FROM StockList WHERE Visibility = 'public') P
 WHERE R.ListId = P.ListId AND R.ListId = ?)                             -- l
UNION
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited                    -- OwnR
 FROM ReviewDetails R
 WHERE R.ListId = ? AND R.UserId = ?)                                    -- l, u
UNION
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited                    -- CreatorR
 FROM ReviewDetails R,
      (SELECT ListId FROM Creates WHERE UserId = ?) C                    -- u
 WHERE R.ListId = C.ListId AND R.ListId = ?);                            -- l

-- Write (one transaction, only if u has no review on l yet)
SELECT rf.ReviewId FROM ReviewFor rf, ReviewBy rb
WHERE rf.ReviewId = rb.ReviewId AND rf.ListId = ? AND rb.UserId = ?;
INSERT INTO Review(Content) VALUES (?) RETURNING ReviewId;
INSERT INTO ReviewBy(ReviewId, UserId) VALUES (?, ?);
INSERT INTO ReviewFor(ReviewId, ListId) VALUES (?, ?);

-- Edit
UPDATE Review SET Content = ?, LastEdited = CURRENT_TIMESTAMP WHERE ReviewId = ?;

-- Delete (by the reviewer or the list's creator)
DELETE FROM Review
WHERE ReviewId = ?
  AND ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?)
  AND (ReviewId IN (SELECT ReviewId FROM ReviewBy WHERE UserId = ?)
       OR EXISTS (SELECT ListId FROM Creates WHERE ListId = ? AND UserId = ?));
