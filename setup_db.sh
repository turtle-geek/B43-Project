#!/usr/bin/env bash
# Creates the database, builds the schema and loads SP500History.csv.
# Run on the VM as the postgres user (with $PGHOME/bin on PATH):
#   ./setup_db.sh [database] [path/to/SP500History.csv]
set -euo pipefail

DB="${1:-mydb}"
CSV="${2:-$(dirname "$0")/SP500History.csv}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CSV="$(cd "$(dirname "$CSV")" && pwd)/$(basename "$CSV")"

if ! psql -lqt | cut -d '|' -f 1 | grep -qw "$DB"; then
    echo "Creating database $DB"
    createdb "$DB"
fi

echo "Creating schema in $DB"
psql -v ON_ERROR_STOP=1 -q -d "$DB" -f "$HERE/sql/schema.sql"

echo "Loading $CSV (about 619k rows, takes a few seconds)"
psql -v ON_ERROR_STOP=1 -d "$DB" -v csv="$CSV" -f "$HERE/sql/load_data.sql"

echo "Done."
