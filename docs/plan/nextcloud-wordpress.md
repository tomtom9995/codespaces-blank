# 13. Umzug: bestehende Nextcloud und WordPress-Visitenkarte in die Cloud

Stand: Oktober 2026 · Zielaufbau: [`infra/hosting/docker-compose.yml`](../../infra/hosting/docker-compose.yml)

## Zielbild

Eine VM in Frankfurt mit Docker Compose: wenig Teile, wenig Kosten, gut zu übergeben an die nächste IT-Generation der Aktivitas.

```
              Internet ── DNS: auth., cloud., api., chattia.de
                 │
   ┌─────────────▼──────────────────────────────────────────────────────────┐
   │ GCE-VM e2-standard-2 (2 vCPU, 8 GB) · europe-west3 · Container-Optimized│
   │                                                                        │
   │  Caddy (TLS Let's Encrypt, Sicherheits-Header)                         │
   │   ├─ auth.   → Keycloak 26                                             │
   │   ├─ cloud.  → Nextcloud 32 (Apache) + Cron-Container + Redis          │
   │   ├─ chattia.de → WordPress 6                                          │
   │   └─ api.    → Nova-Backend (oder Cloud Run, siehe cicd-gcp.md)        │
   │  PostgreSQL 17 (Keycloak, Nextcloud) · MariaDB 11 (WordPress)          │
   │  backup (restic, Zeitplan) ─────────────► GCS im Backup-Projekt         │
   │                                                                        │
   │  Disks: Boot 20 GB · Daten (pd-balanced, 300 GB, Snapshots täglich)     │
   └────────────────────────────────────────────────────────────────────────┘
```

| Entscheidung | Gewählt | Alternative | Warum |
|---|---|---|---|
| Rechner | 1 VM + Docker Compose | GKE/Kubernetes | Für < 300 Nutzer reicht eine VM; Kubernetes wäre Overhead für eine Ehrenamts-IT |
| Datenbank | PostgreSQL im Container | Cloud SQL (~30 €/Monat mehr) | Backup per Dump reicht; Cloud SQL lohnt erst bei Hochverfügbarkeit |
| Dateien | Persistent Disk | GCS als Primärspeicher (S3) | Einfacher, schneller, Backups/Snapshots klar; GCS-Primärspeicher erschwert Restores |
| TLS | Caddy + Let's Encrypt | Load Balancer + Google-Zertifikate | Kein LB nötig (~18 €/Monat gespart) |
| Website | WordPress weiter betreiben | statischer Export | Redaktion bleibt gewohnt; statischer Export (Simply Static) ist Option für noch weniger Angriffsfläche |

**Kosten (Richtwert):** VM e2-standard-2 ca. 50 €, 300 GB pd-balanced ca. 30 €, Snapshots + Backup ca. 15 €, ausgehender Traffic gering, **gesamt ca. 95 €/Monat**. Mit 1-Jahres-Commitment ca. 70 €. Kleiner geht es mit e2-medium (4 GB), wenn wenige gleichzeitig arbeiten.

**Aufbau per Terraform** ([`infra/terraform/hosting`](../../infra/terraform/hosting/main.tf)): feste IP, Firewall nur 80/443, SSH nur über IAP mit OS Login, VM mit Shielded VM, Datendisk mit täglichen Snapshots (14 Tage) und `prevent_destroy`, Dienstkonto nur mit Secret- und Log-Rechten. Das Startskript hängt die Datendisk ein, installiert Docker (Datenverzeichnis auf der Datendisk), holt das Repository, schreibt die `.env` aus dem Secret `chattia-hosting-env` und startet den Stack.

