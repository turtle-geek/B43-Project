-- CSCC43 project schema
DROP VIEW  IF EXISTS ReviewDetails, MarketDaily, LatestPrice, StockRecord CASCADE;
DROP TABLE IF EXISTS ReviewFor, ReviewBy, Review,
                     SharedWith, Contains, Creates, StockList,
                     Holds, OwnsPortfolio, Portfolio,
                     StockDaily, StockHistory, Stock,
                     FriendsWith, RequestTargets, FriendRequest,
                     Account CASCADE;

CREATE TABLE Account (
    UserId   SERIAL PRIMARY KEY,
    Email    VARCHAR(255) NOT NULL UNIQUE,
    Password VARCHAR(255) NOT NULL
);

CREATE TABLE FriendRequest (
    RequestId   SERIAL PRIMARY KEY,
    SentAt      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    RespondedAt TIMESTAMP,
    Status      VARCHAR(10) NOT NULL DEFAULT 'pending'
                CHECK (Status IN ('pending', 'accepted', 'rejected'))
);

CREATE TABLE Review (
    ReviewId   SERIAL PRIMARY KEY,
    Content    VARCHAR(4000) NOT NULL,
    LastEdited TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE StockList (
    ListId     SERIAL PRIMARY KEY,
    Name       VARCHAR(100) NOT NULL,
    Visibility VARCHAR(10) NOT NULL DEFAULT 'private'
               CHECK (Visibility IN ('private', 'shared', 'public'))
);

CREATE TABLE Portfolio (
    PortfolioId SERIAL PRIMARY KEY,
    Name        VARCHAR(100) NOT NULL,
    Cash        NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (Cash >= 0)
);

CREATE TABLE Stock (
    Symbol VARCHAR(5) PRIMARY KEY
);

-- StockRecord is split into historic data (read-only) and new data, joined by the view below
CREATE TABLE StockHistory (
    Symbol    VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    Timestamp DATE NOT NULL,
    Open      FLOAT,
    High      FLOAT,
    Low       FLOAT,
    Close     FLOAT,
    Volume    BIGINT,
    PRIMARY KEY (Symbol, Timestamp)
);

CREATE TABLE StockDaily (
    Symbol    VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    Timestamp DATE NOT NULL,
    Open      FLOAT,
    High      FLOAT,
    Low       FLOAT,
    Close     FLOAT NOT NULL CHECK (Close > 0),
    Volume    BIGINT,
    PRIMARY KEY (Symbol, Timestamp)
);

CREATE VIEW StockRecord AS
    (SELECT Symbol, Timestamp, Open, High, Low, Close, Volume FROM StockHistory)
    UNION ALL
    (SELECT Symbol, Timestamp, Open, High, Low, Close, Volume FROM StockDaily);

-- Latest and Price from Query 4
CREATE VIEW LatestPrice AS
    SELECT s.Symbol, s.Timestamp AS LastTime, s.Close
    FROM StockRecord s,
         (SELECT Symbol, MAX(Timestamp) AS LastTime
          FROM StockRecord
          GROUP BY Symbol) Latest
    WHERE s.Symbol = Latest.Symbol AND s.Timestamp = Latest.LastTime;

-- average close of all stocks per day, used for Beta
CREATE VIEW MarketDaily AS
    SELECT Timestamp, AVG(Close) AS MarketClose
    FROM StockRecord
    GROUP BY Timestamp;

-- each friendship stored once with UserA < UserB
CREATE TABLE FriendsWith (
    UserA INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    UserB INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    PRIMARY KEY (UserA, UserB),
    CHECK (UserA < UserB)
);

CREATE TABLE RequestTargets (
    RequestId INT PRIMARY KEY REFERENCES FriendRequest(RequestId) ON DELETE CASCADE,
    SentBy    INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    SentTo    INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    CHECK (SentBy <> SentTo)
);

CREATE TABLE OwnsPortfolio (
    PortfolioId INT PRIMARY KEY REFERENCES Portfolio(PortfolioId) ON DELETE CASCADE,
    UserId      INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

CREATE TABLE Holds (
    Symbol      VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    PortfolioId INT NOT NULL REFERENCES Portfolio(PortfolioId) ON DELETE CASCADE,
    Shares      INT NOT NULL CHECK (Shares > 0),
    PRIMARY KEY (Symbol, PortfolioId)
);

CREATE TABLE Creates (
    ListId INT PRIMARY KEY REFERENCES StockList(ListId) ON DELETE CASCADE,
    UserId INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

CREATE TABLE SharedWith (
    ListId INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE,
    UserId INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    PRIMARY KEY (ListId, UserId)
);

CREATE TABLE Contains (
    Symbol VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    ListId INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE,
    Shares INT NOT NULL CHECK (Shares > 0),
    PRIMARY KEY (Symbol, ListId)
);

CREATE TABLE ReviewBy (
    ReviewId INT PRIMARY KEY REFERENCES Review(ReviewId) ON DELETE CASCADE,
    UserId   INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

CREATE TABLE ReviewFor (
    ReviewId INT PRIMARY KEY REFERENCES Review(ReviewId) ON DELETE CASCADE,
    ListId   INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE
);

-- R from Query 5
CREATE VIEW ReviewDetails AS
    SELECT rf.ReviewId, rf.ListId, rb.UserId, rv.Content, rv.LastEdited
    FROM ReviewFor rf, ReviewBy rb, Review rv
    WHERE rf.ReviewId = rb.ReviewId AND rf.ReviewId = rv.ReviewId;

-- indices
CREATE INDEX FriendsWithUserB  ON FriendsWith(UserB);
CREATE INDEX RequestSentTo     ON RequestTargets(SentTo);
CREATE INDEX OwnsPortfolioUser ON OwnsPortfolio(UserId);
CREATE INDEX HoldsPortfolio    ON Holds(PortfolioId);
CREATE INDEX CreatesUser       ON Creates(UserId);
CREATE INDEX SharedWithUser    ON SharedWith(UserId);
CREATE INDEX ContainsList      ON Contains(ListId);
CREATE INDEX ReviewForList     ON ReviewFor(ListId);
CREATE INDEX StockHistoryDate  ON StockHistory(Timestamp);
