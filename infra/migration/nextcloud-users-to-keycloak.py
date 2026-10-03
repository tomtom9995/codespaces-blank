#!/usr/bin/env python3
"""
Übernimmt die Konten der alten Nextcloud in Keycloak (Phase 1 des Umzugs, docs/plan/nextcloud-wordpress.md).

Auf der ALTEN Instanz exportieren:
    sudo -u www-data php occ user:list --info --output=json > users.json

Dann (Standard = Probelauf, ändert nichts):
    python3 nextcloud-users-to-keycloak.py users.json --groups group-mapping.csv
    python3 nextcloud-users-to-keycloak.py users.json --groups group-mapping.csv --apply [--send-invites]

- Benutzername in Keycloak = bisheriger Nextcloud-Benutzername. Nur dann landen Dateien, Freigaben und Kalender
  nach dem Umzug beim richtigen Mitglied (user_oidc: mapping-uid=preferred_username, soft_auto_provision).
- Benutzernamen, die Keycloak so nicht abbilden kann (Großbuchstaben, Leerzeichen, Umlaute …), werden gemeldet
  und NICHT angelegt – sie müssen vorher geklärt werden (siehe Runbook).
- Gruppen werden über eine CSV-Datei zugeordnet:  alte_gruppe;/keycloak/gruppenpfad
- Mit --send-invites bekommt jedes neue Konto die Keycloak-Mail „Passwort festlegen“ (Link 72 h gültig).
- Ohne Abhängigkeiten (nur Python-Standardbibliothek).
"""
import argparse
import csv
import json
import os
import re
import ssl
import sys
import urllib.error
import urllib.parse
import urllib.request

VALID = re.compile(r"^[a-z0-9._@-]+$")
SKIP_BACKENDS = {"user_oidc"}  # schon über den zentralen Login angelegt


def load_users(path):
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    # occ liefert ein Objekt {uid: {...}}; Listen werden auch akzeptiert
    return list(data.values()) if isinstance(data, dict) else data


def load_mapping(path):
    mapping = {}
    if not path:
        return mapping
    with open(path, encoding="utf-8") as f:
        for row in csv.reader(f, delimiter=";"):
            if not row or row[0].startswith("#"):
                continue
            mapping.setdefault(row[0].strip(), []).extend(p.strip() for p in row[1:] if p.strip())
    return mapping


def split_name(display, uid):
    parts = (display or "").strip().split()
    if len(parts) >= 2:
        return " ".join(parts[:-1]), parts[-1]
    return (parts[0] if parts else uid), ""


def plan(users, mapping):
    """Liefert (anlegen, problematisch, übersprungen) ohne Netzwerkzugriff – testbar."""
    create, problems, skipped = [], [], []
    for u in users:
        uid = u.get("user_id") or u.get("id")
        if u.get("backend") in SKIP_BACKENDS:
            skipped.append((uid, "bereits über zentralen Login"))
            continue
        if not u.get("enabled", True):
            skipped.append((uid, "deaktiviert"))
            continue
        if not VALID.match(uid or ""):
            problems.append((uid, "Benutzername nicht in Keycloak abbildbar (nur a-z 0-9 . _ - @)"))
            continue
        if not u.get("email"):
            problems.append((uid, "keine E-Mail-Adresse – Einladung unmöglich"))
            continue
        first, last = split_name(u.get("display_name"), uid)
        groups = sorted({p for g in u.get("groups", []) for p in mapping.get(g, [])})
        unmapped = [g for g in u.get("groups", []) if g not in mapping and g != "admin"]
        create.append({"username": uid, "email": u["email"], "firstName": first, "lastName": last,
                       "groups": groups, "unmapped": unmapped})
    return create, problems, skipped


