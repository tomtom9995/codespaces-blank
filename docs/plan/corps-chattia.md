# 15. Was im Umfeld des Corps Chattia zusätzlich Sinn ergibt

Stand: Oktober 2026 · Vorschläge, nach Nutzen und Aufwand sortiert. Alles baut auf dem zentralen Login, der Cloud und der App auf, also ohne neue Insellösungen.

## Leitgedanken

1. **Übergabefähig:** Die IT wechselt mit jeder Aktivengeneration. Alles muss dokumentiert, im Repository versioniert und von zwei Personen beherrschbar sein: einer Person aus der Aktivitas und einer aus dem AHV.
2. **Rollen statt Personen:** Ordner, Kalender und Rechte hängen an Chargen und Gruppen. Beim Semesterwechsel wird umgehängt, nicht neu gebaut.
3. **Datenschutz von Anfang an:** Mitgliederdaten, Fotos und Finanzen sind sensibel. EU-Hosting, Löschfristen, Einwilligungen.

## Übersicht

| # | Idee | Nutzen | Aufwand | Wann |
|---|---|---|---|---|
| 1 | **Chattia-Assistent**: KI beantwortet Fragen zu Satzung, Comment, Hausordnung, Semesterprogramm aus der Cloud | hoch | mittel | Phase 2 |
| 2 | Semesterprogramm als gemeinsamer Kalender + Anmeldung zu Veranstaltungen | hoch | gering | sofort |
| 3 | Amtsordner und Übergabe-Checklisten je Charge | hoch | gering | sofort |
| 4 | Mitgliederverzeichnis AHV mit Opt-in-Profilen, Adressänderungen per Self-Service | hoch | mittel | Phase 2 |
| 5 | Convent digital: Tagesordnung, Protokolle, Abstimmungen | mittel | gering | sofort |
| 6 | Kasse: Belege-Ordner, Beitragsübersicht, 10-Jahres-Archiv (steht) | hoch | gering | sofort |
| 7 | Kommunikation: Talk/Matrix statt WhatsApp für Amtliches, Rundmails, Push über die App | mittel | mittel | Phase 2 |
| 8 | Corpsarchiv digitalisieren: Fotos, Kneipzeitungen, Chronik | mittel | laufend | Projekt |
| 9 | Haus: Gästezimmer-Buchung, Getränke-Strichliste, Schlüssel/Zutritt | mittel | mittel–hoch | Phase 3 |
| 10 | Nachwuchs: Website-Kontakt → Keilgespräch, Zimmerangebot, Fuchsenprogramm in der App | hoch | mittel | Phase 2 |
| 11 | Datenschutz-Paket: Verarbeitungsverzeichnis, Löschkonzept, Einwilligungen | Pflicht | gering | sofort |
| 12 | IT-Notfallordner und Übergabeprotokoll | Pflicht | gering | sofort |

## 1. Chattia-Assistent – die App wird zum Wissensspeicher des Corps

Die Nova-App kann schon chatten und auf die Cloud zugreifen. Der nächste Schritt ist ein Assistent, der **nur aus den Dokumenten antwortet, die das Mitglied in der Cloud sehen darf**:

- „Wann ist das nächste Stiftungsfest und was ist der Dresscode?“ → Semesterprogramm
- „Was sagt der Comment zur Fuchsenzeit?“ → Comment-Ordner der Füxe
- „Wie hoch ist der AH-Beitrag und bis wann?“ → Beitragsordnung (nur AHV)
- Neue Chargen: „Was muss ich als Fuchsmajor bis Semesterbeginn erledigen?“ → Übergabe-Checkliste

Technik: Dokumente aus festgelegten Gruppenordnern werden mit dem Token des Nutzers gelesen (wie die Dateiansicht), in Abschnitte zerlegt und mit Quellenangabe an das Modell gegeben (RAG). Antworten verlinken die Fundstelle in der Cloud. Rechte bleiben die der Cloud, ein Fux sieht nie Vorstandsunterlagen. Modell: Claude über die API oder über Vertex AI in der EU, ohne Training mit den Daten.

