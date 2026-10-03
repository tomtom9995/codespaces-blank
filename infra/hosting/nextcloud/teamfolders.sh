#!/bin/bash
# Legt die Team-Ordner aus teamfolders.conf an bzw. gleicht die Rechte ab (wiederholbar).
# Aufruf: docker compose exec -u www-data nextcloud bash /chattia/config/../teamfolders.sh  (siehe docs/plan/zentraler-login.md)
set -euo pipefail
CONF=${1:-/chattia/teamfolders.conf}
occ() { php /var/www/html/occ "$@"; }

occ app:enable groupfolders >/dev/null
existing=$(occ groupfolders:list --output=json)
folder_id() { # Name → ID (leer, wenn es den Ordner noch nicht gibt)
  php -r '$n=$argv[1]; foreach (json_decode($argv[2], true) as $f) { if (($f["mountPoint"] ?? $f["mount_point"] ?? null) === $n) { echo $f["id"]; exit; } }' "$1" "$existing"
}
perms() { # rwds → read write delete share
  local out=()
  [[ $1 == *r* ]] && out+=(read)
  [[ $1 == *w* ]] && out+=(write)
  [[ $1 == *d* ]] && out+=(delete)
  [[ $1 == *s* ]] && out+=(share)
  echo "${out[@]}"
}

while IFS='|' read -r name rest; do
  name=$(echo "$name" | xargs)
  [[ -z $name || $name == \#* ]] && continue
  id=$(folder_id "$name")
  if [[ -z $id ]]; then
    id=$(occ groupfolders:create "$name")
    echo "Team-Ordner angelegt: $name (#$id)"
  fi
  IFS='|' read -r -a entries <<< "$rest"
  for entry in "${entries[@]}"; do
    entry=$(echo "$entry" | xargs)
    [[ -z $entry ]] && continue
    group=${entry%%:*}
    occ group:info "$group" >/dev/null 2>&1 || occ group:add "$group" >/dev/null
    # shellcheck disable=SC2046
    occ groupfolders:group "$id" "$group" $(perms "${entry#*:}") >/dev/null
  done
  echo "  $name: ${rest//  / }"
done < "$CONF"
