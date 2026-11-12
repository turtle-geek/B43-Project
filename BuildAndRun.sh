#!/usr/bin/env bash
# builds and runs the app (set DB_URL, DB_USER, DB_PASSWORD to change the connection)
set -euo pipefail
cd "$(dirname "$0")/app"
mvn -q -DskipTests package
exec java -jar target/stock-social.jar