## 2. Semesterprogramm und Veranstaltungen

- Ein Nextcloud-Kalender **„Semesterprogramm“** (bearbeitbar: Chargen; lesbar: alle) und **„Hausbelegung“**.
- Abo-Link (ICS) für Handy-Kalender; öffentliche Termine automatisch auf der Website (WordPress-Plugin liest ICS).
- Anmeldung zu Stiftungsfest, Kneipe, Ausflügen mit Nextcloud *Forms* oder als Funktion in der App (Zusage, Begleitung, Essenswahl). Erinnerung per Push.

## 3. Amtsordner und Übergabe

Je Charge ein **Gruppenordner** (App *Team folders/Groupfolders*): Senior, Consenior, Subsenior, Fuchsmajor, Kassenwart AHV, Hauswart. Darin: laufende Vorgänge, Vorlagen, **Übergabe-Checkliste** (Nextcloud *Deck*): Konten, Schlüssel, Fristen, Ansprechpartner, Passwörter im Passwortmanager (*Passwords*-App oder Vaultwarden mit SSO). Beim Chargenwechsel: Gruppen in Keycloak umhängen, fertig.

## 4. Mitgliederverzeichnis

- **Stammdaten** (Name, Anschrift, Geburtsdatum, Reception, Philistrierung) gehören in **ein** System des AHV, nicht in viele Excel-Listen. Optionen: CiviCRM (mächtig, aufwendig), *Admidio* (Open Source, Vereinsverwaltung, OIDC-fähig) oder schlank als geschütztes Nextcloud-Adressbuch.
- Empfehlung: **Admidio** mit Keycloak-Login: Mitglieder pflegen ihre Adresse selbst, Geburtstagsliste, Rundmails, Beitragsstatus. Datenexport für die Kösener Corpslisten bleibt möglich.
- **Opt-in-Profile** für das Netzwerk: Beruf, Branche, Stadt, „bin ansprechbar für Praktika/Mentoring“. Wer nicht will, bleibt unsichtbar.

## 5. Convent digital

- Tagesordnung und Anträge als gemeinsames Dokument (Nextcloud *Text/Collectives*), Protokoll direkt im Convent, Versand als PDF.
- Abstimmungen mit *Polls*. Rechtlich wichtig: Beschlüsse außerhalb einer Versammlung oder virtuelle Teilnahme sind beim e. V. nur zulässig, wenn Satzung oder § 32 BGB es erlauben. Vor der Nutzung für verbindliche Beschlüsse die Satzung prüfen bzw. anpassen.
- Protokolle wandern automatisch ins Jahresarchiv (unveränderbar, 10 Jahre).

## 6. Kasse

- Ordner **„Kasse/Belege/<Jahr>“** nur für AHV-Vorstand und Kassenprüfer; Scans per Nextcloud-App vom Handy.
- Beitragsübersicht und SEPA-Lastschriften: in Admidio oder einer Buchhaltung für Vereine (z. B. *Buchhaltung für Vereine* oder ein Steuerberater-Export).
- Das Jahresarchiv mit Bucket Lock erfüllt die Aufbewahrungspflichten (§ 147 AO) und löscht nach Ablauf automatisch.

## 7. Kommunikation

- WhatsApp-Gruppen bleiben für das Soziale. **Amtliches** (Convent, Kasse, Personalien) gehört in einen Kanal unter eigener Kontrolle: Nextcloud *Talk* (schon da, SSO) oder Matrix/Element mit Keycloak.
- Rundmails an AH: Listmonk oder Admidio, mit Abmeldelink und sauberem Absender (SPF/DKIM/DMARC für chattia.de einrichten).
- Push-Nachrichten über die Nova-App für Termine und Erinnerungen (Texte im CMS).

