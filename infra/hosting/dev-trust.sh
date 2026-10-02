#!/bin/sh
# NUR LOKAL: Dienste vertrauen der lokalen Zertifizierungsstelle von Caddy (für https://*.chattia.internal).
# In Produktion nicht nötig – dort stellt Let's Encrypt öffentlich vertrauenswürdige Zertifikate aus.
set -eu
cd "$(dirname "$0")"
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-local-root.crt
docker compose cp ./caddy-local-root.crt nextcloud:/tmp/caddy-root.crt
docker compose exec -T nextcloud chown www-data /tmp/caddy-root.crt
docker compose exec -T -u www-data nextcloud php occ security:certificates:import /tmp/caddy-root.crt
docker compose exec -T wordpress sh -c 'grep -q "Caddy Local" wp-includes/certificates/ca-bundle.crt || { echo; cat; } >> wp-includes/certificates/ca-bundle.crt' < ./caddy-local-root.crt
echo "Lokale Zertifizierungsstelle eingetragen: Nextcloud, WordPress."
echo "Für den Browser/Backend: ./caddy-local-root.crt (Java: keytool -importcert …)"
