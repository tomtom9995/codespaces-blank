#!/bin/sh
# NUR FÜR DIE LOKALE ENTWICKLUNG: legt Testkonten im Realm "chattia" an.
# Aufruf: docker compose exec keycloak /bin/sh /opt/keycloak/dev-users.sh
set -eu
KC=/opt/keycloak/bin/kcadm.sh
$KC config credentials --server http://localhost:8080 --realm master --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null

user() { # benutzername vorname nachname gruppenpfade...
  name=$1; first=$2; last=$3; shift 3
  if ! $KC get users -r chattia -q username="$name" --fields id | grep -q id; then
    $KC create users -r chattia -s username="$name" -s firstName="$first" -s lastName="$last" \
      -s email="$name@example.org" -s emailVerified=true -s enabled=true
    $KC set-password -r chattia --username "$name" --new-password "Chattia-Test-2026!"
  fi
  id=$($KC get users -r chattia -q username="$name" --fields id --format csv --noquotes)
  for g in "$@"; do
    gid=$($KC get "group-by-path$g" -r chattia --fields id --format csv --noquotes 2>/dev/null || true)
    [ -n "$gid" ] && $KC update "users/$id/groups/$gid" -r chattia -s realm=chattia -s userId="$id" -s groupId="$gid" -n || echo "Gruppe $g nicht gefunden"
  done
  echo "Konto $name angelegt ($*)"
}

user senior    Max     Mustermann /aktivitas/burschen /chargen/senior /it-admins /website-redaktion
user fux       Felix   Fuchs      /aktivitas/fuechse
user altherr   Albert  Herr       /alte-herren /ahv-vorstand
