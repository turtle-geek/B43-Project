-- =====================================================================
-- CSCC43 Project: Social Network for Stocks -- Database schema
--
-- One table per entity and relationship of the design in project_tasks.pdf
-- (Part 2). Every relation is in BCNF (Part 3).
--
-- The only addition: StockRecord is a VIEW over two tables, because the
-- spec requires the historic data to be read-only and the stock data to be
-- the union of the historic data and the new data users enter.
--     StockHistory  - historic data loaded from SP500History.csv
--     StockDaily    - new daily data recorded in the application
-- =====================================================================

DROP VIEW  IF EXISTS ReviewDetails, MarketDaily, LatestPrice, StockRecord CASCADE;
DROP TABLE IF EXISTS ReviewFor, ReviewBy, Review,
                     SharedWith, Contains, Creates, StockList,
                     Holds, OwnsPortfolio, Portfolio,
                     StockDaily, StockHistory, Stock,
                     FriendsWith, RequestTargets, FriendRequest,
                     Account CASCADE;

-- ---------------------------------------------------------------------
-- Entities
-- ---------------------------------------------------------------------

-- Account(UserId, Email, Password); Email is also a key
CREATE TABLE Account (
    UserId   SERIAL PRIMARY KEY,
    Email    VARCHAR(255) NOT NULL UNIQUE,
    Password VARCHAR(255) NOT NULL
);

-- FriendRequest(RequestId, SentAt, RespondedAt, Status)
CREATE TABLE FriendRequest (
    RequestId   SERIAL PRIMARY KEY,
    SentAt      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    RespondedAt TIMESTAMP,
    Status      VARCHAR(10) NOT NULL DEFAULT 'pending'
                CHECK (Status IN ('pending', 'accepted', 'rejected'))
);

