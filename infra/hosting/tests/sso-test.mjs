// Ende-zu-Ende-Test des zentralen Logins: Keycloak → Nextcloud → WordPress (Rollen) – erzeugt Screenshots.
// Voraussetzung: Stack läuft, dev-users.sh und dev-trust.sh ausgeführt.
// Start: NODE_EXTRA_CA_CERTS=caddy-local-root.crt npx -y -p playwright node tests/sso-test.mjs
import { chromium } from "playwright";
import { createHmac } from "node:crypto";

/** TOTP (RFC 6238) wie eine Authenticator-App: Base32-Geheimnis → 6 Ziffern. */
function totp(secret, at = Date.now()) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const c of secret.replace(/[\s=]/g, "").toUpperCase()) bits += alphabet.indexOf(c).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g).map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 30000)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}
/** NUR LOKAL: zweiten Faktor eines Testkontos entfernen, damit die Einrichtung jedes Mal getestet wird. */
async function resetSecondFactor(username) {
  const auth = "https://auth.chattia.internal:8443";
  const token = await (await fetch(`${auth}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    body: new URLSearchParams({
      grant_type: "password", client_id: "admin-cli",
      username: process.env.KEYCLOAK_ADMIN_USER ?? "kc-admin", password: process.env.KEYCLOAK_ADMIN_PASSWORD ?? "dev-kc-admin",
    }),
  })).json();
  const headers = { Authorization: `Bearer ${token.access_token}` };
  const [user] = await (await fetch(`${auth}/admin/realms/chattia/users?exact=true&username=${username}`, { headers })).json();
  for (const c of await (await fetch(`${auth}/admin/realms/chattia/users/${user.id}/credentials`, { headers })).json()) {
    if (c.type === "otp") await fetch(`${auth}/admin/realms/chattia/users/${user.id}/credentials/${c.id}`, { method: "DELETE", headers });
  }
}
await resetSecondFactor("senior");

const secrets = {}; // Benutzer → TOTP-Geheimnis (nach der Einrichtung)
const factor = {}; // Benutzer → "eingerichtet" | "abgefragt" | "ohne"
const out = process.env.SCREENSHOT_DIR ?? new URL("../../../docs/screenshots/hosting/", import.meta.url).pathname;
const b = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
async function login(ctx, user, shots = user === "senior") {
  const p = await ctx.newPage();
  await p.goto("https://cloud.chattia.internal:8443/");
  await p.waitForLoadState("networkidle");
  console.log(user, "nach Aufruf:", p.url().slice(0, 80));
  if (shots) await p.screenshot({ path: out + "01-keycloak-login.png" });
  await p.fill("#username", user);
  await p.fill("#password", "Chattia-Test-2026!");
  await p.click("#kc-login");
  await p.waitForLoadState("domcontentloaded");
  if (await p.locator("#mode-manual, #kc-totp-secret-key").count()) {
    // Erste Anmeldung mit Amt: Keycloak verlangt die Einrichtung des zweiten Faktors
    if (shots) await p.screenshot({ path: out + "06-zweiter-faktor-einrichten.png" });
    if (await p.locator("#mode-manual").count()) await p.click("#mode-manual");
    secrets[user] = (await p.locator("#kc-totp-secret-key").innerText()).trim();
    await p.fill("#totp", totp(secrets[user]));
    await p.click("#saveTOTPBtn, input[type=submit]");
    factor[user] = "eingerichtet";
    console.log(user, "zweiter Faktor eingerichtet");
  } else if (await p.locator("#otp").count()) {
    await p.fill("#otp", totp(secrets[user]));
    await p.click("#kc-login");
    factor[user] = "abgefragt";
    console.log(user, "zweiter Faktor abgefragt");
  } else {
    factor[user] = "ohne";
    console.log(user, "ohne zweiten Faktor");
  }
  await p.waitForURL(/cloud\.chattia\.internal.*apps/, { timeout: 30000, waitUntil: "commit" });
  await p.waitForTimeout(5000);
  console.log(user, "nach Login:", p.url(), await p.title());
  return p;
}
const ctx = await b.newContext({ viewport: { width: 1280, height: 800 }, locale: "de-DE", ignoreHTTPSErrors: true });
let p = await login(ctx, "senior");
await p.goto("https://cloud.chattia.internal:8443/apps/files/");
await p.waitForTimeout(5000);
await p.screenshot({ path: out + "02-nextcloud-nach-sso.png" });
// WordPress mit derselben Keycloak-Sitzung (SSO, keine erneute Passworteingabe)
await p.goto("https://www.chattia.internal:8443/wp-login.php");
await p.waitForLoadState("networkidle");
await p.screenshot({ path: out + "03-wordpress-login.png" });
await p.click("text=Mit Chattia-Konto anmelden");
await p.waitForURL(/wp-admin/, { timeout: 30000, waitUntil: "commit" });
await p.waitForTimeout(2000);
console.log("WP nach SSO:", p.url(), await p.title());
await p.screenshot({ path: out + "04-wordpress-nach-sso.png" });
// Fux darf nicht in WordPress (keine Redaktionsgruppe)
const ctx2 = await b.newContext({ viewport: { width: 1280, height: 800 }, locale: "de-DE", ignoreHTTPSErrors: true });
const f = await login(ctx2, "fux");
await f.goto("https://www.chattia.internal:8443/wp-login.php");
await f.click("text=Mit Chattia-Konto anmelden");
await f.waitForTimeout(6000);
console.log("Fux WP:", f.url().slice(0, 100));
await f.screenshot({ path: out + "05-wordpress-fux-abgelehnt.png" });

// Zweiter Faktor: Senior (Charge) beim nächsten Login wieder gefragt, Bursch ohne Amt nicht
const fresh = () => b.newContext({ viewport: { width: 1280, height: 800 }, locale: "de-DE", ignoreHTTPSErrors: true });
await login(await fresh(), "senior", false);
await login(await fresh(), "bursch", false);
await b.close();

const fail = (msg) => { console.error("✗", msg); process.exitCode = 1; };
if (!["eingerichtet", "abgefragt"].includes(factor.senior)) fail("Senior ohne zweiten Faktor angemeldet");
if (factor.fux !== "ohne") fail("Fux sollte ohne zweiten Faktor auskommen");
if (factor.bursch !== "ohne") fail("Bursch sollte ohne zweiten Faktor auskommen");
if (!f.url().includes("login-error")) fail("Fux wurde in WordPress nicht abgewiesen");
if (!process.exitCode) console.log("✓ Alle Prüfungen bestanden");
