-- =====================================================================
-- Load SP500History.csv into Stock and StockHistory.
--   psql -d mydb -v csv=/data/SP500History.csv -f sql/load_data.sql
-- CSV columns: Timestamp,Open,High,Low,Close,Volume,Code
-- =====================================================================

\set ON_ERROR_STOP on

-- Staging table in the same column order as the CSV file
CREATE TEMP TABLE StageHistory (
    Timestamp DATE,
    Open      FLOAT,
    High      FLOAT,
    Low       FLOAT,
    Close     FLOAT,
    Volume    BIGINT,
    Symbol    VARCHAR(5)
);

\copy StageHistory FROM :'csv' DELIMITER ',' CSV HEADER

INSERT INTO Stock(Symbol)
SELECT DISTINCT Symbol FROM StageHistory;

INSERT INTO StockHistory(Symbol, Timestamp, Open, High, Low, Close, Volume)
SELECT Symbol, Timestamp, Open, High, Low, Close, Volume FROM StageHistory;

ANALYZE;

SELECT COUNT(*) AS stocks  FROM Stock;
SELECT COUNT(*) AS records FROM StockHistory;
