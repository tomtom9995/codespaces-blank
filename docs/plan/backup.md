# 14. Datensicherung: Nextcloud, WordPress, zentraler Login

Stand: Oktober 2026 · Umsetzung: [`infra/backup/`](../../infra/backup/), [`infra/terraform/backup/`](../../infra/terraform/backup/)

## Ziele

| Kennzahl | Ziel | Bedeutung |
|---|---|---|
| **RPO** (max. Datenverlust) | 24 h, für einzelne Dateien praktisch 0 | Tägliche Sicherung; gelöschte Dateien liegen zusätzlich 30–90 Tage im Nextcloud-Papierkorb |
| **RTO** (Zeit bis wieder online) | 4 h für die ganze Plattform, 15 min für einzelne Dateien | Neue VM, Images ziehen, Sicherung einspielen – Ablauf unten |
| **Aufbewahrung** | 14 Tage täglich, 8 Wochen wöchentlich, 24 Monate monatlich, 10 Jahre jährlich | Großvater-Vater-Sohn |
| **Langzeitarchiv** | 10 Jahre unveränderbar | Kasse, Protokolle, Satzung, Mitgliederunterlagen des Vereins (§ 147 AO, § 257 HGB sinngemäß) |

## Regel 3-2-1-1-0

| | Umsetzung |
|---|---|
| **3** Kopien | Live-Daten auf der VM · primäres Backup in Google Cloud Storage · Kopie bei einem zweiten Anbieter |
| **2** Medien/Techniken | Persistent Disk der VM + Objektspeicher (zusätzlich Disk-Snapshots als schnelle Rückfallebene) |
| **1** außer Haus | Zweitkopie bei anderem Anbieter in anderer Region (z. B. Backblaze B2 Amsterdam oder Hetzner Storage Box) |
| **1** unveränderbar | Server darf im Backup-Bucket nichts löschen oder überschreiben; Soft Delete 30 Tage; Jahresarchiv mit Bucket Lock |
| **0** Fehler | Jeder Lauf prüft 2 % der Daten, der Wartungsjob wöchentlich 10 %, monatlich ein automatischer Wiederherstellungstest |

## Ebenen – von „schnell selbst“ bis „Katastrophe“

```
Ebene 1  Nextcloud-Papierkorb + Versionen     Nutzer stellt selbst wieder her   30–90 Tage / bis 365 Tage
Ebene 2  Disk-Snapshots der VM (täglich)       ganze VM in Minuten zurück        14 Tage
Ebene 3  restic → GCS (eigenes Projekt)        Dateien, Datenbanken, Konfig.     GFS bis 10 Jahre
Ebene 4  restic copy → zweiter Anbieter        falls Google-Konto/Projekt weg    wie Ebene 3
Ebene 5  Jahresarchiv, age-verschlüsselt       Vereinsunterlagen, unveränderbar  10 Jahre (Bucket Lock)
```

Ebene 1 deckt erfahrungsgemäß über 90 % der Fälle („Ich habe den Ordner gelöscht“) ab – ohne Admin.

## Was wird gesichert?

| Daten | Wie | Warum so |
|---|---|---|
| Nextcloud-Datenbank | `pg_dump -Fc` im **Wartungsmodus** (wenige Sekunden) | Datenbank und Dateien passen zusammen |
| Keycloak-Datenbank | `pg_dump -Fc` | Konten, Gruppen, Passkeys, 2FA – ohne sie kommt niemand mehr rein |
| WordPress-Datenbank | `mariadb-dump --single-transaction` | konsistent ohne Sperre |
| Nextcloud-Dateien | restic, ohne Vorschaubilder und Caches | lassen sich neu erzeugen |
| Nextcloud-Programmverzeichnis | restic (vollständig, dedupliziert) | `config.php` mit Secret und Instanz-ID; Restore landet exakt auf derselben Version |
| WordPress `wp-content` | restic | Uploads, Theme, Plugins |
| Konfiguration des Stacks | Git (dieses Repository) | Geheimnisse liegen im Secret Manager, nicht im Backup-Skript |

restic verschlüsselt (AES-256), dedupliziert und komprimiert. Ein unveränderter Datenbestand von 200 GB belegt nach einem Jahr typischerweise 250–350 GB im Repository.

## Rechte – der Server kann seine Backups nicht vernichten

Ransomware oder ein Angreifer auf der VM versucht zuerst, die Backups zu löschen. Deshalb:

