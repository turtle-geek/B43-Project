#!/usr/bin/env bash
# Builds the Java application with Maven and starts it.
# Connection settings (override as needed):
#   DB_URL=jdbc:postgresql://localhost:5432/mydb DB_USER=postgres DB_PASSWORD=postgres
set -euo pipefail
cd "$(dirname "$0")/app"
mvn -q -DskipTests package
exec java -jar target/stock-social.jar
