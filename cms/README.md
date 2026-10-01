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

## Nächste Schritte

1. Strapi-5-Projekt in `cms/` anlegen, Inhaltstypen nach `docs/plan/cms.md` als Code
2. Import-Skript: lädt diese Dateien einmalig in Strapi
3. Export-Pipeline: veröffentlichte Texte zurück nach Git (Pull Request)
