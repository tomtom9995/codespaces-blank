#!/bin/sh
# Bei jedem Start: versionierte Zusatzkonfiguration aus dem Repository übernehmen.
set -eu
cp /chattia/config/*.config.php /var/www/html/config/
