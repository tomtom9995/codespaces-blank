// Ende-zu-Ende-Test des zentralen Logins: Keycloak → Nextcloud → WordPress (Rollen) – erzeugt Screenshots.
// Voraussetzung: Stack läuft, dev-users.sh und dev-trust.sh ausgeführt.  Start: npx -y -p playwright node tests/sso-test.mjs
import { chromium } from "playwright";
const out = process.env.SCREENSHOT_DIR ?? new URL("../../../docs/screenshots/hosting/", import.meta.url).pathname;
const b = await chromium.launch(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {});
async function login(ctx, user) {
  const p = await ctx.newPage();
  await p.goto("https://cloud.chattia.internal:8443/");
  await p.waitForLoadState("networkidle");
  console.log(user, "nach Aufruf:", p.url().slice(0, 80));
  if (user === "senior") await p.screenshot({ path: out + "01-keycloak-login.png" });
  await p.fill("#username", user);
  await p.fill("#password", "Chattia-Test-2026!");
  await p.click("#kc-login");
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
await b.close();
