#!/bin/sh
# Getrennte Datenbanken und Benutzer für Keycloak und Nextcloud.
set -e
psql -v ON_ERROR_STOP=1 --username postgres <<SQL
CREATE USER keycloak WITH PASSWORD '${KEYCLOAK_DB_PASSWORD}';
CREATE DATABASE keycloak OWNER keycloak;
CREATE USER nextcloud WITH PASSWORD '${NEXTCLOUD_DB_PASSWORD}';
CREATE DATABASE nextcloud OWNER nextcloud TEMPLATE template0 ENCODING 'UTF8';
SQL
