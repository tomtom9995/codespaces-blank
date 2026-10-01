import fs from 'fs';
import path from 'path';
import type { Core } from '@strapi/strapi';

/**
 * Nova-CMS: Beim Start werden
 *  1. die Sprache „de“ angelegt und zur Standardsprache gemacht,
 *  2. die Startfassung aller Texte aus cms/content/<sprache>/ importiert (nur wenn noch leer),
 *  3. die Redaktionsrollen „Redaktion“ und „Sicherheitsfreigabe“ angelegt,
 *  4. optional (Entwicklung) Lesezugriff ohne Token erlaubt und ein Admin-Konto angelegt,
 *  5. das Backend bei jeder Änderung benachrichtigt, damit es seinen Text-Cache leert.
 */

type Uid = `api::${string}.${string}`;

interface Source {
  uid: Uid;
  file: string;
  single?: boolean;
  transform?: (entry: Record<string, unknown>) => Record<string, unknown>;
}

const SOURCES: Source[] = [
  { uid: 'api::setting.setting', file: 'settings.json', single: true },
  { uid: 'api::ui-text.ui-text', file: 'ui-texts.json' },
  { uid: 'api::onboarding-step.onboarding-step', file: 'onboarding-steps.json' },
  {
    uid: 'api::email-template.email-template',
    file: 'email-templates.json',
    transform: (e) => ({ ...e, body: Array.isArray(e.body) ? (e.body as string[]).join('\n\n') : e.body }),
  },
  { uid: 'api::sms-template.sms-template', file: 'sms-templates.json' },
  { uid: 'api::voice-template.voice-template', file: 'voice-templates.json' },
  { uid: 'api::push-template.push-template', file: 'push-templates.json' },
  { uid: 'api::role.role', file: 'roles.json' },
];

/** Nachrichtenvorlagen: Redaktion darf entwerfen, nur die Sicherheitsfreigabe veröffentlichen. */
const MESSAGE_TYPES: Uid[] = [
  'api::setting.setting',
  'api::email-template.email-template',
  'api::sms-template.sms-template',
  'api::voice-template.voice-template',
  'api::push-template.push-template',
];
const EDITORIAL_TYPES: Uid[] = ['api::ui-text.ui-text', 'api::onboarding-step.onboarding-step', 'api::role.role'];

function contentDir(): string {
  return process.env.CONTENT_DIR || path.resolve(process.cwd(), '..', 'content');
}

function pick(strapi: Core.Strapi, uid: Uid, entry: Record<string, unknown>): Record<string, unknown> {
  const attributes = Object.keys(strapi.getModel(uid as any).attributes);
  return Object.fromEntries(Object.entries(entry).filter(([k, v]) => attributes.includes(k) && v !== undefined));
}

async function ensureLocales(strapi: Core.Strapi, codes: string[]) {
  const locales = strapi.plugin('i18n').service('locales');
  for (const code of codes) {
    const existing = await locales.findByCode(code);
    if (!existing) {
      await locales.create({ code, name: code === 'de' ? 'Deutsch (de)' : code === 'en' ? 'English (en)' : code });
      strapi.log.info(`[nova] Sprache ${code} angelegt`);
    }
  }
  if (codes.includes('de')) {
    const current = await locales.getDefaultLocale();
    if (current !== 'de') await locales.setDefaultLocale({ code: 'de' });
  }
}

async function seed(strapi: Core.Strapi) {
  const dir = contentDir();
  if (!fs.existsSync(dir)) {
    strapi.log.warn(`[nova] Kein Inhaltsordner unter ${dir} – Import übersprungen`);
    return;
  }
  const localeCodes = fs.readdirSync(dir).filter((d) => fs.statSync(path.join(dir, d)).isDirectory());
  await ensureLocales(strapi, localeCodes);

  for (const locale of localeCodes) {
    for (const source of SOURCES) {
      const file = path.join(dir, locale, source.file);
      if (!fs.existsSync(file)) continue;
      const docs = strapi.documents(source.uid as any);
      const existing = await docs.count({ locale } as any);
      if (existing > 0) continue;

      const raw = JSON.parse(fs.readFileSync(file, 'utf8'));
      const entries: Record<string, unknown>[] = source.single ? [raw] : raw;
      for (const entry of entries) {
        const data = pick(strapi, source.uid, source.transform ? source.transform(entry) : entry);
        await docs.create({ data, locale, status: 'published' } as any);
      }
      strapi.log.info(`[nova] ${entries.length} × ${source.file} (${locale}) importiert`);
    }
  }
}

