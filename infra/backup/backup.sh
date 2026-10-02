#!/bin/bash
# Sichert Datenbanken und Dateien in ein restic-Repository (verschlüsselt, dedupliziert, versioniert).
# Aufbewahrung nach Großvater-Vater-Sohn: siehe docs/plan/backup.md.
set -euo pipefail

HOST_TAG=${BACKUP_HOST_TAG:-chattia}
WORK=$(mktemp -d /tmp/dumps.XXXX)
STATUS=0
log() { echo "$(date '+%F %T') [backup] $*"; }
ping_health() { [ -n "${HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" >/dev/null || true; }
cleanup() { rm -rf "$WORK"; }
trap cleanup EXIT

ping_health /start
restic cat config >/dev/null 2>&1 || { log "Repository wird angelegt"; restic init; }

nc_container() { docker ps --filter "label=com.docker.compose.service=nextcloud" --format '{{.Names}}' | head -1; }
occ() { docker exec -u www-data "$(nc_container)" php occ "$@"; }

# 1) Datenbanken – Nextcloud kurz im Wartungsmodus, damit Datenbank und Dateien zusammenpassen.
log "Datenbank-Dumps"
NC=$(nc_container || true)
if [ -n "$NC" ]; then occ maintenance:mode --on >/dev/null; fi
pg_dump -Fc nextcloud > "$WORK/nextcloud.pgdump" || STATUS=1
if [ -n "$NC" ]; then occ maintenance:mode --off >/dev/null; fi
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

# 3) Aufbewahrung. Nur wenn diese Instanz Löschrechte hat (in Produktion übernimmt das ein separater Wartungsjob).
if [ "${FORGET_ENABLED:-1}" = "1" ]; then
  log "Aufbewahrung anwenden"
  restic forget --host "$HOST_TAG" --group-by host,tags \
    --keep-daily "${KEEP_DAILY:-14}" --keep-weekly "${KEEP_WEEKLY:-8}" \
    --keep-monthly "${KEEP_MONTHLY:-24}" --keep-yearly "${KEEP_YEARLY:-10}" --prune --quiet || STATUS=1
fi

# 4) Stichprobe: 2 % der Daten werden bei jedem Lauf gelesen und geprüft.
restic check --read-data-subset=2% --quiet || STATUS=1

restic snapshots --host "$HOST_TAG" --latest 1 --compact
if [ $STATUS -eq 0 ]; then log "Backup erfolgreich"; ping_health ""; else log "Backup mit FEHLERN beendet"; ping_health /fail; fi
exit $STATUS