- Backups liegen in einem **eigenen Google-Cloud-Projekt** `chattia-backup`. Kein Betriebs-Admin hat dort Rechte; Zugang nur für zwei benannte Personen mit Hardware-Schlüssel.
- Das Dienstkonto der VM darf im Backup-Bucket **nur anlegen und lesen** (`objectCreator` + `objectViewer`). In GCS braucht Überschreiben die Löschberechtigung, also kann es auch nichts überschreiben. Einzige Ausnahme sind restic-Sperrdateien unter `restic/locks/`, abgesichert über eine IAM-Bedingung.
- Aufräumen (`restic forget --prune`) macht ein **Wartungsjob** (Cloud Run Job, wöchentlich) mit eigenem Dienstkonto (`maintenance.sh`). Auf der VM gilt `BACKUP_FORGET_ENABLED=0`.
- **Soft Delete 30 Tage** auf dem Bucket: Selbst was der Wartungsjob (oder ein Angreifer mit dessen Rechten) löscht, ist noch 30 Tage wiederherstellbar.
- **Jahresarchiv** mit **Bucket Lock** (10 Jahre): Nicht einmal der Projektinhaber kann löschen. Die Frist wird erst nach einem Probelauf gesperrt (`lock_archive_retention = true`), weil sich eine gesperrte Frist nie mehr verkürzen lässt.

Alles als Terraform in [`infra/terraform/backup/main.tf`](../../infra/terraform/backup/main.tf).

## Schlüssel – und wer sie hat

| Schlüssel | Wo | Notfallzugriff |
|---|---|---|
| restic-Passwort (primär) | Secret Manager im Backup-Projekt | ausgedruckt im versiegelten Umschlag, Tresor des Corpshauses |
| restic-Passwort (Offsite) | anderes Passwort, Passwortmanager des AHV-Vorstands | zweiter Umschlag bei einer zweiten Person (z. B. Rechtsanwalt im AHV) |
| Archiv-Schlüssel (age, privat) | **nur offline**: YubiKey (age-plugin-yubikey) + Papier-Backup | Tresor; Server kennt nur den öffentlichen Schlüssel |

Regel „zwei Personen, zwei Orte“: Fällt der IT-Verantwortliche aus (Wechsel der Aktivitas, Krankheit), kommt der AHV-Vorstand an alles heran. Ohne diese Regel ist ein verschlüsseltes Backup im Ernstfall wertlos. Die Umschläge werden bei jeder Schlüsseländerung erneuert und beim Chargenwechsel kontrolliert.

## Zeitplan (Container `backup`, alle Zeiten Europe/Berlin)

| Wann | Was | Skript |
|---|---|---|
| täglich 02:15 | Datenbanken + Dateien → primäres Repository, 2 % Datenprüfung | `backup.sh` |
| sonntags 05:00 | Kopie aller neuen Snapshots zum zweiten Anbieter | `offsite-copy.sh` |
| sonntags 06:00 (Cloud Run Job) | Aufbewahrung anwenden, 10 % Datenprüfung | `maintenance.sh` |
| 1. des Monats 04:30 | Wiederherstellungstest: Datenbanken in Test-DB einspielen, Dateien zählen, Prüfdatei vergleichen | `restore-test.sh` |
| 2. Januar 06:00 | Jahresarchiv der Vereinsordner, verschlüsselt, in den gesperrten Bucket | `archive.sh` |
| jährlich (Termin im Semesterplan) | **Notfallübung**: komplette Wiederherstellung auf einer frischen VM nach Runbook, Zeit stoppen | Mensch |

## Überwachung

- Jeder Lauf meldet Start, Erfolg oder Fehler an einen Heartbeat-Dienst (`BACKUP_HEALTHCHECK_URL`, z. B. healthchecks.io oder Cloud Monitoring). **Bleibt die Meldung 26 Stunden aus, geht eine Mail an IT-Admins und AHV-Vorstand** – auch wenn der Server ganz ausgefallen ist.
- Monatlicher Bericht (Mail): Anzahl Snapshots, Größe, Ergebnis des Wiederherstellungstests.
- Cloud-Audit-Logs im Backup-Projekt: Alarm bei jeder Löschung außerhalb des Wartungsjobs.

## Wiederherstellen – Runbooks

**A) Eine Datei oder ein Ordner ist weg**
1. Nutzer: Nextcloud → *Gelöschte Dateien* bzw. *Versionen*. Fertig in 90 % der Fälle.
2. Sonst Admin:
   ```bash
   docker compose exec backup restic snapshots --tag nextcloud-files
   docker compose exec backup restic restore <snapshot> --target /tmp/r --include /sources/nextcloud-data/<nutzer>/files/<pfad>
   docker compose cp backup:/tmp/r/sources/nextcloud-data/<nutzer>/files/<pfad> ./wiederhergestellt
   # dann per Web-Upload oder: docker compose cp … nextcloud:/var/www/html/data/<nutzer>/files/… && occ files:scan <nutzer>
   ```