```bash
cd infra/terraform/backup  && terraform apply -var project_id=chattia-backup -var server_service_account=chattia-vm@chattia-prod.iam.gserviceaccount.com
cd infra/terraform/hosting && terraform apply -var project_id=chattia-prod -var repo_url=https://github.com/<org>/<repo>.git -var 'admin_members=["user:it@chattia.de"]'
gcloud secrets create chattia-hosting-env --data-file=infra/hosting/.env.prod   # echte Domains, Geheimnisse, BACKUP_FORGET_ENABLED=0
```

Sicherheit der VM: kein SSH aus dem Internet (IAP-Tunnel), automatische Updates, Firewall nur 80/443, Keycloak-Admin nur über IAP/VPN (`Caddyfile` blockiert `/admin` von außen), Dienstkonto der VM ohne Löschrecht auf Backups.

## Umzug Nextcloud – Ablauf

### Phase 0: Bestandsaufnahme (1 Abend)

```bash
# auf der alten Instanz
sudo -u www-data php occ status                 # Version! (Upgrade nur Hauptversion für Hauptversion)
sudo -u www-data php occ app:list               # welche Apps sind in Gebrauch?
sudo -u www-data php occ user:list --output=json > users.json
sudo -u www-data php occ group:list --output=json > groups.json
du -sh /pfad/zu/data/*                          # Datenmenge je Nutzer
sudo -u www-data php occ config:list system     # Speicher, Verschlüsselung, externe Speicher?
```

Prüffragen: Ist **serverseitige Verschlüsselung** aktiv? (Dann vorher `occ encryption:decrypt-all`, sonst hängt alles am alten Schlüssel.) Gibt es **externe Speicher**, **Talk**, **Kalender/Kontakte** (sind in der Datenbank, ziehen mit um)? Welche **Datenbank** (MySQL/MariaDB → Wechsel auf PostgreSQL mit `occ db:convert-type` möglich)?

### Phase 1: Konten abgleichen

Die neue Cloud kennt nur Chattia-Konten. Damit bestehende Daten, Freigaben und Kalender beim richtigen Mitglied landen, **muss der Keycloak-Benutzername dem alten Nextcloud-Benutzernamen entsprechen** (`mapping-uid=preferred_username`, `unique-uid=0`, `soft_auto_provision=true` sind so eingestellt).

