#!/bin/sh
# Läuft einmalig nach der Nextcloud-Installation (offizieller Docker-Hook, als www-data).
# Richtet den zentralen Login (Keycloak) und die Grundkonfiguration ein.
set -eu
occ() { php /var/www/html/occ "$@"; }

echo "[chattia] Grundkonfiguration"
occ config:system:set default_phone_region --value=DE
occ config:system:set default_language --value=de
occ config:system:set default_locale --value=de_DE
occ config:system:set maintenance_window_start --type=integer --value=1
occ background:cron
occ db:add-missing-indices || true

cp /chattia/config/*.config.php /var/www/html/config/

# Vorab bereitgestellte Apps (z. B. für Umgebungen ohne Zugang zum App Store) übernehmen.
for dir in /chattia/apps/*/; do
  [ -d "$dir" ] || continue
  name=$(basename "$dir")
  [ -d "/var/www/html/custom_apps/$name" ] || cp -r "$dir" "/var/www/html/custom_apps/$name"
done

echo "[chattia] Zentraler Login (user_oidc)"
if [ -d /var/www/html/custom_apps/user_oidc ] || [ -d /var/www/html/apps/user_oidc ]; then
  occ app:enable user_oidc
else
  occ app:install user_oidc
fi
occ user_oidc:provider chattia \
  --clientid=nextcloud \
  --clientsecret="${CHATTIA_OIDC_SECRET}" \
  --discoveryuri="${CHATTIA_AUTH_URL}/realms/chattia/.well-known/openid-configuration" \
  --scope="openid email profile groups" \
  --unique-uid=0 \
  --mapping-uid=preferred_username \
  --mapping-display-name=name \
  --mapping-email=email \
  --mapping-groups=groups \
  --group-provisioning=1 \
  --check-bearer=1 \
  --send-id-token-hint=1
# Login direkt über Keycloak; Notfall-Login für Admins bleibt unter /login?direct=1 erreichbar.
occ config:app:set --type=string --value=0 user_oidc allow_multiple_user_backends

echo "[chattia] Zusatz-Apps: ${CHATTIA_INSTALL_APPS:-keine}"
for app in ${CHATTIA_INSTALL_APPS:-}; do
  occ app:install "$app" || occ app:enable "$app" || echo "[chattia] App $app nicht verfügbar"
done
echo "[chattia] fertig"
