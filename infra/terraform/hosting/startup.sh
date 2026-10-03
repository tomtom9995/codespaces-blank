#!/bin/bash
# Startskript der VM (läuft bei jedem Boot, idempotent). Terraform-Vorlage: Platzhalter sind repo_url, repo_ref, project_id.
set -euo pipefail
exec > >(logger -t chattia-startup) 2>&1

DATA=/srv/chattia
DISK=/dev/disk/by-id/google-chattia-data

# 1) Datendisk einhängen (beim ersten Mal formatieren). Alle Docker-Volumes liegen darauf → Snapshots erfassen alles.
if ! blkid "$DISK" >/dev/null 2>&1; then mkfs.ext4 -m 0 -L chattia-data "$DISK"; fi
mkdir -p "$DATA"
grep -q "$DATA" /etc/fstab || echo "LABEL=chattia-data $DATA ext4 defaults,nofail,discard 0 2" >> /etc/fstab
mountpoint -q "$DATA" || mount "$DATA"

# 2) Docker mit Datenverzeichnis auf der Datendisk, automatische Sicherheitsupdates
if ! command -v docker >/dev/null; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose git unattended-upgrades jq
  mkdir -p /etc/docker
  echo "{\"data-root\": \"$DATA/docker\", \"log-driver\": \"json-file\", \"log-opts\": {\"max-size\": \"20m\", \"max-file\": \"5\"}}" > /etc/docker/daemon.json
  systemctl restart docker
fi

# 3) Repository holen bzw. aktualisieren
if [ ! -d "$DATA/repo/.git" ]; then git clone "${repo_url}" "$DATA/repo"; fi
git -C "$DATA/repo" fetch --quiet origin && git -C "$DATA/repo" checkout --quiet "${repo_ref}" && git -C "$DATA/repo" pull --quiet --ff-only || true

# 4) .env aus dem Secret Manager (Secret "chattia-hosting-env" enthält die komplette .env-Datei)
TOKEN=$(curl -fsS -H 'Metadata-Flavor: Google' \
  http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token | jq -r .access_token)
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "https://secretmanager.googleapis.com/v1/projects/${project_id}/secrets/chattia-hosting-env/versions/latest:access" \
  | jq -r .payload.data | base64 -d > "$DATA/repo/infra/hosting/.env"
chmod 600 "$DATA/repo/infra/hosting/.env"

# 5) Stack starten (zieht neue Images nur bei geänderten Tags; Updates bewusst per Commit)
cd "$DATA/repo/infra/hosting"
docker compose up -d --build --remove-orphans