-- Review(ReviewId, Content, LastEdited); at most 4000 characters
CREATE TABLE Review (
    ReviewId   SERIAL PRIMARY KEY,
    Content    VARCHAR(4000) NOT NULL,
    LastEdited TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- StockList(ListId, Name, Visibility)
CREATE TABLE StockList (
    ListId     SERIAL PRIMARY KEY,
    Name       VARCHAR(100) NOT NULL,
    Visibility VARCHAR(10) NOT NULL DEFAULT 'private'
               CHECK (Visibility IN ('private', 'shared', 'public'))
);

-- Portfolio(PortfolioId, Name, Cash)
CREATE TABLE Portfolio (
    PortfolioId SERIAL PRIMARY KEY,
    Name        VARCHAR(100) NOT NULL,
    Cash        NUMERIC(15,2) NOT NULL DEFAULT 0 CHECK (Cash >= 0)
);

-- Stock(Symbol)
CREATE TABLE Stock (
    Symbol VARCHAR(5) PRIMARY KEY
);

-- StockRecord(Symbol, Timestamp, Open, Close, High, Low, Volume)
-- weak entity of Stock: one record per stock per day
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

-- Unified view of all stock information
CREATE VIEW StockRecord AS
    (SELECT Symbol, Timestamp, Open, High, Low, Close, Volume FROM StockHistory)
    UNION ALL
    (SELECT Symbol, Timestamp, Open, High, Low, Close, Volume FROM StockDaily);

-- Present value of each stock: the Close on its latest Timestamp
-- (Latest and Price from Query 4 of project_tasks.pdf)
CREATE VIEW LatestPrice AS
    SELECT s.Symbol, s.Timestamp AS LastTime, s.Close
    FROM StockRecord s,
         (SELECT Symbol, MAX(Timestamp) AS LastTime
          FROM StockRecord
          GROUP BY Symbol) Latest
    WHERE s.Symbol = Latest.Symbol AND s.Timestamp = Latest.LastTime;

-- The market as a whole on each day: the average close of all stocks.
-- Used to compute Beta.
CREATE VIEW MarketDaily AS
    SELECT Timestamp, AVG(Close) AS MarketClose
    FROM StockRecord
    GROUP BY Timestamp;

-- ---------------------------------------------------------------------
-- Relationships
-- ---------------------------------------------------------------------

-- FriendsWith(UserA, UserB): stored once with UserA < UserB
CREATE TABLE FriendsWith (
    UserA INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    UserB INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    PRIMARY KEY (UserA, UserB),
    CHECK (UserA < UserB)
);

-- RequestTargets(RequestId, SentBy, SentTo)
CREATE TABLE RequestTargets (
    RequestId INT PRIMARY KEY REFERENCES FriendRequest(RequestId) ON DELETE CASCADE,
    SentBy    INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    SentTo    INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    CHECK (SentBy <> SentTo)
);

-- OwnsPortfolio(PortfolioId, UserId)
CREATE TABLE OwnsPortfolio (
    PortfolioId INT PRIMARY KEY REFERENCES Portfolio(PortfolioId) ON DELETE CASCADE,
    UserId      INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

-- Holds(Symbol, PortfolioId, Shares)
CREATE TABLE Holds (
    Symbol      VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    PortfolioId INT NOT NULL REFERENCES Portfolio(PortfolioId) ON DELETE CASCADE,
    Shares      INT NOT NULL CHECK (Shares > 0),
    PRIMARY KEY (Symbol, PortfolioId)
);

-- Creates(ListId, UserId)
CREATE TABLE Creates (
    ListId INT PRIMARY KEY REFERENCES StockList(ListId) ON DELETE CASCADE,
    UserId INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

-- SharedWith(ListId, UserId)
CREATE TABLE SharedWith (
    ListId INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE,
    UserId INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE,
    PRIMARY KEY (ListId, UserId)
);

-- Contains(Symbol, ListId, Shares)
CREATE TABLE Contains (
    Symbol VARCHAR(5) NOT NULL REFERENCES Stock(Symbol),
    ListId INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE,
    Shares INT NOT NULL CHECK (Shares > 0),
    PRIMARY KEY (Symbol, ListId)
);

-- ReviewBy(ReviewId, UserId)
CREATE TABLE ReviewBy (
    ReviewId INT PRIMARY KEY REFERENCES Review(ReviewId) ON DELETE CASCADE,
    UserId   INT NOT NULL REFERENCES Account(UserId) ON DELETE CASCADE
);

-- ReviewFor(ReviewId, ListId)
CREATE TABLE ReviewFor (
    ReviewId INT PRIMARY KEY REFERENCES Review(ReviewId) ON DELETE CASCADE,
    ListId   INT NOT NULL REFERENCES StockList(ListId) ON DELETE CASCADE
);

-- R from Query 5: each review with its list, writer, content and date
-- (ReviewFor |><| ReviewBy) |><| Review
CREATE VIEW ReviewDetails AS
    SELECT rf.ReviewId, rf.ListId, rb.UserId, rv.Content, rv.LastEdited
    FROM ReviewFor rf, ReviewBy rb, Review rv
    WHERE rf.ReviewId = rb.ReviewId AND rf.ReviewId = rv.ReviewId;

-- ---------------------------------------------------------------------
-- Indices on foreign keys that queries search by
-- (primary keys are indexed automatically)
-- ---------------------------------------------------------------------
CREATE INDEX FriendsWithUserB  ON FriendsWith(UserB);
CREATE INDEX RequestSentTo     ON RequestTargets(SentTo);
CREATE INDEX OwnsPortfolioUser ON OwnsPortfolio(UserId);
CREATE INDEX HoldsPortfolio    ON Holds(PortfolioId);
CREATE INDEX CreatesUser       ON Creates(UserId);
CREATE INDEX SharedWithUser    ON SharedWith(UserId);
CREATE INDEX ContainsList      ON Contains(ListId);
CREATE INDEX ReviewForList     ON ReviewFor(ListId);
CREATE INDEX StockHistoryDate  ON StockHistory(Timestamp);
