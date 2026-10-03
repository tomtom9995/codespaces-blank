#!/bin/bash
# Kalender des Corps (wiederholbar). Eigentümer ist das Verwaltungskonto; Chargen schreiben, alle Gruppen lesen.
#   „Semesterprogramm“         öffentlich: Abo-Link für Website und Handy-Kalender
#   „Semesterprogramm intern“  nur Mitglieder (Convente, Interna) – kein öffentlicher Link
# Aufruf: docker compose exec nextcloud bash /chattia/semesterprogramm.sh   → gibt den öffentlichen Link aus
set -euo pipefail
OWNER=${NEXTCLOUD_ADMIN_USER:?}
WRITERS="senior consenior subsenior fuchsmajor"
READERS="burschen fuechse inaktive alte-herren"
HOST=$(php /var/www/html/occ config:system:get overwritehost 2>/dev/null || echo localhost)
dav() { curl -fsS -u "$OWNER:$NEXTCLOUD_ADMIN_PASSWORD" -H "Host: $HOST" -H "X-Forwarded-Proto: https" "$@"; }
occ() { if [ "$(id -u)" = 0 ]; then su -s /bin/sh www-data -c "php /var/www/html/occ $*"; else php /var/www/html/occ "$@"; fi; }

calendar() { # uri anzeigename farbe zusätzliche-lesegruppen
  local url="http://localhost/remote.php/dav/calendars/$OWNER/$1/" share=""
  occ dav:create-calendar "$OWNER" "$1" >/dev/null 2>&1 || true
  dav -X PROPPATCH "$url" -H 'Content-Type: application/xml' --data "<?xml version=\"1.0\"?>
<d:propertyupdate xmlns:d=\"DAV:\" xmlns:a=\"http://apple.com/ns/ical/\"><d:set><d:prop>
<d:displayname>$2</d:displayname><a:calendar-color>$3</a:calendar-color></d:prop></d:set></d:propertyupdate>" >/dev/null
  for g in $WRITERS; do share+="<o:set><d:href>principal:principals/groups/$g</d:href><o:read-write/></o:set>"; done
  for g in $READERS $4; do share+="<o:set><d:href>principal:principals/groups/$g</d:href></o:set>"; done
  dav -X POST "$url" -H 'Content-Type: application/xml' --data "<?xml version=\"1.0\"?><o:share xmlns:d=\"DAV:\" xmlns:o=\"http://owncloud.org/ns\">$share</o:share>" >/dev/null
}

calendar semesterprogramm "Semesterprogramm" "#0071E3" "gaeste"
calendar semesterprogramm-intern "Semesterprogramm intern" "#8E8E93" ""

URL="http://localhost/remote.php/dav/calendars/$OWNER/semesterprogramm/"
dav -X POST "$URL" -H 'Content-Type: application/xml' \
  --data '<?xml version="1.0"?><cs:publish-calendar xmlns:cs="http://calendarserver.org/ns/"/>' >/dev/null 2>&1 || true
dav -X PROPFIND "$URL" -H 'Depth: 0' -H 'Content-Type: application/xml' \
  --data '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><cs:publish-url/></d:prop></d:propfind>' \
  | grep -o '<cs:publish-url><d:href>[^<]*' | sed 's/.*<d:href>//; s#$#?export#'
