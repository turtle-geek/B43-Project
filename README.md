# CSCC43 Project - Social Network for Stocks

Command line app in Java with PostgreSQL. The schema follows the ER diagram and relations in `project_tasks.pdf`.

## Files

- `sql/schema.sql` - tables, constraints, views, indices
- `sql/load_data.sql` - loads `SP500History.csv`
- `sql/queries.sql` - SQL used for each function
- `app/` - Java source (Maven)

## Setup

Run as the postgres user on the VM:

```
./setup_db.sh mydb /data/SP500History.csv
./BuildAndRun.sh
```

Default connection is `localhost:5432/mydb` with user `postgres` and password `postgres`. Change it with `DB_URL`, `DB_USER`, `DB_PASSWORD`.

## Notes

- Each entity and relationship from Part 2 is its own table, all in BCNF.
- `StockRecord` is a view: historic data (`StockHistory`, read-only) union new data (`StockDaily`).
- `LatestPrice` view gives the last close of each stock (Latest and Price from Query 4).
- `MarketDaily` view is the average close of all stocks per day, used as the market for Beta.
- `ReviewDetails` view is R from Query 5.
- A friendship is stored once with UserA < UserB.
- A rejected request, or a removed friend, has to wait 5 minutes from `RespondedAt` before a new request.
- Statistics (COV, Beta, covariance and correlation) use daily close prices over any interval. They are computed with AVG in SQL and cached in memory.
- Prediction fits a straight line to the last year of closes.
