# CMS-Inhalte

Startfassung aller Texte der App für Strapi. Konzept und Inhaltsmodell: [docs/plan/cms.md](../docs/plan/cms.md).

| Datei | Inhalt |
|---|---|
| `content/de/settings.json` | App-Name, Domain, erlaubte Link-Domains, Beispielwerte für die Prüfung |
| `content/de/ui-texts.json` | Alle Texte der App-Oberfläche |
| `content/de/onboarding-steps.json` | Onboarding-Bildschirme |
| `content/de/email-templates.json` | Alle E-Mails (Onboarding-Strecke und Sicherheit) |
| `content/de/sms-templates.json` | SMS (GSM-7, max. 160 Zeichen, keine Links) |
| `content/de/voice-templates.json` | Ansage für Code-Anrufe |
| `content/de/push-templates.json` | Push-Benachrichtigungen |
| `content/de/roles.json` | Rollennamen, Beschreibungen und Rechte |

Platzhalter stehen in `{geschweiften}` Klammern und müssen im Feld `placeholders` des Eintrags stehen (Ausnahme: `{appName}`, `{domain}`, `{supportEmail}`).

## Prüfen

```bash
node cms/scripts/validate-content.mjs
```

Läuft automatisch in der CI-Pipeline (`.github/workflows/content.yml`).

## Strapi

Das CMS liegt in `strapi/`. Beim Start (`docker compose up`) werden automatisch:
- die Sprache Deutsch angelegt und alle Dateien aus `content/de/` importiert (nur wenn noch leer),
- die Admin-Rollen „Redaktion“ und „Sicherheitsfreigabe“ angelegt,
- lokal ein Admin-Konto angelegt (`STRAPI_ADMIN_EMAIL` / `STRAPI_ADMIN_PASSWORD`),
- das Backend bei jeder Änderung benachrichtigt (Cache leeren → Text sofort in der App).

Lokal ohne Docker: `cd strapi && npm install && npm run develop` (Datenbank-Variablen siehe `strapi/.env.example`).
