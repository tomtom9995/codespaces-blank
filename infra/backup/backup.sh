#!/bin/bash
# Sichert Datenbanken und Dateien in ein restic-Repository (verschlüsselt, dedupliziert, versioniert).
# Aufbewahrung nach Großvater-Vater-Sohn: siehe docs/plan/backup.md.
set -euo pipefail

HOST_TAG=${BACKUP_HOST_TAG:-chattia}
WORK=$(mktemp -d /tmp/dumps.XXXX)
STATUS=0
log() { echo "$(date '+%F %T') [backup] $*"; }
ping_health() { [ -n "${HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" >/dev/null || true; }
MAINTENANCE_ON=0
cleanup() {
  # Wartungsmodus nie hängen lassen, auch wenn das Skript abbricht
  if [ "$MAINTENANCE_ON" = "1" ]; then occ maintenance:mode --off >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
}
trap cleanup EXIT

ping_health /start
restic cat config >/dev/null 2>&1 || { log "Repository wird angelegt"; restic init; }
# Verwaiste Sperren abgebrochener Läufe entfernen (aktive Sperren bleiben unangetastet)
restic unlock --quiet 2>/dev/null || true

nc_container() { docker ps --filter "label=com.docker.compose.service=nextcloud" --format '{{.Names}}' | head -1; }
occ() { docker exec -u www-data "$(nc_container)" php occ "$@"; }

# 1) Datenbanken – Nextcloud kurz im Wartungsmodus, damit Datenbank und Dateien zusammenpassen.
log "Datenbank-Dumps"
NC=$(nc_container || true)
if [ -n "$NC" ]; then occ maintenance:mode --on >/dev/null && MAINTENANCE_ON=1; fi
pg_dump -Fc nextcloud > "$WORK/nextcloud.pgdump" || STATUS=1
if [ "$MAINTENANCE_ON" = "1" ]; then occ maintenance:mode --off >/dev/null && MAINTENANCE_ON=0; fi
pg_dump -Fc keycloak > "$WORK/keycloak.pgdump" || STATUS=1
mariadb-dump -h "$MARIADB_HOST" -uroot -p"$MARIADB_ROOT_PASSWORD" --single-transaction --routines --triggers wordpress > "$WORK/wordpress.sql" || STATUS=1
for f in "$WORK"/*; do log "  $(basename "$f"): $(du -h "$f" | cut -f1)"; done
restic backup --host "$HOST_TAG" --tag databases "$WORK" --quiet || STATUS=1

# 2) Dateien – Vorschaubilder und Caches lassen sich neu erzeugen und werden ausgelassen.
log "Nextcloud-Dateien"
restic backup --host "$HOST_TAG" --tag nextcloud-files /sources/nextcloud-data \
  --exclude '/sources/nextcloud-data/appdata_*/preview' \
  --exclude '/sources/nextcloud-data/*/cache' \
  --exclude '/sources/nextcloud-data/nextcloud.log*' --quiet || STATUS=1
log "Nextcloud-Konfiguration und Apps"
restic backup --host "$HOST_TAG" --tag nextcloud-config /sources/nextcloud-html/config /sources/nextcloud-html/custom_apps --quiet || STATUS=1
log "WordPress (Uploads, Themes, Plugins)"
restic backup --host "$HOST_TAG" --tag wordpress /sources/wordpress-html/wp-content --quiet || STATUS=1

# 3) Aufbewahrung. Nur wenn diese Instanz Löschrechte hat – in Produktion darf der Server NICHT löschen,
#    das übernimmt maintenance.sh als separater Job mit eigenem Dienstkonto (siehe docs/plan/backup.md).
if [ "${FORGET_ENABLED:-1}" = "1" ]; then
  /usr/local/bin/maintenance.sh --no-check || STATUS=1
fi

# 4) Stichprobe: 2 % der Daten werden bei jedem Lauf gelesen und geprüft.
restic check --read-data-subset=2% --quiet || STATUS=1

restic snapshots --host "$HOST_TAG" --latest 1 --compact
if [ $STATUS -eq 0 ]; then log "Backup erfolgreich"; ping_health ""; else log "Backup mit FEHLERN beendet"; ping_health /fail; fi
exit $STATUS
