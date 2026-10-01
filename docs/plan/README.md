# Plan: Native KI-Assistenz-App für iOS und Android (Kotlin)

Stand: Oktober 2026 · Arbeitstitel **„Nova“** (Platzhalter, im CMS unter *Einstellungen* änderbar)

Eine App, die wie ChatGPT oder Claude funktioniert. Sie wird nativ für iOS und Android gebaut, steht auf Open-Source-Bausteinen und läuft auf der Google Cloud. Sicherheit gegen Social Engineering ist von Anfang an Teil der Architektur und wird nicht nachgerüstet.

## Inhalt

| # | Abschnitt | Datei |
|---|---|---|
| 1–7 | Grundplan: Ziel, Architektur, Open-Source-Basis, Funktionen, Technologien, Phasen, Risiken | diese Datei |
| 8 | Sicherheit: Anmeldung, Zwei-Faktor, Standort- und Verbindungsprüfung, Rollen, Schlüssel, Social Engineering | [sicherheit.md](sicherheit.md) |
| 9 | Onboarding: von der ersten E-Mail bis Tag 14 | [onboarding.md](onboarding.md) |
| 10 | CMS: Strapi für alle Texte, E-Mails, SMS und Onboarding | [cms.md](cms.md) |
| 11 | CI/CD-Pipeline und Google-Cloud-Dienste | [cicd-gcp.md](cicd-gcp.md) |
| – | Alle Texte der App (Startfassung für das CMS) | [`cms/content/de/`](../../cms/content/de/) |

---

## 1. Ziel und Abgrenzung

Ein reiner ChatGPT-Klon hat kaum Chancen. Die App braucht einen klaren Grund, warum man sie statt der großen Anbieter nutzt. Mögliche Schwerpunkte, die zu diesem Plan passen:

- **Vertrauen und Sicherheit:** Hosting in der EU, Passkeys, kein Training mit Nutzerdaten, transparente Sicherheitsmeldungen
- **Eine Zielgruppe oder Branche:** z. B. Handwerk, Kanzleien, Schulen, Vereine, mit passenden Assistenten und Texten
- **Teams:** Workspaces mit gewohnten Rollen (Inhaber:in, Admin, Mitglied, Gast)

Diese Entscheidung muss vor dem Design fallen.

## 2. Architektur

```
iOS (SwiftUI) ──────┐
                    ├─ KMP-Shared-Modul (Kotlin): API-Client (Ktor), Auth, Streaming,
Android (Compose) ──┘   Offline-Cache (SQLDelight), Texte aus dem CMS, Geräteschlüssel
          │
          │  HTTPS · SSE-Streaming · jede Anfrage mit dem Geräteschlüssel signiert
          ▼
   Cloud Armor (WAF, Rate-Limits, Bot-Schutz)
          ▼
   Backend-API (Ktor/Kotlin auf Cloud Run)
          ├─ Identity Platform ........ Konten, Google-/Apple-Login, MFA, Tokens
          ├─ Passkey-Dienst ........... WebAuthn (Yubico java-webauthn-server)
          ├─ Risiko-Engine ............ Standort, Verbindungsart, Gerät, Verhalten
          ├─ LiteLLM (Cloud Run) ...... Vertex AI (Gemini, Claude) und weitere Anbieter
          ├─ Cloud SQL Postgres ....... Chats, Konten, Rollen; pgvector für RAG
          ├─ Cloud Storage ............ Datei-Uploads
          ├─ Strapi (Cloud Run) ....... Texte, E-Mails, SMS, Onboarding (Admin hinter IAP)
          └─ Pub/Sub + Cloud Run functions → E-Mail, SMS/Anruf, Push, Onboarding-Strecke
```

**Änderungen gegenüber der ersten Fassung:**
- Das Backend ist jetzt **Ktor (Kotlin)** statt FastAPI/NestJS. Damit ist alles in einer Sprache, und die Datenmodelle (DTOs) lassen sich über KMP zwischen App und Backend teilen.
- Gehostet wird auf der **Google Cloud in Frankfurt** (`europe-west3`).

## 3. Open-Source-Basis

| Baustein | Lizenz | Einsatz |
|---|---|---|
| **LiteLLM** | MIT (Enterprise-Teile separat) | Ein API-Format für alle Modelle, Kosten- und Rate-Limits pro Nutzer |
| **Strapi** (Community Edition) | MIT (Enterprise-Funktionen separat) | CMS für alle Texte |
| **Yubico java-webauthn-server** | BSD-2 | Passkeys im Ktor-Backend |
| **LibreChat** | MIT | Optional als Web-Version oder Vorlage, nicht als Backend der Apps |
| **pgvector** | PostgreSQL-Lizenz | Vektorsuche für Dokumente (RAG) |

