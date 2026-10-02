#!/bin/bash
# Zeitplan aus der Umgebung: tägliches Backup, monatlicher Wiederherstellungstest.
set -euo pipefail
env | grep -E '^(RESTIC_|GOOGLE_|AWS_|B2_|PG|MARIADB_|HEALTHCHECK_URL|KEEP_|TZ)' | sed 's/^/export /; s/=/="/; s/$/"/' > /etc/backup.env
cat > /etc/crontabs/root <<CRON
${BACKUP_SCHEDULE:-15 2 * * *} . /etc/backup.env && /usr/local/bin/backup.sh >> /var/log/backup.log 2>&1
${RESTORE_TEST_SCHEDULE:-30 4 1 * *} . /etc/backup.env && /usr/local/bin/restore-test.sh >> /var/log/backup.log 2>&1
CRON
touch /var/log/backup.log
echo "[backup] Zeitplan aktiv: Backup '${BACKUP_SCHEDULE:-15 2 * * *}', Restore-Test '${RESTORE_TEST_SCHEDULE:-30 4 1 * *}'"
crond -b -l 8
exec tail -F /var/log/backup.log
