// Ende-zu-Ende-Test: Nova-App-Login mit dem Chattia-Konto und Dateien aus Nextcloud über das Nova-Backend.
// Ablauf wie in der App: Browser → /v1/auth/oidc/start → Keycloak → Callback → nova://auth?code=… → /v1/auth/oidc/exchange
// Voraussetzung: Stack + Nova-Backend laufen (API_URL), dev-users.sh ausgeführt.
// Start: NODE_EXTRA_CA_CERTS=caddy-local-root.crt npx -y -p playwright node tests/nova-files-test.mjs
import { chromium } from "playwright";
import { generateKeyPairSync } from "node:crypto";
import assert from "node:assert/strict";

const api = process.env.API_URL ?? "https://api.chattia.internal:8443";
const user = process.env.TEST_USER ?? "bursch";
const password = process.env.TEST_PASSWORD ?? "Chattia-Test-2026!";

/** Antworttext aus dem SSE-Strom des Chats zusammensetzen. */
const answerOf = (sse) => sse.split("\n").filter((l) => l.startsWith("data:")).map((l) => JSON.parse(l.slice(5)))
  .filter((e) => e.type === "delta").map((e) => e.text).join("");

// 1) Browser-Teil: Login bei Keycloak, Rücksprung zur App abfangen
const browser = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
const page = await (await browser.newContext({ ignoreHTTPSErrors: true, locale: "de-DE" })).newPage();
let appRedirect = null;
page.on("response", (r) => {
  const location = r.headers()["location"];
  if (location?.startsWith("nova://auth")) appRedirect = location;
});
await page.goto(`${api}/v1/auth/oidc/start?redirect=nova://auth`).catch(() => {});
await page.fill("#username", user);
await page.fill("#password", password);
await page.click("#kc-login").catch(() => {});
for (let i = 0; i < 40 && !appRedirect; i++) await page.waitForTimeout(250);
await browser.close();
assert.ok(appRedirect, "Kein Rücksprung zur App erhalten");
const code = new URL(appRedirect).searchParams.get("code");
console.log("✓ Rücksprung zur App mit Einmalcode");

// 2) App-Teil: Einmalcode gegen Sitzung tauschen (mit Geräteschlüssel wie in der App)
const { publicKey } = generateKeyPairSync("ec", { namedCurve: "P-256" });
const device = {
  publicKey: publicKey.export({ type: "spki", format: "der" }).toString("base64"),
  name: "E2E-Test",
  platform: "android",
  connectionType: "wifi",
};
async function call(method, path, body, headers = {}) {
  const res = await fetch(api + path, {
    method,
    headers: { ...(body && typeof body === "object" && !(body instanceof Uint8Array) ? { "Content-Type": "application/json" } : {}), ...headers },
    body: body == null ? undefined : body instanceof Uint8Array || typeof body === "string" ? body : JSON.stringify(body),
  });
  return res;
}
const login = await (await call("POST", "/v1/auth/oidc/exchange", { code, device })).json();
assert.equal(login.status, "ok", JSON.stringify(login));
assert.equal(login.user.centralAccount, true);
console.log("✓ Angemeldet als", login.user.email, "Gruppen:", login.user.groups.join(", "));
const auth = { Authorization: `Bearer ${login.tokens.accessToken}` };

const reuse = await call("POST", "/v1/auth/oidc/exchange", { code, device });
assert.equal(reuse.status, 400, "Einmalcode darf nur einmal funktionieren");
console.log("✓ Einmalcode ist verbraucht");

// 3) Dateien
const folder = `/Nova-Test-${Date.now()}`;
let res = await call("POST", "/v1/files/folder", { path: folder }, auth);
assert.equal(res.status, 201, await res.text());
const content = `Protokoll der Kneipe\n${new Date().toISOString()}\nÄÖÜ ß ✓\n`;
res = await call("PUT", `/v1/files/content?path=${encodeURIComponent(folder + "/Protokoll Kneipe.txt")}`, content, { ...auth, "Content-Type": "text/plain" });
assert.equal(res.status, 201, await res.text());
console.log("✓ Ordner angelegt und Datei hochgeladen");

const listing = await (await call("GET", `/v1/files?path=${encodeURIComponent(folder)}`, null, auth)).json();
assert.equal(listing.entries.length, 1, JSON.stringify(listing));
assert.equal(listing.entries[0].name, "Protokoll Kneipe.txt");
assert.equal(listing.entries[0].size, Buffer.byteLength(content));
const root = await (await call("GET", "/v1/files", null, auth)).json();
assert.ok(root.entries.some((e) => e.isFolder && e.path === folder));
console.log("✓ Ordnerinhalt gelistet:", listing.entries.map((e) => `${e.name} (${e.size} B)`).join(", "));