Bei Open WebUI (Branding-Klausel), LobeChat (Community License) und Dify (Zusatzbedingungen) ist Weißlabel nur eingeschränkt erlaubt. **Alle Lizenzen vor der Entscheidung juristisch prüfen lassen.**

## 4. Funktionen nach Ausbaustufe

**MVP (ca. 3–4 Monate)**
- Chat mit Streaming, Markdown und Code-Darstellung, Verlauf
- Anmeldung mit Google, Apple, E-Mail-Code oder Passkey; SMS/Anruf als Zusatzfaktor
- Risikoprüfung, Geräteübersicht und Sicherheitsmeldungen ([Abschnitt 8](sicherheit.md))
- Onboarding und E-Mail-Strecke, alle Texte aus dem CMS ([Abschnitte 9](onboarding.md) und [10](cms.md))
- Bilder hochladen und analysieren lassen
- Abos über In-App-Kauf (z. B. RevenueCat)
- Push-Benachrichtigungen, Dark Mode

**Version 1.0 (+2–3 Monate)**
- Datei-Uploads (PDF) mit RAG
- Websuche mit Quellen
- Spracheingabe und Vorlesen
- Projekte und eigene Anweisungen
- Workspaces mit Rollen, API-Schlüssel für Power-User

**Version 2.0 (danach)**
- Echtzeit-Sprachmodus
- Tools/Agenten über MCP
- Code-Ausführung in einer Sandbox
- Bildgenerierung
- Gedächtnis über Chats hinweg
- On-Device-Modelle

## 5. Technologien

| Bereich | Technologie |
|---|---|
| iOS | Swift 6, SwiftUI, AuthenticationServices (Passkeys), Secure Enclave, App Attest |
| Android | Kotlin, Jetpack Compose, Material 3, Credential Manager (Passkeys), Android Keystore/StrongBox, Play Integrity |
| Gemeinsame Logik | Kotlin Multiplatform: Ktor-Client, kotlinx.serialization, SQLDelight, Koin |
| Backend | Ktor auf Cloud Run, Exposed oder jOOQ, Flyway |
| KI | LiteLLM → Vertex AI (Gemini, Claude), optional weitere Anbieter |
| Identität | Google Identity Platform, eigener Passkey-Dienst, Twilio Verify (SMS und Anruf) |
| CMS | Strapi 5 auf Cloud Run, Cloud SQL Postgres, Medien in Cloud Storage |
| Infrastruktur | Terraform, GitHub Actions, Fastlane, Artifact Registry |
| Monitoring | Cloud Logging/Monitoring, Sentry, Langfuse (LLM-Kosten und Traces) |

## 6. Phasen und Team

| Phase | Dauer | Inhalt |
|---|---|---|
| 0. Konzept | 2–3 Wochen | Zielgruppe, Lizenzen, Design in Figma, Rollenmodell, Sicherheitskonzept freigeben |
| 1. Fundament | 4–6 Wochen | GCP-Projekte per Terraform, CI/CD, Identity Platform, Passkeys, Risiko-Engine (Grundversion), Strapi mit Texten |
| 2. MVP-Apps | 6–8 Wochen | Native Oberflächen, Onboarding, Chat, Abos; Beta über TestFlight und Play Console (interner Test) |
| 3. Launch | 2–4 Wochen | Penetrationstest, Store-Review, DSGVO-Unterlagen, E-Mail-Zustellbarkeit |
| 4. Ausbau | laufend | Version 1.0 und 2.0 |

Sicherheit und CMS stehen bewusst in Phase 1: Wer Anmeldung und Texte später umbaut, zahlt doppelt.

**Team für das MVP:**
- 1 iOS-Entwickler:in
- 1 Android/KMP-Entwickler:in
- 1 Backend-Entwickler:in (Kotlin, GCP)
- Design in Teilzeit
- Datenschutz und Recht extern

## 7. Risiken und offene Entscheidungen

- **Abgrenzung** (siehe Abschnitt 1) ist noch offen.
- **Kosten:** API-Kosten pro Nutzer begrenzen den Abopreis. Apple und Google behalten 15–30 % ein. SMS und Anrufe kosten pro Nachricht. Deshalb sind sie nur der Zusatzfaktor und durch App Check und Rate-Limits geschützt.
- **Recht:**
  - DSGVO, mit Datenschutz-Folgenabschätzung wegen der Risikoprüfung
  - Transparenzpflichten aus dem EU AI Act
  - Apple-Regeln für KI-Apps und Login-Dienste
  - Double-Opt-in für Marketing-E-Mails
- **Modellstrategie:** API-Modelle über Vertex AI als Start, später selbst gehostete Open-Weight-Modelle möglich (LiteLLM macht den Wechsel einfach).
