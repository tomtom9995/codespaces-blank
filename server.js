const express = require('express');
const sqlite3 = require('sqlite3').verbose();
const path = require('path');

const app = express();
const PORT = process.env.PORT || 8080;

// SQLite Datenbank initialisieren
const db = new sqlite3.Database(':memory:', (err) => {
  if (err) {
    console.error('Datenbankfehler:', err);
    process.exit(1);
  }
  console.log('SQLite Datenbank verbunden');
});

// Tabelle erstellen und Beispieldaten einfügen
db.serialize(() => {
  db.run(`
    CREATE TABLE IF NOT EXISTS messages (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      message TEXT NOT NULL,
      created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    )
  `);

  db.run(`INSERT INTO messages (message) VALUES (?)`, ['Hello World!']);
  db.run(`INSERT INTO messages (message) VALUES (?)`, ['Willkommen zur SQL Demo']);
});

// Middleware
app.use(express.json());
app.use(express.static('public'));

// Hello World API Endpoint
app.get('/api/messages', (req, res) => {
  db.all('SELECT id, message, created_at FROM messages', [], (err, rows) => {
    if (err) {
      return res.status(500).json({ error: err.message });
    }
    res.json(rows);
  });
});

// Neue Nachricht hinzufügen
app.post('/api/messages', (req, res) => {
  const { message } = req.body;
  if (!message) {
    return res.status(400).json({ error: 'Nachricht erforderlich' });
  }

  db.run(
    'INSERT INTO messages (message) VALUES (?)',
    [message],
    function(err) {
      if (err) {
        return res.status(500).json({ error: err.message });
      }
      res.json({ id: this.lastID, message, created_at: new Date() });
    }
  );
});

// HTML Seite
app.get('/', (req, res) => {
  const html = `<!DOCTYPE html>
    <html>
    <head>
      <title>Hello World SQL App</title>
      <style>
        body { font-family: Arial; margin: 40px; }
        .container { max-width: 600px; }
        input { padding: 8px; width: 300px; }
        button { padding: 8px 16px; cursor: pointer; }
        .message { padding: 10px; margin: 10px 0; background: #f0f0f0; border-radius: 4px; }
      </style>
    </head>
    <body>
      <div class="container">
        <h1>Hello World SQL Anwendung</h1>
        <p>Dies ist eine einfache Webanwendung mit SQLite Datenbank.</p>

        <h2>Nachricht hinzufügen:</h2>
        <input type="text" id="messageInput" placeholder="Nachricht eingeben">
        <button onclick="addMessage()">Hinzufügen</button>

        <h2>Nachrichten aus der Datenbank:</h2>
        <div id="messagesList"></div>
      </div>

      <script>
        function loadMessages() {
          fetch('/api/messages')
            .then(r => r.json())
            .then(messages => {
              const list = document.getElementById('messagesList');
              list.innerHTML = messages.map(m =>
                '<div class="message"><strong>' + m.message + '</strong><br><small>' + new Date(m.created_at).toLocaleString() + '</small></div>'
              ).join('');
            });
        }

        function addMessage() {
          const input = document.getElementById('messageInput');
          const message = input.value.trim();
          if (!message) return;

          fetch('/api/messages', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message })
          })
          .then(r => r.json())
          .then(() => {
            input.value = '';
            loadMessages();
          });
        }

        loadMessages();
      </script>
    </body>
    </html>`;
  res.send(html);
});

app.listen(PORT, () => {
  console.log('Server läuft auf http://localhost:' + PORT);
});

process.on('SIGINT', () => {
  db.close();
  process.exit(0);
});
