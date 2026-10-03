#!/bin/bash
# Jahresarchiv: ausgewählte Ordner (z. B. Kasse, Protokolle, Satzung) als eine verschlüsselte Datei.
# - Verschlüsselt mit age an einen ÖFFENTLICHEN Schlüssel: Der Server kann das Archiv schreiben, aber nie lesen.
#   Der private Schlüssel liegt nur offline (Tresor/Hardware-Schlüssel), siehe docs/plan/backup.md.
# - Ablage in einem Bucket mit gesperrter Aufbewahrungsfrist (Bucket Lock, 10 Jahre) → unveränderbar.
set -euo pipefail
log() { echo "$(date '+%F %T') [archiv] $*"; }
: "${ARCHIVE_AGE_RECIPIENT:?ARCHIVE_AGE_RECIPIENT (öffentlicher age-Schlüssel) fehlt}"
YEAR=${ARCHIVE_YEAR:-$(date -d "@$(( $(date +%s) - 86400 * 30 ))" +%Y 2>/dev/null || date +%Y)}
OUT_DIR=${ARCHIVE_OUT_DIR:-/archive}
NAME="chattia-archiv-$YEAR-$(date +%Y%m%d).tar.zst.age"
mkdir -p "$OUT_DIR"

# Pfade relativ zum Nextcloud-Datenordner, Leerzeichen-getrennt, z. B. "__groupfolders/1 senior/files/Corps"
read -r -a PATHS <<< "${ARCHIVE_PATHS:?ARCHIVE_PATHS fehlt}"
cd /sources/nextcloud-data
log "Archiviere ${PATHS[*]} → $NAME"
tar --create --file - "${PATHS[@]}" | zstd -q -19 -T0 | age -r "$ARCHIVE_AGE_RECIPIENT" > "$OUT_DIR/$NAME"
sha256sum "$OUT_DIR/$NAME" | tee "$OUT_DIR/$NAME.sha256"

if [ -n "${ARCHIVE_BUCKET:-}" ]; then
  # Hochladen mit dem Dienstkonto der VM (Metadatenserver) – ohne gcloud im Image.
  TOKEN=$(curl -fsS -H 'Metadata-Flavor: Google' \
    'http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token' | jq -r .access_token)
  for f in "$NAME" "$NAME.sha256"; do
    curl -fsS -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/octet-stream' \
      --data-binary @"$OUT_DIR/$f" \
      "https://storage.googleapis.com/upload/storage/v1/b/$ARCHIVE_BUCKET/o?uploadType=media&name=$f" >/dev/null
  done
  log "hochgeladen nach gs://$ARCHIVE_BUCKET/$NAME"
  rm -f "$OUT_DIR/$NAME"
fi
log "fertig"
