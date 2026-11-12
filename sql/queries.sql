-- SQL used by each function (? = parameter)

-- Register / log in
INSERT INTO Account(Email, Password) VALUES (?, ?) RETURNING UserId;
SELECT UserId FROM Account WHERE Email = ? AND Password = ?;

-- Query 1: F1 ∪ F2
SELECT a.Email
FROM Account a,
     (SELECT UserB AS FriendId FROM FriendsWith WHERE UserA = ?
      UNION
      SELECT UserA AS FriendId FROM FriendsWith WHERE UserB = ?) F
WHERE a.UserId = F.FriendId;

-- Query 2: πRequestId, SentBy, SentAt(σSentTo='u' AND Status='pending'(RequestTargets ⋈ FriendRequest))
SELECT rt.RequestId, a.Email AS SentBy, fr.SentAt
FROM RequestTargets rt, FriendRequest fr, Account a
WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentBy
  AND rt.SentTo = ? AND fr.Status = 'pending';

-- Outgoing requests
SELECT rt.RequestId, a.Email AS SentTo, fr.SentAt
FROM RequestTargets rt, FriendRequest fr, Account a
WHERE rt.RequestId = fr.RequestId AND a.UserId = rt.SentTo
  AND rt.SentBy = ? AND fr.Status = 'pending';

-- Send request: refuse if already friends, a request is pending,
-- or a request was rejected less than 5 minutes ago
SELECT UserA FROM FriendsWith WHERE UserA = ? AND UserB = ?;
SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr
WHERE rt.RequestId = fr.RequestId AND fr.Status = 'pending'
  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?));
SELECT rt.RequestId FROM RequestTargets rt, FriendRequest fr
WHERE rt.RequestId = fr.RequestId AND fr.Status = 'rejected'
  AND fr.RespondedAt > CURRENT_TIMESTAMP - INTERVAL '5 minutes'
  AND ((rt.SentBy = ? AND rt.SentTo = ?) OR (rt.SentBy = ? AND rt.SentTo = ?));
INSERT INTO FriendRequest(Status) VALUES ('pending') RETURNING RequestId;
INSERT INTO RequestTargets(RequestId, SentBy, SentTo) VALUES (?, ?, ?);

-- Accept / reject
UPDATE FriendRequest SET Status = 'accepted', RespondedAt = CURRENT_TIMESTAMP WHERE RequestId = ?;
INSERT INTO FriendsWith(UserA, UserB) VALUES (?, ?);

-- Remove friend (starts the 5 minute wait)
DELETE FROM FriendsWith WHERE UserA = ? AND UserB = ?;
UPDATE FriendRequest SET Status = 'rejected', RespondedAt = CURRENT_TIMESTAMP
WHERE Status = 'accepted' AND RequestId IN
      (SELECT RequestId FROM RequestTargets
       WHERE (SentBy = ? AND SentTo = ?) OR (SentBy = ? AND SentTo = ?));

-- Portfolio ownership check
SELECT p.Name FROM Portfolio p, OwnsPortfolio o
WHERE p.PortfolioId = o.PortfolioId AND p.PortfolioId = ? AND o.UserId = ?;

-- Create portfolio
INSERT INTO Portfolio(Name, Cash) VALUES (?, 0) RETURNING PortfolioId;
INSERT INTO OwnsPortfolio(PortfolioId, UserId) VALUES (?, ?);

-- My portfolios
SELECT p.PortfolioId, p.Name, p.Cash, SUM(h.Shares * lp.Close) AS StockValue
FROM Portfolio p
JOIN OwnsPortfolio o ON o.PortfolioId = p.PortfolioId
LEFT OUTER JOIN Holds h ON h.PortfolioId = p.PortfolioId
LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol
WHERE o.UserId = ?
GROUP BY p.PortfolioId, p.Name, p.Cash;

-- Query 4: Value, StockTotal, PortfolioCash
SELECT h.Symbol, h.Shares, lp.LastTime, lp.Close, h.Shares * lp.Close AS MarketValue
FROM Holds h LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = h.Symbol
WHERE h.PortfolioId = ?;
SELECT SUM(h.Shares * lp.Close) FROM Holds h, LatestPrice lp
WHERE h.Symbol = lp.Symbol AND h.PortfolioId = ?;
SELECT Cash FROM Portfolio WHERE PortfolioId = ?;

-- Deposit / withdraw
UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?;
UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?;

-- Buy
SELECT Close FROM LatestPrice WHERE Symbol = ?;
UPDATE Portfolio SET Cash = Cash - ? WHERE PortfolioId = ? AND Cash >= ?;
UPDATE Holds SET Shares = Shares + ? WHERE PortfolioId = ? AND Symbol = ?;
INSERT INTO Holds(Symbol, PortfolioId, Shares) VALUES (?, ?, ?);