**B) Ganze Plattform verloren (VM weg, Projekt kompromittiert)** – automatisiert mit [`restore-platform.sh`](../../infra/backup/restore-platform.sh)
```bash
# neue VM (infra/terraform/hosting) bzw. leere Volumes; .env aus dem Secret Manager
docker compose up -d --wait postgres mariadb
docker compose --profile restore run --rm restore        # Datenbanken + Nextcloud + WordPress aus dem letzten Stand
docker compose up -d
docker compose exec -u www-data nextcloud php occ maintenance:data-fingerprint   # Sync-Clients gleichen sauber ab
docker compose exec -u www-data nextcloud php occ files:scan --all
```
Das Skript bricht ab, wenn Volumes nicht leer sind (Schutz vor versehentlichem Überschreiben). DNS auf die neue IP; Let's Encrypt holt die Zertifikate neu. Ist das Google-Konto selbst nicht nutzbar: dasselbe mit `RESTIC_REPOSITORY=$OFFSITE_REPOSITORY` und dem Offsite-Passwort.

**C) Ransomware hat Dateien verschlüsselt und synchronisiert**
Snapshot von *vor* dem Befall wählen (`restic snapshots`, `restic diff <alt> <neu>` zeigt massenhaft geänderte Dateien), Nextcloud in den Wartungsmodus, betroffene Nutzerordner aus dem alten Snapshot zurückspielen, `occ files:scan`, Clients danach neu verbinden. Die Backups selbst sind nicht betroffen: Der Server kann sie nicht ändern.

## Kosten (Richtwerte, 200 GB Nutzdaten)

| Posten | ca. pro Monat |
|---|---|
| GCS Standard, ~300 GB inkl. Versionen | 6 € |
| Zweiter Anbieter (Backblaze B2 / Hetzner Storage Box 1 TB) | 2–4 € |
| Jahresarchiv (GCS Archive, wachsend) | < 1 € |
| Disk-Snapshots (inkrementell) | 2–4 € |
| **Summe** | **ca. 10–15 €** |

## Getestet (lokal, 3. Oktober 2026)

Ablauf mit dem Stack aus `infra/hosting` und den Skripten aus `infra/backup`:

```
backup.sh          4 Snapshots (databases 2,2 MiB · nextcloud-files 60,6 MiB · config 18 MiB · wordpress 14,4 MiB) in 13 s,
                   Nextcloud nur für den Datenbank-Dump im Wartungsmodus
Datenverlust       Satzung überschrieben, Foto (3 MB) gelöscht, zweites Backup
Wiederherstellung  beide Dateien aus dem ersten Snapshot zurück – SHA-256 identisch mit dem Original
restore-test.sh    Dumps lesbar, Nextcloud-DB in Test-DB eingespielt (3 SSO-Konten, 96 Dateien), WordPress-Dump ok → BESTANDEN
offsite-copy.sh    zweites Repository mit eigenem Passwort angelegt, alle Snapshots kopiert
archive.sh         Ordner Corps als .tar.zst.age; Entschlüsseln nur mit dem privaten Schlüssel möglich, Inhalt vollständig
Sperren            verwaiste restic-Sperre aus abgebrochenem Lauf → backup.sh räumt sie jetzt selbst auf
```

## Notfallübung (lokal, 3. Oktober 2026)

Kompletter Verlust simuliert: `docker compose down -v` löscht alle Datenbanken, Cloud-Dateien, die Website und die Zertifikate. Übrig bleibt nur das Backup-Repository.

| Schritt | Ergebnis |
|---|---|
| Wiederherstellung (Datenbanken, 31 000 Dateien Nextcloud, Daten, WordPress) | 11 s |
| Plattform wieder erreichbar (inkl. Start von Keycloak und Nextcloud) | **95 s** |
| Vergleich mit dem Stand vor dem Verlust: Konten, Team-Ordner, SHA-256 aller Dokumente, Keycloak-Konten, Website | **identisch** |
| SSO-Test (Keycloak → Nextcloud → WordPress, zweiter Faktor für Ämter) | bestanden |
| App-Test (Login mit Chattia-Konto, Dateien, Rechte der Team-Ordner, Assistent) | bestanden |

Die gemessene Zeit gilt für lokale Daten. In Produktion bestimmt die Download-Geschwindigkeit aus GCS die Dauer (200 GB bei ~100 MB/s ≈ 35 min). Das RTO-Ziel von 4 Stunden ist damit gut erreichbar. Die Übung gehört einmal im Jahr auf eine frische VM (Abschnitt Zeitplan).

## Offene Punkte vor dem Echtbetrieb

1. Backup-Projekt anlegen, Terraform anwenden, Probelauf, dann Bucket Lock aktivieren.
2. Zweiten Anbieter wählen und Konto **auf den Verein** (nicht auf eine Privatperson) anlegen.
3. Archiv-Schlüssel erzeugen (`age-keygen` bzw. YubiKey), Papier-Backups in die Umschläge.
4. Welche Ordner ins Jahresarchiv gehören, legt der AHV-Vorstand fest (Kasse, Protokolle der Convente, Satzung, Mitgliederverzeichnis); DSGVO: Löschung nach Fristablauf ist per Lifecycle-Regel eingebaut.
5. Erste Notfallübung terminieren.
