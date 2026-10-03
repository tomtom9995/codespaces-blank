#!/bin/bash
# Kalender „Semesterprogramm“: gehört dem Verwaltungskonto, Chargen dürfen schreiben, alle Gruppen lesen,
# öffentlicher Abo-Link (nur öffentliche Termine) für Website und Handy-Kalender. Wiederholbar.
# Aufruf: docker compose exec nextcloud bash /chattia/semesterprogramm.sh   → gibt den öffentlichen Link aus
set -euo pipefail
OWNER=${NEXTCLOUD_ADMIN_USER:?}
CAL=semesterprogramm
WRITERS="senior consenior subsenior fuchsmajor"
READERS="burschen fuechse inaktive alte-herren gaeste"
HOST=$(php /var/www/html/occ config:system:get overwritehost 2>/dev/null || echo localhost)
URL="http://localhost/remote.php/dav/calendars/$OWNER/$CAL/"
dav() { curl -fsS -u "$OWNER:$NEXTCLOUD_ADMIN_PASSWORD" -H "Host: $HOST" -H "X-Forwarded-Proto: https" "$@"; }

if [ "$(id -u)" = 0 ]; then
  su -s /bin/sh www-data -c "php /var/www/html/occ dav:create-calendar '$OWNER' '$CAL'" >/dev/null 2>&1 || true
else
  php /var/www/html/occ dav:create-calendar "$OWNER" "$CAL" >/dev/null 2>&1 || true
fi
dav -X PROPPATCH "$URL" -H 'Content-Type: application/xml' --data '<?xml version="1.0"?>
<d:propertyupdate xmlns:d="DAV:" xmlns:a="http://apple.com/ns/ical/"><d:set><d:prop>
<d:displayname>Semesterprogramm</d:displayname><a:calendar-color>#0071E3</a:calendar-color></d:prop></d:set></d:propertyupdate>' >/dev/null

share=""
for g in $WRITERS; do share+="<o:set><d:href>principal:principals/groups/$g</d:href><o:read-write/></o:set>"; done
for g in $READERS; do share+="<o:set><d:href>principal:principals/groups/$g</d:href></o:set>"; done
dav -X POST "$URL" -H 'Content-Type: application/xml' --data "<?xml version=\"1.0\"?><o:share xmlns:d=\"DAV:\" xmlns:o=\"http://owncloud.org/ns\">$share</o:share>" >/dev/null

dav -X POST "$URL" -H 'Content-Type: application/xml' \
  --data '<?xml version="1.0"?><cs:publish-calendar xmlns:cs="http://calendarserver.org/ns/"/>' >/dev/null 2>&1 || true
dav -X PROPFIND "$URL" -H 'Depth: 0' -H 'Content-Type: application/xml' \
  --data '<?xml version="1.0"?><d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/"><d:prop><cs:publish-url/></d:prop></d:propfind>' \
  | grep -o '<cs:publish-url><d:href>[^<]*' | sed 's/.*<d:href>//; s#$#?export#'