res = await call("GET", `/v1/files/content?path=${encodeURIComponent(folder + "/Protokoll Kneipe.txt")}`, null, auth);
assert.equal(res.status, 200);
assert.equal(await res.text(), content);
console.log("✓ Datei heruntergeladen, Inhalt identisch");

res = await call("GET", `/v1/files?path=${encodeURIComponent("/../../admin")}`, null, auth);
assert.equal(res.status, 400);
res = await call("GET", "/v1/files", null, {});
assert.equal(res.status, 401);
console.log("✓ Pfad-Ausbruch und Zugriff ohne Anmeldung abgelehnt");

res = await call("DELETE", `/v1/files?path=${encodeURIComponent(folder)}`, null, auth);
assert.equal(res.status, 200);
res = await call("GET", `/v1/files?path=${encodeURIComponent(folder)}`, null, auth);
assert.equal(res.status, 404);
console.log("✓ Ordner gelöscht (liegt im Nextcloud-Papierkorb)");
// 4) Team-Ordner: Rechte aus den Gruppen (bursch = burschen + website-redaktion)
if (user === "bursch") {
  const names = root.entries.filter((e) => e.isFolder).map((e) => e.name);
  for (const n of ["Corps", "Semesterprogramm", "Aktivitas", "Website"]) assert.ok(names.includes(n), `Team-Ordner ${n} fehlt: ${names}`);
  for (const n of ["Kasse", "AHV-Vorstand", "Amt Senior", "Fuchsenstall"]) assert.ok(!names.includes(n), `Team-Ordner ${n} dürfte nicht sichtbar sein`);
  res = await call("GET", `/v1/files?path=${encodeURIComponent("/Kasse")}`, null, auth);
  assert.equal(res.status, 404);
  console.log("✓ Team-Ordner nach Gruppen:", names.join(", "), "– Kasse nicht sichtbar");
}

// 5) Termine: öffentlicher und interner Kalender (Beispieldaten aus dev-files.sh)
if (user === "bursch") {
  res = await call("GET", "/v1/events", null, auth);
  assert.equal(res.status, 200, await res.clone().text());
  const events = await res.json();
  const titles = events.map((e) => e.title);
  assert.ok(titles.includes("Stiftungsfest"), titles.join(", "));
  const convent = events.find((e) => e.title === "Convent");
  assert.ok(convent && convent.internal, "interner Convent fehlt für Mitglieder");
  const site = await (await fetch(process.env.WEB_URL ?? "https://www.chattia.internal:8443/")).text();
  assert.ok(site.includes("Stiftungsfest") && !site.includes("Convent"), "Website zeigt interne Termine");
  console.log("✓ Termine in der App:", titles.join(", "), "– Convent nur intern, nicht auf der Website");
}

// 6) Chattia-Assistent: Antwort aus den Cloud-Dokumenten (Beispieldaten aus dev-files.sh)
if (process.env.SKIP_ASSISTANT !== "1") {
  const conversation = await (await call("POST", "/v1/conversations", {}, auth)).json();
  const sse = await (await call("POST", `/v1/conversations/${conversation.id}/messages`, { content: "Wann ist das Stiftungsfest und was ist der Dresscode?" }, auth)).text();
  const answer = sse.split("\n").filter((l) => l.startsWith("data:")).map((l) => JSON.parse(l.slice(5)))
    .filter((e) => e.type === "delta").map((e) => e.text).join("");
  assert.match(answer, /Stiftungsfest/);
  assert.match(answer, /\/Semesterprogramm\/WS-2026\.md/);
  if (user === "bursch") {
    const conv3 = await (await call("POST", "/v1/conversations", {}, auth)).json();
    const sse3 = answerOf(await (await call("POST", `/v1/conversations/${conv3.id}/messages`, { content: "Ab wann ist Nachtruhe im Corpshaus?" }, auth)).text());
    assert.match(sse3, /Nachtruhe ab 23 Uhr/);
    assert.match(sse3, /Hausordnung\.pdf/);
    console.log("✓ Assistent liest auch PDFs (Hausordnung.pdf)");
    const conv2 = await (await call("POST", "/v1/conversations", {}, auth)).json();
    const sse2 = answerOf(await (await call("POST", `/v1/conversations/${conv2.id}/messages`, { content: "Wie hoch ist der AH-Beitrag laut Beitragsordnung?" }, auth)).text());
    assert.ok(!/240|Beitragsordnung\.md|Kasse/.test(sse2), "Assistent hat vertrauliche Kasse-Unterlagen verwendet");
    console.log("✓ Assistent nutzt keine Unterlagen, die der Nutzer nicht sehen darf (Kasse)");
  }
  console.log("✓ Assistent antwortet aus der Cloud mit Quelle:", answer.split("\n").filter((l) => l.includes("Stiftungsfest") || l.includes(".md")).join(" | ").slice(0, 160));
}
console.log("Alle Prüfungen bestanden.");
