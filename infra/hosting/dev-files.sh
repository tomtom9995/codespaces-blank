#!/bin/bash
# NUR LOKAL: Beispielinhalte in den Team-Ordnern (nextcloud/teamfolders.conf) für Demos und Tests.
# Die Rechte kommen aus den Team-Ordnern: z. B. sieht ein Bursch „Corps“ und „Semesterprogramm“, aber nicht „Kasse“.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

nc() { docker compose exec -T -u www-data nextcloud "$@"; }
ocs() { # Konto über die user_oidc-API anlegen (idempotent), damit es vor dem ersten Login existiert
  docker compose exec -T nextcloud curl -sf -o /dev/null -u "$NEXTCLOUD_ADMIN_USER:$NEXTCLOUD_ADMIN_PASSWORD" \
    -H "Host: $CLOUD_HOST_WITH_PORT" -H "OCS-APIRequest: true" -H "X-Forwarded-Proto: https" \
    -X POST "http://localhost/ocs/v2.php/apps/user_oidc/api/v1/user" \
    -d "providerId=1" -d "userId=$1" -d "displayName=$2" -d "email=$1@example.org"
}
folders=$(nc php occ groupfolders:list --output=json)
put() { # team-ordner pfad inhalt
  local id
  id=$(nc php -r '$n=$argv[1]; foreach (json_decode($argv[2], true) as $f) { if (($f["mountPoint"] ?? "") === $n) echo $f["id"]; }' "$1" "$folders")
  [ -n "$id" ] || { echo "Team-Ordner $1 fehlt – erst nextcloud/teamfolders.sh ausführen"; exit 1; }
  nc sh -c "mkdir -p \"data/__groupfolders/$id/files/$(dirname "$2")\" && cat > \"data/__groupfolders/$id/files/$2\"" <<< "$3"
  echo "$id"
}

ocs bursch "Bernd Bursch"
ocs senior "Max Mustermann"

ids=()
ids+=("$(put Corps "Satzung.md" "# Satzung des Corps Chattia (Auszug, Beispiel)

## § 1 Name und Sitz

Der Bund führt den Namen Corps Chattia.

## § 2 Zweck

Lebensbund, Erziehung zu verantwortungsbewussten Persönlichkeiten, Toleranz.")")
ids+=("$(put Corps "Comment.md" "# Comment (Beispiel)

## Fuchsenzeit

Die Fuchsenzeit dauert in der Regel zwei Semester. Der Fuchsmajor betreut die Füxe.

## Kneipe

Auf der Kneipe führt das Präsidium. Es gilt der Kneipcomment.")")
ids+=("$(put Semesterprogramm "WS-2026.md" "# Semesterprogramm Wintersemester 2026/27

- 17.10. Antrittskneipe
- 07.11. Fuchsenstunde
- 28.11. Stiftungsfest (Dresscode: Frack bzw. Abendkleid)
- 12.12. Weihnachtskneipe")")
ids+=("$(put Aktivitas "Kneipe/Liederliste.md" "# Liederliste Antrittskneipe

1. Gaudeamus igitur
2. Ergo bibamus")")
ids+=("$(put "Amt Senior" "Übergabe-Checkliste.md" "# Übergabe Senior

- Schlüssel Corpshaus und Kneipsaal übergeben
- Zugänge: Keycloak-Gruppe chargen/senior umhängen (IT)
- Fristen: Semesterbericht an den AHV bis 30.11.")")
ids+=("$(put Kasse "Beitragsordnung.md" "# Beitragsordnung AHV (vertraulich, Beispiel)

Der jährliche AH-Beitrag beträgt 240 Euro und ist bis 31.03. fällig.")")

for id in $(printf '%s\n' "${ids[@]}" | sort -u); do nc php occ groupfolders:scan "$id" -q; done
# Alte private Beispielordner aus früheren Versionen dieses Skripts entfernen
for u in bursch senior; do
  nc sh -c "rm -rf data/$u/files/Corps data/$u/files/Semesterprogramm data/$u/files/Kneipe" || true
  nc php occ files:scan "$u" -q
done
echo "Beispieldateien in den Team-Ordnern angelegt (Corps, Semesterprogramm, Aktivitas, Amt Senior, Kasse)"

# Semesterprogramm-Kalender mit Beispielterminen (ein interner Termin, der nicht öffentlich erscheinen darf)
docker compose cp nextcloud/semesterprogramm.sh nextcloud:/tmp/semesterprogramm.sh >/dev/null 2>&1
ics_url=$(docker compose exec -T nextcloud bash /tmp/semesterprogramm.sh)
event() { # uid start ende titel ort klasse
  docker compose exec -T nextcloud curl -fsS -o /dev/null -u "$NEXTCLOUD_ADMIN_USER:$NEXTCLOUD_ADMIN_PASSWORD" \
    -H "Host: $CLOUD_HOST_WITH_PORT" -H "X-Forwarded-Proto: https" -H "Content-Type: text/calendar" -X PUT \
    "http://localhost/remote.php/dav/calendars/$NEXTCLOUD_ADMIN_USER/semesterprogramm/$1.ics" --data-binary @- <<ICS
BEGIN:VCALENDAR
VERSION:2.0
PRODID:-//Corps Chattia//dev-files//DE
BEGIN:VEVENT
UID:$1@chattia
DTSTAMP:20261001T120000Z
DTSTART;TZID=Europe/Berlin:$2
DTEND;TZID=Europe/Berlin:$3
SUMMARY:$4
LOCATION:$5
CLASS:$6
END:VEVENT
END:VCALENDAR
ICS
}
event antritt-ws26 20261017T200000 20261017T235900 "Antrittskneipe" "Corpshaus" PUBLIC
event fuchsenstunde-1 20261107T190000 20261107T210000 "Fuchsenstunde" "Corpshaus" PUBLIC
event cc-intern-1 20261114T190000 20261114T220000 "Convent (intern)" "Corpshaus" PRIVATE
event stiftungsfest-26 20261128T180000 20261129T020000 "Stiftungsfest" "Kurhaus" PUBLIC
event weihnachtskneipe-26 20261212T200000 20261212T235900 "Weihnachtskneipe" "Corpshaus" PUBLIC
echo "Semesterprogramm mit Beispielterminen. Öffentlicher Abo-Link (für WORDPRESS_EVENTS_ICS in .env):"
echo "  $ics_url"
