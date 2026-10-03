#!/bin/bash
# Notfall-Wiederherstellung der kompletten Plattform aus dem restic-Repository (Runbook B, docs/plan/backup.md).
#
#   docker compose down -v                       # bzw. neue VM: leere Volumes
#   docker compose up -d postgres mariadb
#   docker compose --profile restore run --rm restore
#   docker compose up -d
#
# Läuft im Container „restore“ mit Schreibzugriff auf die Volumes. Anwendungen müssen gestoppt sein.
set -euo pipefail
log() { echo "$(date '+%F %T') [wiederherstellung] $*"; }
HOST_TAG=${BACKUP_HOST_TAG:-chattia}
START=$(date +%s)

log "Verfügbare Stände:"
restic snapshots --host "$HOST_TAG" --compact --latest 1

for v in /sources/nextcloud-data /sources/nextcloud-html /sources/wordpress-html; do
  if [ -n "$(ls -A "$v" 2>/dev/null)" ] && [ "${FORCE:-0}" != "1" ]; then
    log "ABBRUCH: $v ist nicht leer. Wiederherstellung nur in leere Volumes (oder FORCE=1)."; exit 1
  fi
done

# 1) Datenbanken (Rollen/Datenbanken legt postgres/init.sh beim ersten Start an)
TMP=$(mktemp -d)
restic restore latest --host "$HOST_TAG" --tag databases --target "$TMP" --quiet
DUMPS=$(dirname "$(find "$TMP" -name nextcloud.pgdump | head -1)")
for db in keycloak nextcloud; do
  log "PostgreSQL: $db"
  pg_restore --clean --if-exists --exit-on-error -d "$db" "$DUMPS/$db.pgdump"
done
log "MariaDB: wordpress"
mariadb -h "$MARIADB_HOST" -uroot -p"$MARIADB_ROOT_PASSWORD" wordpress < "$DUMPS/wordpress.sql"
rm -rf "$TMP"

# 2) Dateien direkt an ihren Platz (Pfade im Backup = Pfade hier)
for tag in nextcloud-html nextcloud-files wordpress; do
  log "Dateien: $tag"
  restic restore latest --host "$HOST_TAG" --tag "$tag" --target / --quiet
done
chown -R 33:33 /sources/nextcloud-data /sources/nextcloud-html /sources/wordpress-html   # www-data in beiden Images

log "Fertig nach $(( $(date +%s) - START )) s. Jetzt: docker compose up -d, danach"
log "  docker compose exec -u www-data nextcloud php occ maintenance:data-fingerprint"
log "  docker compose exec -u www-data nextcloud php occ files:scan --all"