-- Sell
UPDATE Holds SET Shares = Shares - ? WHERE PortfolioId = ? AND Symbol = ? AND Shares > ?;
DELETE FROM Holds WHERE PortfolioId = ? AND Symbol = ? AND Shares = ?;
UPDATE Portfolio SET Cash = Cash + ? WHERE PortfolioId = ?;

-- Past prices
SELECT Timestamp, Open, High, Low, Close, Volume
FROM StockRecord
WHERE Symbol = ? AND Timestamp >= ? AND Timestamp <= ?
ORDER BY Timestamp;

-- Record new daily data
SELECT Symbol FROM StockHistory WHERE Symbol = ? AND Timestamp = ?;
UPDATE StockDaily SET Open = ?, High = ?, Low = ?, Close = ?, Volume = ?
WHERE Symbol = ? AND Timestamp = ?;
INSERT INTO StockDaily(Symbol, Timestamp, Open, High, Low, Close, Volume)
VALUES (?, ?, ?, ?, ?, ?, ?);

-- Prediction: sums for the least squares line
SELECT COUNT(*), SUM(x), SUM(Close), SUM(x * x), SUM(x * Close)
FROM (SELECT Timestamp - CAST(? AS DATE) AS x, Close
      FROM StockRecord
      WHERE Symbol = ? AND Close IS NOT NULL AND Timestamp > CAST(? AS DATE) - 365) Pts;

-- Statistics: COV and Beta
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

-- Statistics: covariance / correlation matrix
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

-- Query 3: StockList ⋈ ((Own ∪ Shared) ∪ Public)
SELECT sl.ListId, sl.Name, sl.Visibility, a.Email AS Creator
FROM StockList sl, Creates c, Account a,
     (SELECT ListId FROM Creates WHERE UserId = ?
      UNION
      SELECT ListId FROM SharedWith WHERE UserId = ?
      UNION
      SELECT ListId FROM StockList WHERE Visibility = 'public') Acc
WHERE sl.ListId = Acc.ListId AND c.ListId = sl.ListId AND a.UserId = c.UserId;

-- Create list
INSERT INTO StockList(Name, Visibility) VALUES (?, 'private') RETURNING ListId;
INSERT INTO Creates(ListId, UserId) VALUES (?, ?);

-- List contents
SELECT c.Symbol, c.Shares, lp.Close, c.Shares * lp.Close AS MarketValue
FROM Contains c LEFT OUTER JOIN LatestPrice lp ON lp.Symbol = c.Symbol
WHERE c.ListId = ?;

-- Add / change / remove stock
UPDATE Contains SET Shares = ? WHERE ListId = ? AND Symbol = ?;
INSERT INTO Contains(Symbol, ListId, Shares) VALUES (?, ?, ?);
DELETE FROM Contains WHERE ListId = ? AND Symbol = ?;

-- Share / make public
INSERT INTO SharedWith(ListId, UserId) VALUES (?, ?);
UPDATE StockList SET Visibility = 'shared' WHERE ListId = ? AND Visibility = 'private';
UPDATE StockList SET Visibility = 'public' WHERE ListId = ?;

-- Delete list and its reviews
DELETE FROM Review WHERE ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?);
DELETE FROM StockList WHERE ListId = ?;

-- Query 5: (PublicR ∪ OwnR) ∪ CreatorR, R = ReviewDetails view
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited
 FROM ReviewDetails R,
      (SELECT ListId FROM StockList WHERE Visibility = 'public') P
 WHERE R.ListId = P.ListId AND R.ListId = ?)
UNION
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited
 FROM ReviewDetails R
 WHERE R.ListId = ? AND R.UserId = ?)
UNION
(SELECT R.ReviewId, R.UserId, R.Content, R.LastEdited
 FROM ReviewDetails R,
      (SELECT ListId FROM Creates WHERE UserId = ?) C
 WHERE R.ListId = C.ListId AND R.ListId = ?);

-- Write review
SELECT rf.ReviewId FROM ReviewFor rf, ReviewBy rb
WHERE rf.ReviewId = rb.ReviewId AND rf.ListId = ? AND rb.UserId = ?;
INSERT INTO Review(Content) VALUES (?) RETURNING ReviewId;
INSERT INTO ReviewBy(ReviewId, UserId) VALUES (?, ?);
INSERT INTO ReviewFor(ReviewId, ListId) VALUES (?, ?);

-- Edit review
UPDATE Review SET Content = ?, LastEdited = CURRENT_TIMESTAMP WHERE ReviewId = ?;

-- Delete review
DELETE FROM Review
WHERE ReviewId = ?
  AND ReviewId IN (SELECT ReviewId FROM ReviewFor WHERE ListId = ?)
  AND (ReviewId IN (SELECT ReviewId FROM ReviewBy WHERE UserId = ?)
       OR EXISTS (SELECT ListId FROM Creates WHERE ListId = ? AND UserId = ?));