## 8. Corpsarchiv und Chronik

- Fotos (Kneipen, Stiftungsfeste, Mensuren nur mit Einwilligung!), Kneipzeitungen, Couleurkarten, Chroniken digitalisieren. Nextcloud *Memories* für Fotos mit Zeitleiste und Orten.
- Metadaten (Datum, Personen, Anlass) erfassen, damit spätere Generationen finden. Einwilligung für Personenfotos klären (Kunsturhebergesetz/DSGVO), interne Galerie getrennt von öffentlicher Website.
- Gehört ins Langzeitarchiv: Chronik, Mitgliederlisten historisch, Satzungsstände.

## 9. Haus

- **Gästezimmer** buchbar (Kalender „Hausbelegung“ oder Nextcloud *Appointments*), Belegung für Hauswart sichtbar.
- **Getränke-Strichliste** digital (Tablet am Kühlschrank, Abrechnung monatlich), z. B. mit einer kleinen App-Funktion oder Open-Source-Kassen wie *Spliit*.
- **Zutritt:** Digitale Schließzylinder (z. B. Nuki, Salto KS) mit Gruppen aus Keycloak: Zutritt endet automatisch mit Austritt, keine verlorenen Schlüssel mehr. Hoher Nutzen, aber Investition und Brandschutz/Versicherung vorher klären.

## 10. Nachwuchsgewinnung

- Website: modernes Kontaktformular („Zimmer frei“, „Komm zur Kneipe“), Anfragen landen als Karte im *Deck*-Board der Aktivitas, damit keine Anfrage verloren geht.
- Social Media mit Freigabeprozess (Website-Redaktion).
- **Fuchsenprogramm in der App:** Inhalte der Fuchsenstunden, Comment-Quiz, Geschichte des Corps, Ansprechpartner. Der Chattia-Assistent beantwortet Fragen rund um die Uhr.

## 11. Datenschutz-Paket (Pflicht)

- **Verzeichnis der Verarbeitungstätigkeiten** (Art. 30 DSGVO): Mitgliederverwaltung, Cloud, Website, App, Backups.
- **Auftragsverarbeitung:** Google Cloud (DPA im Konto akzeptieren), Mail-Anbieter, Offsite-Backup-Anbieter.
- **Löschkonzept:** Austritt → Konto deaktivieren, Daten nach Frist löschen. Backups laufen über die Aufbewahrungsregeln aus, Archiv nach 10 Jahren per Lifecycle.
- **Einwilligungen:** Fotos, Profil im Netzwerk, Newsletter. Datenschutzerklärung der Website und App aktualisieren.
- Datenschutzbeauftragter ist bei Vereinen meist **nicht** Pflicht (erst ab 20 Personen, die ständig Daten verarbeiten). Eine verantwortliche Person benennen.

## 12. IT-Notfallordner und Übergabe

Ein versiegelter Umschlag (Tresor Corpshaus) und ein Ordner **„IT-Übergabe“** (AHV-Vorstand + IT-Admins):

- Wer hat Zugang zu: Domain/DNS, Google-Cloud-Organisation (zwei Inhaber!), Backup-Projekt, Keycloak-Admin, Nextcloud-Notfall-Admin, WordPress-Notfall-Admin, Mail.
- Wo liegen: restic-Passwörter, Archiv-Schlüssel, Wiederherstellungscodes.
- Runbooks: [Backup/Restore](backup.md), [Umzug](nextcloud-wordpress.md), [zentraler Login](zentraler-login.md).
- **Übergabetermin** jedes Semester im Convent-Kalender: neue IT-Verantwortliche bekommen Zugänge, alte geben ab. Eine Probe-Wiederherstellung gehört dazu.

Die Domain und das Google-Cloud-Konto werden **auf den Verein** (AHV e. V. oder Hausverein) registriert, nicht auf eine Privatperson. Das ist der häufigste Grund, warum Vereine ihre IT verlieren.
