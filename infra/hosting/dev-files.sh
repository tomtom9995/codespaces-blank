#!/bin/bash
# NUR LOKAL: Beispielinhalte in der Cloud für Demos und App-Tests.
# Legt die Nextcloud-Konten der Testnutzer an (wie beim ersten SSO-Login) und füllt einige Ordner.
set -euo pipefail
cd "$(dirname "$0")"
set -a; . ./.env; set +a

ocs() { # Konto über die user_oidc-API anlegen (idempotent)
  docker compose exec -T nextcloud curl -sf -o /dev/null -u "$NEXTCLOUD_ADMIN_USER:$NEXTCLOUD_ADMIN_PASSWORD" \
    -H "Host: $CLOUD_HOST_WITH_PORT" -H "OCS-APIRequest: true" -H "X-Forwarded-Proto: https" \
    -X POST "http://localhost/ocs/v2.php/apps/user_oidc/api/v1/user" \
    -d "providerId=1" -d "userId=$1" -d "displayName=$2" -d "email=$1@example.org"
}
put() { # benutzer pfad inhalt
  docker compose exec -T -u www-data nextcloud sh -c "mkdir -p \"data/$1/files/$(dirname "$2")\" && cat > \"data/$1/files/$2\"" <<< "$3"
}

for u in "bursch:Bernd Bursch" "senior:Max Mustermann"; do
  name=${u%%:*}
  ocs "$name" "${u#*:}"
  put "$name" "Corps/Satzung.md" "# Satzung des Corps Chattia (Auszug, Beispiel)

§ 1 Name und Sitz
§ 2 Zweck: Lebensbund, Erziehung zu verantwortungsbewussten Persönlichkeiten …"
  put "$name" "Corps/Comment.md" "# Comment (Beispiel)

Die Fuchsenzeit dauert in der Regel zwei Semester. Der Fuchsmajor betreut die Füxe."
  put "$name" "Semesterprogramm/WS-2026.md" "# Semesterprogramm Wintersemester 2026/27

- 17.10. Antrittskneipe
- 07.11. Fuchsenstunde
- 28.11. Stiftungsfest (Dresscode: Frack bzw. Abendkleid)
- 12.12. Weihnachtskneipe"
  put "$name" "Kneipe/Liederliste.md" "# Liederliste Antrittskneipe

1. Gaudeamus igitur
2. Ergo bibamus"
  docker compose exec -T -u www-data nextcloud php occ files:scan "$name" -q
  echo "Beispieldateien für $name angelegt"
done
