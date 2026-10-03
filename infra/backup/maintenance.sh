#!/bin/bash
# Aufbewahrung (Großvater-Vater-Sohn) anwenden, Speicher freigeben und das Repository prüfen.
# Läuft in Produktion NICHT auf dem Server, sondern als eigener Job (Cloud Run Job) mit dem einzigen
# Dienstkonto, das im Backup-Bucket löschen darf. Ein kompromittierter Server kann so keine Backups vernichten.
set -euo pipefail
log() { echo "$(date '+%F %T') [wartung] $*"; }
HOST_TAG=${BACKUP_HOST_TAG:-chattia}

log "Aufbewahrung: täglich ${KEEP_DAILY:-14}, wöchentlich ${KEEP_WEEKLY:-8}, monatlich ${KEEP_MONTHLY:-24}, jährlich ${KEEP_YEARLY:-10}"
restic forget --host "$HOST_TAG" --group-by host,tags \
  --keep-daily "${KEEP_DAILY:-14}" --keep-weekly "${KEEP_WEEKLY:-8}" \
  --keep-monthly "${KEEP_MONTHLY:-24}" --keep-yearly "${KEEP_YEARLY:-10}" \
  --prune --max-unused 5% --quiet

if [ "${1:-}" != "--no-check" ]; then
  # Wöchentlich 10 % der Daten vollständig lesen → nach ca. 10 Wochen ist jedes Byte einmal geprüft.
  log "Prüfung (Struktur + ${CHECK_SUBSET:-10%} der Daten)"
  restic check --read-data-subset="${CHECK_SUBSET:-10%}"
fi
log "fertig"