class Keycloak:
    def __init__(self, url, realm, user, password, ca=None):
        self.url, self.realm = url.rstrip("/"), realm
        self.ctx = ssl.create_default_context(cafile=ca) if ca else ssl.create_default_context()
        token = self._call("POST", f"{self.url}/realms/master/protocol/openid-connect/token",
                           form={"grant_type": "password", "client_id": "admin-cli", "username": user, "password": password})
        self.token = token["access_token"]

    def _call(self, method, url, body=None, form=None):
        headers = {}
        data = None
        if form is not None:
            data = urllib.parse.urlencode(form).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        elif body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if getattr(self, "token", None):
            headers["Authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        with urllib.request.urlopen(req, context=self.ctx) as r:
            text = r.read().decode()
            return json.loads(text) if text else None

    def admin(self, method, path, body=None):
        return self._call(method, f"{self.url}/admin/realms/{self.realm}{path}", body=body)

    def find_user(self, username):
        found = self.admin("GET", f"/users?exact=true&username={urllib.parse.quote(username)}")
        return found[0] if found else None

    def group_id(self, path, cache={}):
        if path not in cache:
            cache[path] = self.admin("GET", f"/group-by-path{urllib.parse.quote(path)}")["id"]
        return cache[path]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("users_json")
    ap.add_argument("--groups", help="CSV: alte_gruppe;/keycloak/pfad[;/weiterer/pfad]")
    ap.add_argument("--keycloak", default=os.environ.get("KEYCLOAK_URL", "https://auth.chattia.internal:8443"))
    ap.add_argument("--realm", default="chattia")
    ap.add_argument("--admin-user", default=os.environ.get("KEYCLOAK_ADMIN_USER", "kc-admin"))
    ap.add_argument("--admin-password", default=os.environ.get("KEYCLOAK_ADMIN_PASSWORD"))
    ap.add_argument("--ca", help="CA-Zertifikat (lokal: infra/hosting/caddy-local-root.crt)")
    ap.add_argument("--apply", action="store_true", help="wirklich anlegen (sonst Probelauf)")
    ap.add_argument("--send-invites", action="store_true", help="Mail „Passwort festlegen“ an neue Konten")
    args = ap.parse_args()

    create, problems, skipped = plan(load_users(args.users_json), load_mapping(args.groups))
    print(f"{len(create)} Konten zu übernehmen, {len(problems)} zu klären, {len(skipped)} übersprungen\n")
    for uid, why in problems:
        print(f"  ✗ {uid}: {why}")
    for uid, why in skipped:
        print(f"  – {uid}: {why}")
    for u in create:
        extra = f"   (Gruppe ohne Zuordnung: {', '.join(u['unmapped'])})" if u["unmapped"] else ""
        print(f"  + {u['username']:<20} {u['email']:<32} {' '.join(u['groups'])}{extra}")

    if not args.apply:
        print("\nProbelauf – nichts geändert. Mit --apply anlegen.")
        return 1 if problems else 0
    if not args.admin_password:
        sys.exit("KEYCLOAK_ADMIN_PASSWORD bzw. --admin-password fehlt")

    kc = Keycloak(args.keycloak, args.realm, args.admin_user, args.admin_password, args.ca)
    created = existing = 0
    for u in create:
        user = kc.find_user(u["username"])
        if user is None:
            kc.admin("POST", "/users", {
                "username": u["username"], "email": u["email"], "emailVerified": True, "enabled": True,
                "firstName": u["firstName"], "lastName": u["lastName"],
            })
            user = kc.find_user(u["username"])
            created += 1
            if args.send_invites:
                kc.admin("PUT", f"/users/{user['id']}/execute-actions-email?lifespan=259200", ["UPDATE_PASSWORD"])
        else:
            existing += 1
        for path in u["groups"]:
            kc.admin("PUT", f"/users/{user['id']}/groups/{kc.group_id(path)}")
    print(f"\nFertig: {created} angelegt, {existing} bereits vorhanden (Gruppen ergänzt).")
    return 1 if problems else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except urllib.error.HTTPError as e:
        sys.exit(f"Keycloak-Fehler {e.code}: {e.read().decode()[:300]}")
