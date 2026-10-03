#!/bin/bash
# Zeitplan aus der Umgebung. Leerer Zeitplan = Job aus.
#   BACKUP_SCHEDULE        täglich   Datenbanken + Dateien → primäres Repository
#   RESTORE_TEST_SCHEDULE  monatlich automatischer Wiederherstellungstest
#   OFFSITE_SCHEDULE       wöchentlich Kopie zum zweiten Anbieter (nur mit OFFSITE_REPOSITORY)
#   ARCHIVE_SCHEDULE       jährlich  verschlüsseltes Jahresarchiv (nur mit ARCHIVE_AGE_RECIPIENT)
#   MAINTENANCE_SCHEDULE   nur lokal/ohne getrennten Wartungsjob: Aufbewahrung + Prüfung
set -euo pipefail
env | grep -E '^(RESTIC_|GOOGLE_|AWS_|B2_|PG|MARIADB_|HEALTHCHECK_URL|KEEP_|TZ|FORGET_ENABLED|CHECK_SUBSET|OFFSITE_|ARCHIVE_|BACKUP_HOST_TAG)' \
  | sed 's/^/export /; s/=/="/; s/$/"/' > /etc/backup.env

job() { # job <zeitplan> <skript>
  [ -n "$1" ] && echo "$1 . /etc/backup.env && /usr/local/bin/$2 >> /var/log/backup.log 2>&1"
  return 0
}
{
  job "${BACKUP_SCHEDULE-15 2 * * *}" backup.sh
  job "${RESTORE_TEST_SCHEDULE-30 4 1 * *}" restore-test.sh
  [ -n "${OFFSITE_REPOSITORY:-}" ] && job "${OFFSITE_SCHEDULE-0 5 * * 0}" offsite-copy.sh
  [ -n "${ARCHIVE_AGE_RECIPIENT:-}" ] && job "${ARCHIVE_SCHEDULE-0 6 2 1 *}" archive.sh
  job "${MAINTENANCE_SCHEDULE-}" maintenance.sh
  true
} > /etc/crontabs/root
touch /var/log/backup.log
echo "[backup] Zeitplan:"; sed 's/ \. \/etc.*bin\// → /; s/ >>.*//' /etc/crontabs/root
crond -b -l 8
exec tail -F /var/log/backup.log
