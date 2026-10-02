#!/bin/bash
# Kopie aller Snapshots in ein zweites Repository bei einem anderen Anbieter (3-2-1: "1 Kopie außer Haus").
# Eigenes Passwort, eigene Zugangsdaten; der Zielanbieter hält ebenfalls Löschschutz (z. B. Object Lock / Append-only).
set -euo pipefail
log() { echo "$(date '+%F %T') [offsite] $*"; }
: "${OFFSITE_REPOSITORY:?OFFSITE_REPOSITORY fehlt}"
: "${OFFSITE_PASSWORD:?OFFSITE_PASSWORD fehlt}"

export RESTIC_FROM_REPOSITORY="$RESTIC_REPOSITORY" RESTIC_FROM_PASSWORD="$RESTIC_PASSWORD"
export RESTIC_REPOSITORY="$OFFSITE_REPOSITORY" RESTIC_PASSWORD="$OFFSITE_PASSWORD"
if ! restic cat config >/dev/null 2>&1; then
  log "Offsite-Repository wird angelegt (gleiche Chunk-Parameter → Deduplizierung bleibt erhalten)"
  restic init --copy-chunker-params
fi
log "Kopiere neue Snapshots"
restic copy --quiet
restic snapshots --compact --latest 1
log "fertig"
