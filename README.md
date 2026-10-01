# Hello World – KI-Halluzinationen

Landingpage im Apple-Stil (Parodie) mit frei erfundenen „KI-halluzinierten“ Produkten.

| Service | Technik                         | Aufgabe                                   |
|---------|---------------------------------|-------------------------------------------|
| `mongo` | MongoDB 7 (NoSQL)               | Speichert die Produkte, Seed in `mongo-init/` |
| `api`   | Node 22 + Express               | `GET /api/products`, `GET /api/health`    |
| `web`   | Nginx + React (Vite-Build)      | Liefert die App aus, leitet `/api` an die API weiter |

## Starten

Voraussetzung: Docker Desktop (oder Docker Engine + Compose v2).

```bash
docker compose up -d --build
```

Dann im Browser öffnen: http://localhost:8080

Stoppen: `docker compose down` (mit `-v` wird auch die Datenbank gelöscht, beim nächsten Start wird neu geseedet).

## Entwicklung ohne Docker für das Frontend

```bash
docker compose up -d mongo api   # API läuft dann auf http://localhost:3000
cd web && npm install && npm run dev
```

> Alle Produkte sind erfunden. Nicht mit Apple Inc. verbunden.
