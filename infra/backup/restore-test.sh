#!/bin/bash
# Automatischer Wiederherstellungstest: stellt den neuesten Stand in ein temporäres Verzeichnis
# und eine temporäre Datenbank wieder her und prüft, ob die Daten brauchbar sind.
# „Ein Backup, das nie wiederhergestellt wurde, ist nur eine Hoffnung.“
set -euo pipefail
HOST_TAG=${BACKUP_HOST_TAG:-chattia}
TARGET=$(mktemp -d /tmp/restore.XXXX)
FAIL=0
log() { echo "$(date '+%F %T') [restore-test] $*"; }
check() { if eval "$2"; then log "  ✓ $1"; else log "  ✗ $1"; FAIL=1; fi; }
trap 'rm -rf "$TARGET"; psql -q -c "DROP DATABASE IF EXISTS restore_test" >/dev/null 2>&1 || true' EXIT

log "Wiederherstellung nach $TARGET"
restic restore latest --host "$HOST_TAG" --tag databases --target "$TARGET" --quiet
restic restore latest --host "$HOST_TAG" --tag nextcloud-files --target "$TARGET" --quiet
restic restore latest --host "$HOST_TAG" --tag wordpress --target "$TARGET" --quiet
DUMPS=$(dirname "$(find "$TARGET" -name nextcloud.pgdump | head -1)")

log "Prüfungen"
check "Nextcloud-Dump lesbar" "pg_restore --list '$DUMPS/nextcloud.pgdump' >/dev/null"
check "Keycloak-Dump lesbar" "pg_restore --list '$DUMPS/keycloak.pgdump' >/dev/null"
psql -q -c "DROP DATABASE IF EXISTS restore_test" >/dev/null && psql -q -c "CREATE DATABASE restore_test" >/dev/null
check "Nextcloud-Datenbank einspielbar" "pg_restore --no-owner -d restore_test '$DUMPS/nextcloud.pgdump' >/dev/null 2>&1"
USERS=$(psql -At -d restore_test -c "SELECT count(*) FROM oc_users" 2>/dev/null || echo 0)
FILES_DB=$(psql -At -d restore_test -c "SELECT count(*) FROM oc_filecache WHERE mimetype <> (SELECT id FROM oc_mimetypes WHERE mimetype='httpd/unix-directory')" 2>/dev/null || echo 0)
log "  Nextcloud: $USERS lokale Konten, $FILES_DB Dateien laut Datenbank"
check "WordPress-Dump enthält Inhalte" "grep -q 'CREATE TABLE \`wp_posts\`' '$DUMPS/wordpress.sql'"
FILES_RESTORED=$(find "$TARGET/sources/nextcloud-data" -path '*/files/*' -type f 2>/dev/null | wc -l)
log "  Nextcloud: $FILES_RESTORED Dateien wiederhergestellt"
check "Dateien vorhanden, wenn die Datenbank Dateien kennt" "[ '$FILES_DB' -eq 0 ] || [ '$FILES_RESTORED' -gt 0 ]"
if [ -n "${VERIFY_FILE:-}" ]; then
  check "Prüfdatei $VERIFY_FILE identisch" "cmp -s '/sources/nextcloud-data/$VERIFY_FILE' '$TARGET/sources/nextcloud-data/$VERIFY_FILE'"
fi

if [ $FAIL -eq 0 ]; then
  log "Wiederherstellungstest BESTANDEN"
  [ -n "${HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 "${HEALTHCHECK_URL}/0" >/dev/null || true
else
  log "Wiederherstellungstest FEHLGESCHLAGEN"
  [ -n "${HEALTHCHECK_URL:-}" ] && curl -fsS -m 10 "${HEALTHCHECK_URL}/fail" >/dev/null || true
fi
exit $FAIL