1. Aus `users.json` eine Liste erstellen: alter Benutzername, Anzeigename, E-Mail, Gruppen.
2. Konten in Keycloak anlegen (Skript nach dem Muster von `keycloak/dev-users.sh` oder CSV-Import über die Admin-API), Gruppen gemäß [Rollentabelle](zentraler-login.md#gruppen--rollen-im-corps).
3. Nextcloud kann Benutzernamen nicht umbenennen. Alte Namen mit Großbuchstaben, Leerzeichen oder Sonderzeichen passen nicht zu Keycloak (nur Kleinbuchstaben, Ziffern, `.-_@`). Solche Konten vorab klären: entweder Daten in der alten Instanz an ein neues Konto übertragen (`occ files:transfer-ownership`) oder in Keycloak eine passende Schreibweise wählen und testen.
4. Einladungsmails erst zum Umzugstag verschicken („Passwort setzen“).

### Phase 2: Probelauf (Wochenende vorher)

1. Neue VM mit dem Stack aufsetzen, gleiche Nextcloud-**Hauptversion** wie alt (bei Bedarf `image: nextcloud:<alt>-apache`, danach schrittweise hochziehen).
2. Datenbank-Dump und Daten kopieren (wie Phase 3), Testzugriff über `/etc/hosts`.
3. Mit 2–3 Testpersonen prüfen: Dateien, Freigaben, Kalender, Desktop-Client.
4. **Zeit messen** → daraus das Wartungsfenster planen.

### Phase 3: Umzug (Wartungsfenster, typischerweise Sonntagabend)

```bash
# 48 h vorher: DNS-TTL von cloud. auf 300 s senken

# alt: Wartungsmodus, letzter Stand
sudo -u www-data php occ maintenance:mode --on
pg_dump -Fc nextcloud > nextcloud.pgdump          # bzw. mysqldump --single-transaction
rsync -aHAX --info=progress2 /pfad/zu/data/ neu:/mnt/data/nextcloud-data/

# neu: einspielen (Volumes gestoppt)
docker compose stop nextcloud nextcloud-cron
pg_restore --clean --if-exists -d nextcloud nextcloud.pgdump
# config.php: instanceid, passwordsalt, secret aus der ALTEN config.php übernehmen
docker compose up -d nextcloud
docker compose exec -u www-data nextcloud php occ upgrade
docker compose exec -u www-data nextcloud php occ maintenance:data-fingerprint
docker compose exec -u www-data nextcloud php occ files:scan --all
docker compose exec -u www-data nextcloud php occ db:add-missing-indices
docker compose exec -u www-data nextcloud php occ maintenance:mode --off

# DNS umstellen, Let's Encrypt holt das Zertifikat automatisch
```

Vorschaltrunde für große Datenmengen: `rsync` schon Tage vorher laufen lassen, im Wartungsfenster nur noch die Änderungen.

### Phase 4: Danach

- Desktop- und Handy-Clients: Server-Adresse bleibt gleich, Anmeldung jetzt über Keycloak (Clients zeigen den Browser-Login). Alte App-Passwörter verfallen.
- Erstes Backup sofort manuell starten und den **Wiederherstellungstest** laufen lassen.
- Alte Instanz 30 Tage **nur lesend** behalten (Wartungsmodus), dann Daten sicher löschen.
- **Rückfallplan:** Bis zur DNS-Umstellung kann jederzeit abgebrochen werden: alte Instanz aus dem Wartungsmodus nehmen, fertig.

## Umzug WordPress – Ablauf

Die Visitenkartenseite ist klein, also ein einfacher Umzug:

```bash
# alt
wp db export chattia.sql
tar czf wp-content.tgz wp-content/uploads wp-content/themes/<theme>     # Plugins neu installieren statt kopieren

# neu
docker compose --profile tools run --rm wpcli                           # Grundinstallation + OIDC-Plugin
docker compose cp chattia.sql wordpress:/var/www/html/chattia.sql
docker compose run --rm --entrypoint wp wpcli db import chattia.sql && docker compose exec wordpress rm chattia.sql
docker compose run --rm --entrypoint wp wpcli search-replace 'https://alte-domain.de' 'https://chattia.de' --all-tables --precise
docker compose run --rm --entrypoint wp wpcli plugin install <liste aus "wp plugin list" der alten Seite> --activate
```

Dabei gleich aufräumen:
- **Nur noch SSO** für die Redaktion (`website-redaktion`), lokale Admin-Konten bis auf einen Notfall-Admin löschen.
- `DISALLOW_FILE_EDIT` ist gesetzt (kein Code-Editor im Backend), XML-RPC ist aus.
- Nicht genutzte Plugins und Themes nicht mitnehmen. Jedes Plugin ist Angriffsfläche.
- Optional: Seite mit *Simply Static* als statische Kopie ausliefern und WordPress nur intern für die Redaktion erreichbar machen.

## Checkliste Umzugstag

- [ ] DNS-TTL gesenkt (48 h vorher)
- [ ] Konten in Keycloak angelegt, Benutzernamen = alte Nextcloud-Namen
- [ ] Probelauf erfolgreich, Zeit gemessen
- [ ] Mitglieder informiert (Rundmail + WhatsApp-Gruppe): Zeitfenster, „neuer Login“, Anleitung mit Screenshots
- [ ] Wartungsmodus alt → Dump → rsync → Einspielen → `occ upgrade` → Scan
- [ ] DNS umgestellt, Zertifikate da
- [ ] Stichprobe mit 3 Mitgliedern (Aktivitas, AH, Charge)
- [ ] Backup + Wiederherstellungstest gelaufen
- [ ] Alte Instanz schreibgeschützt, Löschtermin im Kalender