async function ensureEditorialRoles(strapi: Core.Strapi) {
  const roleService = strapi.service('admin::role');
  const localeCodes = ((await strapi.plugin('i18n').service('locales').find()) as { code: string }[]).map((l) => l.code);
  const fieldsOf = (uid: Uid) => Object.keys(strapi.getModel(uid as any).attributes);

  const perms = (uids: Uid[], actions: string[]) =>
    uids.flatMap((uid) =>
      actions.map((action) => ({
        action: `plugin::content-manager.explorer.${action}`,
        subject: uid,
        properties: ['read', 'create', 'update'].includes(action) ? { fields: fieldsOf(uid), locales: localeCodes } : { locales: localeCodes },
        conditions: [],
      })),
    );

  const roles = [
    {
      name: 'Redaktion',
      description: 'Entwirft und veröffentlicht UI-Texte und Onboarding. Nachrichtenvorlagen nur als Entwurf.',
      permissions: [
        ...perms(EDITORIAL_TYPES, ['read', 'create', 'update', 'delete', 'publish']),
        ...perms(MESSAGE_TYPES, ['read', 'create', 'update']),
      ],
    },
    {
      name: 'Sicherheitsfreigabe',
      description: 'Gibt E-Mail-, SMS-, Anruf- und Push-Vorlagen sowie Einstellungen frei.',
      permissions: [
        ...perms(MESSAGE_TYPES, ['read', 'create', 'update', 'delete', 'publish']),
        ...perms(EDITORIAL_TYPES, ['read']),
      ],
    },
  ];

  for (const role of roles) {
    const existing = await strapi.db.query('admin::role').findOne({ where: { name: role.name } });
    if (existing) continue;
    const created = await roleService.create({ name: role.name, description: role.description });
    await roleService.assignPermissions(created.id, role.permissions);
    strapi.log.info(`[nova] Admin-Rolle „${role.name}“ angelegt`);
  }
}

async function allowPublicRead(strapi: Core.Strapi) {
  const publicRole = await strapi.db.query('plugin::users-permissions.role').findOne({ where: { type: 'public' } });
  if (!publicRole) return;
  for (const source of SOURCES) {
    const actions = source.single ? ['find'] : ['find', 'findOne'];
    for (const a of actions) {
      const action = `${source.uid}.${a}`;
      const exists = await strapi.db.query('plugin::users-permissions.permission').findOne({ where: { action, role: publicRole.id } });
      if (!exists) await strapi.db.query('plugin::users-permissions.permission').create({ data: { action, role: publicRole.id } });
    }
  }
  strapi.log.warn('[nova] STRAPI_PUBLIC_READ=true: Inhalte ohne Token lesbar – nur für Entwicklung/internes Netz!');
}

async function ensureAdmin(strapi: Core.Strapi) {
  const email = process.env.STRAPI_ADMIN_EMAIL;
  const password = process.env.STRAPI_ADMIN_PASSWORD;
  if (!email || !password) return;
  const userService = strapi.service('admin::user');
  if (await userService.exists()) return;
  const superAdmin = await strapi.service('admin::role').getSuperAdmin();
  await userService.create({
    email,
    firstname: 'Nova',
    lastname: 'Admin',
    password,
    isActive: true,
    registrationToken: null,
    roles: superAdmin ? [superAdmin.id] : [],
  });
  strapi.log.info(`[nova] Admin-Konto ${email} angelegt`);
}

function notifyBackendOnChange(strapi: Core.Strapi) {
  const url = process.env.BACKEND_INVALIDATE_URL;
  const secret = process.env.STRAPI_WEBHOOK_SECRET;
  if (!url || !secret) return;
  const notify = () => {
    fetch(url, { method: 'POST', headers: { 'X-Webhook-Secret': secret } }).catch((e) =>
      strapi.log.warn(`[nova] Backend-Benachrichtigung fehlgeschlagen: ${e.message}`),
    );
  };
  strapi.db.lifecycles.subscribe({
    models: SOURCES.map((s) => s.uid),
    afterCreate: notify,
    afterUpdate: notify,
    afterDelete: notify,
  });
}

export default {
  register() {},

  async bootstrap({ strapi }: { strapi: Core.Strapi }) {
    const steps: [string, () => Promise<void> | void][] = [
      ['Texte importieren', () => seed(strapi)],
      ['Redaktionsrollen', () => ensureEditorialRoles(strapi)],
      ['Admin-Konto', () => ensureAdmin(strapi)],
      ['Öffentlicher Lesezugriff', () => (process.env.STRAPI_PUBLIC_READ === 'true' ? allowPublicRead(strapi) : undefined)],
      ['Backend-Benachrichtigung', () => notifyBackendOnChange(strapi)],
    ];
    for (const [name, step] of steps) {
      try {
        await step();
      } catch (e) {
        strapi.log.error(`[nova] ${name} fehlgeschlagen: ${(e as Error).message}`);
      }
    }
  },
};
