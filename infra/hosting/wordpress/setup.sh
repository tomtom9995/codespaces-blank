#!/bin/sh
# Einrichtung per WP-CLI: docker compose run --rm wpcli
# Bei einer Migration wird die alte Datenbank importiert (siehe docs/plan/nextcloud-wordpress.md) – dann
# überspringt das Skript die Installation und aktiviert nur das Login-Plugin.
set -eu
cd /var/www/html
for i in $(seq 1 30); do wp core is-installed 2>/dev/null && break; [ -f wp-config.php ] && break; sleep 2; done

if ! wp core is-installed 2>/dev/null; then
  wp core install --url="$WEB_URL" --title="Corps Chattia" --admin_user="notfall-admin" \
    --admin_email="$WORDPRESS_ADMIN_EMAIL" --admin_password="$(head -c 24 /dev/urandom | base64)" --skip-email
  wp language core install de_DE --activate || true
  wp option update blogdescription "Visitenkarte"
  wp post create --post_type=page --post_status=publish --post_title="Willkommen" \
    --post_content="<!-- wp:paragraph --><p>Platzhalter – wird bei der Migration durch die bestehende Website ersetzt.</p><!-- /wp:paragraph -->"
  wp option update show_on_front page
  wp option update page_on_front "$(wp post list --post_type=page --name=willkommen --field=ID)"
  wp rewrite structure '/%postname%/'
fi

if [ -f /plugins/daggerhart-openid-connect-generic.zip ]; then
  wp plugin install /plugins/daggerhart-openid-connect-generic.zip --force --activate
else
  wp plugin install daggerhart-openid-connect-generic --activate
fi
wp plugin list --status=active --fields=name,version
echo "WordPress bereit: $WEB_URL (Login: $WEB_URL/wp-login.php)"
