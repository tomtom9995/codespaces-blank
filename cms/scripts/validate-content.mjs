#!/usr/bin/env node
// Prüft alle CMS-Texte in cms/content/<sprache>/ – lokal und in der CI-Pipeline.
//   - nur erlaubte Platzhalter ({name}), Beispielwert für jeden Platzhalter vorhanden
//   - Links nur auf erlaubte Domains (Schutz vor Phishing-Links über das CMS)
//   - SMS: nur GSM-7-Zeichen und höchstens 160 Zeichen (eine SMS)
//   - eindeutige Schlüssel, eindeutige Reihenfolge im Onboarding
// Ohne Abhängigkeiten: `node cms/scripts/validate-content.mjs`

import { readFileSync, readdirSync, existsSync } from "node:fs";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const contentRoot = join(dirname(fileURLToPath(import.meta.url)), "..", "content");

const PLACEHOLDER = /\{([a-zA-Z][a-zA-Z0-9]*)\}/g;
const URL_PATTERN = /https?:\/\/[^\s)\]>"]+/g;
const NON_TEXT_FIELDS = new Set([
  "key", "description", "category", "trigger", "condition", "placeholders",
  "permissions", "scope", "order", "skippable",
]);
const GSM7_BASIC =
  "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡" +
  "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
const GSM7_EXTENDED = "^{}\\[~]|€\f";

const FILES = {
  "ui-texts.json": {},
  "onboarding-steps.json": {},
  "email-templates.json": {},
  "sms-templates.json": {},
  "voice-templates.json": {},
  "push-templates.json": {},
  "roles.json": {},
};

let errors = 0;
let warnings = 0;
const error = (where, msg) => { errors++; console.error(`  ✗ ${where}: ${msg}`); };
const warn = (where, msg) => { warnings++; console.warn(`  ! ${where}: ${msg}`); };

function readJson(path) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (e) {
    error(path, `ungültiges JSON (${e.message})`);
    return null;
  }
}

// Alle Textfelder eines Eintrags als [feldname, text]
function textFields(entry) {
  const out = [];
  for (const [field, value] of Object.entries(entry)) {
    if (NON_TEXT_FIELDS.has(field)) continue;
    if (typeof value === "string") out.push([field, value]);
    else if (Array.isArray(value)) value.forEach((v, i) => typeof v === "string" && out.push([`${field}[${i}]`, v]));
  }
  return out;
}

function render(text, samples, where) {
  return text.replace(PLACEHOLDER, (_, name) => {
    if (!(name in samples)) {
      error(where, `kein Beispielwert für {${name}} in settings.json › sampleValues`);
      return "";
    }
    return samples[name];
  });
}

function hostAllowed(url, allowedDomains) {
  let host;
  try { host = new URL(url).hostname; } catch { return false; }
  return allowedDomains.some((d) => host === d || host.endsWith(`.${d}`));
}

function gsm7Length(text, where) {
  let length = 0;
  for (const ch of text) {
    if (GSM7_BASIC.includes(ch)) length += 1;
    else if (GSM7_EXTENDED.includes(ch)) length += 2;
    else {
      error(where, `Zeichen „${ch}“ (U+${ch.codePointAt(0).toString(16).toUpperCase().padStart(4, "0")}) ist nicht GSM-7 – die SMS würde als Unicode mit max. 70 Zeichen verschickt`);
      length += 1;
    }
  }
  return length;
}

function validateLocale(locale) {
  const dir = join(contentRoot, locale);
  console.log(`\nSprache „${locale}“`);

  const settings = readJson(join(dir, "settings.json"));
  if (!settings) return;
  const globals = new Set(settings.globalPlaceholders ?? []);
  const samples = settings.sampleValues ?? {};
  const allowedDomains = settings.allowedLinkDomains ?? [];
  const seenKeys = new Map();
  const counts = [];

  for (const file of Object.keys(FILES)) {
    const path = join(dir, file);
    if (!existsSync(path)) { warn(file, "Datei fehlt"); continue; }
    const entries = readJson(path);
    if (!entries) continue;
    if (!Array.isArray(entries)) { error(file, "erwartet ein Array von Einträgen"); continue; }
    counts.push(`${entries.length} × ${file.replace(".json", "")}`);

    const orders = new Map();
    for (const entry of entries) {
      const where = `${file} › ${entry.key ?? "(ohne key)"}`;
      if (!entry.key) { error(where, "Feld „key“ fehlt"); continue; }
      if (seenKeys.has(entry.key)) error(where, `Schlüssel doppelt (auch in ${seenKeys.get(entry.key)})`);
      seenKeys.set(entry.key, file);

      const declared = new Set(entry.placeholders ?? []);
      const used = new Set();
      const rendered = {};

      for (const [field, text] of textFields(entry)) {
        const fieldWhere = `${where} › ${field}`;
        for (const [, name] of text.matchAll(PLACEHOLDER)) {
          used.add(name);
          if (!declared.has(name) && !globals.has(name)) {
            error(fieldWhere, `Platzhalter {${name}} ist nicht in „placeholders“ angegeben`);
          }
        }
        rendered[field] = render(text, samples, fieldWhere);
        for (const url of rendered[field].match(URL_PATTERN) ?? []) {
          if (!hostAllowed(url, allowedDomains)) error(fieldWhere, `Link auf nicht erlaubte Domain: ${url}`);
        }
      }

      for (const name of declared) {
        if (!used.has(name)) warn(where, `Platzhalter {${name}} angegeben, aber nicht verwendet`);
      }

      if (file === "sms-templates.json") {
        const length = gsm7Length(rendered.text ?? "", `${where} › text`);
        if (length > 160) error(where, `SMS hat ${length} Zeichen (max. 160 für eine SMS)`);
        if (/https?:\/\//.test(entry.text ?? "")) error(where, "SMS dürfen keine Links enthalten");
      }
      if (file === "email-templates.json") {
        if ((rendered.subject ?? "").length > 70) warn(where, `Betreff hat ${rendered.subject.length} Zeichen – auf dem Handy wird ab ca. 70 abgeschnitten`);
        if ((rendered.preheader ?? "").length > 110) warn(where, `Preheader hat ${rendered.preheader.length} Zeichen (empfohlen max. 110)`);
        if (!Array.isArray(entry.body) || entry.body.length === 0) error(where, "„body“ muss eine nicht leere Liste von Absätzen sein");
        if (!["transactional", "security", "marketing"].includes(entry.category)) error(where, `unbekannte Kategorie „${entry.category}“`);
        if (entry.ctaLabel && !entry.ctaUrl) error(where, "ctaLabel ohne ctaUrl");
      }
      if (file === "push-templates.json") {
        if ((rendered.title ?? "").length > 50) warn(where, `Titel hat ${rendered.title.length} Zeichen (empfohlen max. 50)`);
        if ((rendered.body ?? "").length > 150) warn(where, `Text hat ${rendered.body.length} Zeichen (empfohlen max. 150)`);
      }
      if (file === "onboarding-steps.json") {
        if (orders.has(entry.order)) error(where, `Reihenfolge ${entry.order} doppelt (auch ${orders.get(entry.order)})`);
        orders.set(entry.order, entry.key);
      }
    }
  }
  console.log(`  ${counts.join(", ")}`);
}

const locales = readdirSync(contentRoot, { withFileTypes: true }).filter((d) => d.isDirectory()).map((d) => d.name);
locales.forEach(validateLocale);

console.log(`\n${errors === 0 ? "✓" : "✗"} ${errors} Fehler, ${warnings} Warnungen`);
process.exit(errors === 0 ? 0 : 1);
