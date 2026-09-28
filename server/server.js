/**
 * King Vegas TV license server
 * ---------------------
 * - Subscribers sign up and buy Premium on this website (monthly, yearly or lifetime) through Stripe.
 * - In the app they sign in (Settings › Premium account) and up to DEVICE_LIMIT devices can use one account.
 * - The admin account (ADMIN_EMAIL) always has Premium and can give Premium to anyone from /admin.
 * - Without STRIPE_SECRET_KEY the server runs in TEST MODE: checkout is simulated so you can test for free.
 *
 * Configure with environment variables (see .env.example). Needs Node 22.13+ (built-in SQLite).
 */
const crypto = require('node:crypto');
const path = require('node:path');
const fs = require('node:fs');
const { DatabaseSync } = require('node:sqlite');
const express = require('express');

// ------------------------------------------------------------------ config
const env = process.env;
const PORT = +(env.PORT || 8080);
const PUBLIC_URL = (env.PUBLIC_URL || `http://localhost:${PORT}`).replace(/\/+$/, '');
const APP_NAME = env.APP_NAME || 'KINGVEGAS TV';
const ADMIN_EMAIL = (env.ADMIN_EMAIL || '').trim().toLowerCase();
const ADMIN_PASSWORD = env.ADMIN_PASSWORD || '';
const DEVICE_LIMIT = +(env.DEVICE_LIMIT || 10);
const DATA_DIR = env.DATA_DIR || path.join(__dirname, 'data');
const STRIPE_KEY = env.STRIPE_SECRET_KEY || '';
const STRIPE_WEBHOOK_SECRET = env.STRIPE_WEBHOOK_SECRET || '';
// Cash App ($cashtag) payments: the customer pays you directly, you approve the order in /admin.
const DEFAULT_CASHTAG = String(env.CASHTAG ?? 'kingvegastv').replace(/^\$/, '').trim();
const stripe = STRIPE_KEY ? require('stripe')(STRIPE_KEY) : null;
const QRCode = require('qrcode');
const DAY = 86400e3;
const LIFETIME = 253402300799000; // year 9999

fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'novatv.db'));
db.exec(`
PRAGMA journal_mode = WAL;
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  email TEXT UNIQUE NOT NULL,
  pass TEXT NOT NULL,
  role TEXT NOT NULL DEFAULT 'user',
  plan TEXT NOT NULL DEFAULT 'none',          -- none | monthly | yearly | lifetime | gift
  premium_until INTEGER NOT NULL DEFAULT 0,   -- epoch ms; LIFETIME for lifetime
  stripe_customer TEXT, stripe_subscription TEXT,
  note TEXT DEFAULT '',
  created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  name TEXT NOT NULL,
  last_seen INTEGER NOT NULL,
  UNIQUE(user_id, device_id)
);
CREATE TABLE IF NOT EXISTS tokens (
  token TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  device_id TEXT,            -- null = website session
  created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings (k TEXT PRIMARY KEY, v TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS orders (
  id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT UNIQUE NOT NULL, user_id INTEGER NOT NULL, plan TEXT NOT NULL,
  amount INTEGER NOT NULL, method TEXT NOT NULL DEFAULT 'cashapp', status TEXT NOT NULL DEFAULT 'pending', -- pending | approved | rejected | cancelled
  created INTEGER NOT NULL, decided INTEGER
);
CREATE TABLE IF NOT EXISTS revoked (user_id INTEGER NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY (user_id, device_id));
-- Playlist logins (IPTV lines) each signed-in app reports, shown to the admin under the customer's email.
CREATE TABLE IF NOT EXISTS lines (
  user_id INTEGER NOT NULL, device_id TEXT NOT NULL, name TEXT, type TEXT, server TEXT, username TEXT, password TEXT, mac TEXT, streams TEXT, updated INTEGER
);
-- TV service (IPTV) logins sold on the store; the app adds them as a playlist by itself.
CREATE TABLE IF NOT EXISTS iptv_lines (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER NOT NULL, order_code TEXT, server TEXT, username TEXT, password TEXT,
  devices INTEGER, expires INTEGER, trial INTEGER DEFAULT 0, xui_id TEXT, created INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, plan TEXT, amount INTEGER, ref TEXT, created INTEGER
);
`);

// ------------------------------------------------------------------ cloud copy (Turso, free)
// The free Render server forgets its files on every restart. When TURSO_DATABASE_URL is set, every change is
// also written to a Turso cloud database, and on startup everything is loaded back from it — so customers,
// devices and orders survive restarts.
const CLOUD_URL = (env.TURSO_DATABASE_URL || '').trim();
const CLOUD_TOKEN = (env.TURSO_AUTH_TOKEN || '').trim();
const cloud = CLOUD_URL ? require('@libsql/client').createClient({ url: CLOUD_URL, authToken: CLOUD_TOKEN || undefined }) : null;
const localPrepare = db.prepare.bind(db);
const TABLES = localPrepare(`SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'`).all();
const cloudState = { on: !!cloud, restored: false, writes: 0, errors: 0, lastError: '' };
let cloudQueue = Promise.resolve();
function mirror(sql, args) {
  cloudQueue = cloudQueue.then(async () => {
    for (let attempt = 0; attempt < 6; attempt++) {
      try { await cloud.execute({ sql, args: args.map(v => (v === undefined ? null : v)) }); cloudState.writes++; return; }
      catch (e) { cloudState.errors++; cloudState.lastError = e.message; console.error('cloud write failed:', e.message); await new Promise(r => setTimeout(r, 1500 * (attempt + 1))); }
    }
  });
}
if (cloud) {
  // Every write (INSERT / UPDATE / DELETE) runs here first, then is copied to the cloud in the same order.
  db.prepare = sql => {
    const st = localPrepare(sql);
    if (/^\s*select/i.test(sql)) return st;
    return { run: (...a) => { const r = st.run(...a); mirror(sql, a); return r; }, get: (...a) => st.get(...a), all: (...a) => st.all(...a) };
  };
}
async function restoreFromCloud() {
  for (const t of TABLES) await cloud.execute(t.sql.replace(/^CREATE TABLE\s+(IF NOT EXISTS\s+)?/i, 'CREATE TABLE IF NOT EXISTS '));
  const data = {};
  for (const t of TABLES) data[t.name] = await cloud.execute(`SELECT * FROM ${t.name}`);
  let seq = null;
  try { seq = await cloud.execute('SELECT name, seq FROM sqlite_sequence'); } catch { /* no rows yet */ }
  db.exec('BEGIN');
  try {
    for (const t of TABLES) {
      const rs = data[t.name];
      localPrepare(`DELETE FROM ${t.name}`).run();
      if (!rs.rows.length) continue;
      const ins = localPrepare(`INSERT INTO ${t.name} (${rs.columns.join(', ')}) VALUES (${rs.columns.map(() => '?').join(', ')})`);
      for (const row of rs.rows) ins.run(...rs.columns.map(c => (typeof row[c] === 'bigint' ? Number(row[c]) : row[c])));
    }
    // Keep new ids in step with the cloud copy.
    try { localPrepare('DELETE FROM sqlite_sequence').run(); } catch { /* table appears after the first insert */ }
    for (const r of seq?.rows || []) localPrepare('INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)').run(String(r.name), Number(r.seq));
    db.exec('COMMIT');
  } catch (e) { db.exec('ROLLBACK'); throw e; }
  cloudState.restored = true;
  const n = localPrepare('SELECT COUNT(*) n FROM users').get().n;
  console.log(`Loaded ${n} accounts from the cloud database.`);
}

// Prices in cents — editable in the admin panel. Defaults undercut TiviMate on purpose.
const DEFAULT_PRICES = { monthly: 99, yearly: 499, lifetime: 1499 };
function prices() {
  const out = { ...DEFAULT_PRICES };
  for (const r of db.prepare(`SELECT k, v FROM settings WHERE k LIKE 'price_%'`).all()) out[r.k.slice(6)] = +r.v;
  return out;
}
const money = c => `$${(c / 100).toFixed(2)}`;
function cashtag() {
  const r = db.prepare(`SELECT v FROM settings WHERE k = 'cashtag'`).get();
  return (r ? r.v : DEFAULT_CASHTAG).replace(/^\$/, '').trim();
}
/** Test mode = no way to take real money yet (no Stripe key and no $cashtag): purchases are simulated. */
const testMode = () => !stripe && !cashtag();
const PLAN_LABEL = { none: 'Free', monthly: 'Monthly', yearly: 'Yearly', lifetime: 'Lifetime', gift: 'Given by admin', bundle: 'Included with TV service' };

// ------------------------------------------------------------------ TV service store (reseller)
// Customers buy a TV service plan (months × devices). Orders are stored in `orders` with plan "iptv:<months>:<devices>"
// ("iptv:trial:1" for the free trial). Approving one creates the line in the XUI panel (when XUI_API_URL + XUI_API_KEY
// are set) or saves the login the admin pasted, and gives KINGVEGAS Premium for the same time.
const IPTV_MONTHS = [1, 3, 6, 12];
const IPTV_DEVICES = [1, 2, 3, 4, 5];
const TRIAL_HOURS = 36;
const DEFAULT_IPTV_BASE = { 1: 1500, 3: 4000, 6: 7000, 12: 12000 };
function setting(k, def = '') { const r = db.prepare('SELECT v FROM settings WHERE k = ?').get(k); return r ? r.v : def; }
function setSetting(k, v) { db.prepare('INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v').run(k, String(v)); }
function iptvPrice(m, d) {
  const v = setting(`iptv_price_${m}_${d}`, '');
  return v !== '' ? +v : Math.round(DEFAULT_IPTV_BASE[m] * (1 + 0.5 * (d - 1)) / 100) * 100 - 1;
}
function parsePlan(plan) {
  const m = /^iptv:(\d+|trial):(\d)$/.exec(plan || '');
  return m ? { trial: m[1] === 'trial', months: m[1] === 'trial' ? 0 : +m[1], devices: +m[2] } : null;
}
const plural = (n, w) => `${n} ${w}${n === 1 ? '' : 's'}`;
function planLabel(plan) {
  const p = parsePlan(plan);
  if (!p) return PLAN_LABEL[plan] || plan;
  return p.trial ? `Free trial · ${TRIAL_HOURS} hours` : `TV service · ${plural(p.months, 'month')} · ${plural(p.devices, 'device')}`;
}
const bundlePremium = () => setting('iptv_bundle_premium', '1') === '1';
const trialOn = () => setting('iptv_trial', '1') === '1';
const iptvServer = () => setting('iptv_server', '');
const APK_URL = 'https://github.com/stillsmokinbud-dotcom/KingVegasTV/releases/latest/download/KingVegasTV.apk';

// XUI reseller API: XUI_API_URL = the panel link with your reseller API access code (from your provider),
// XUI_API_KEY = the API key from your panel profile. Both are set on Render, never in the code.
const XUI_URL = (env.XUI_API_URL || '').trim();
const XUI_KEY = (env.XUI_API_KEY || '').trim();
const xuiOn = () => !!(XUI_URL && XUI_KEY);
async function xui(action, params = {}) {
  const url = new URL(XUI_URL.endsWith('/') ? XUI_URL : `${XUI_URL}/`);
  url.searchParams.set('api_key', XUI_KEY);
  url.searchParams.set('action', action);
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v));
  let r;
  try { r = await fetch(url, { signal: AbortSignal.timeout(20000) }); } catch (e) { throw new Error(`Can't reach the panel (${e.cause?.code || e.name})`); }
  const text = await r.text();
  let j; try { j = JSON.parse(text); } catch { throw new Error(`The panel answered ${r.status}: ${text.replace(/<[^>]+>/g, ' ').trim().slice(0, 120)}`); }
  if (j && j.status && j.status !== 'STATUS_SUCCESS') throw new Error(`The panel said: ${j.error || j.message || j.status}`);
  return j && 'data' in j ? j.data : j;
}
async function xuiPackages() {
  try { return await xui('get_packages'); } catch (e) { try { return await xui('packages'); } catch { throw e; } }
}
const randStr = (n, chars) => Array.from({ length: n }, () => chars[crypto.randomInt(chars.length)]).join('');
function saveLine(userId, code, l) {
  const r = db.prepare('INSERT INTO iptv_lines (user_id, order_code, server, username, password, devices, expires, trial, xui_id, created) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
    .run(userId, code, l.server || '', l.username, l.password, l.devices || 1, l.expires, l.trial ? 1 : 0, l.xuiId == null ? null : String(l.xuiId), Date.now());
  return db.prepare('SELECT * FROM iptv_lines WHERE id = ?').get(Number(r.lastInsertRowid));
}
/** Creates the customer's line in the XUI panel for order [o] (uses the package chosen for that plan in the admin). */
async function provisionLine(u, o) {
  const p = parsePlan(o.plan);
  const pkg = setting(p.trial ? 'iptv_pkg_trial' : `iptv_pkg_${p.months}_${p.devices}`, '');
  if (!pkg) throw new Error(`No panel package is chosen for "${planLabel(o.plan)}" (Admin › TV service › prices and packages).`);
  const username = 'kv' + randStr(8, 'abcdefghjkmnpqrstuvwxyz23456789');
  const password = randStr(10, 'abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789');
  const data = await xui('create_line', { username, password, package: pkg, max_connections: p.devices, is_trial: p.trial ? 1 : 0 });
  const d = Array.isArray(data) ? data[0] : data;
  const exp = +(d?.exp_date || 0);
  return saveLine(u.id, o.code, {
    server: iptvServer(), username: d?.username || username, password: d?.password || password, devices: p.devices, trial: p.trial, xuiId: d?.id,
    expires: exp ? exp * 1000 : Date.now() + (p.trial ? TRIAL_HOURS * 3600e3 : p.months * 31 * DAY),
  });
}
/** Premium that comes with a TV service plan (same length). Lifetime Premium stays lifetime. */
function giveBundlePremium(u, months) {
  if (!bundlePremium() || u.plan === 'lifetime' || isAdmin(u)) return;
  extendPremium(u, u.plan === 'none' || !u.plan ? 'bundle' : u.plan, months * 31);
}
function takeBundlePremium(u, months) {
  if (!bundlePremium() || u.plan === 'lifetime' || isAdmin(u)) return;
  const until = u.premium_until - months * 31 * DAY;
  if (until > Date.now()) db.prepare('UPDATE users SET premium_until = ? WHERE id = ?').run(until, u.id);
  else db.prepare(`UPDATE users SET plan = 'none', premium_until = 0 WHERE id = ?`).run(u.id);
}
const activeLines = userId => db.prepare('SELECT * FROM iptv_lines WHERE user_id = ? AND expires > ? ORDER BY created DESC').all(userId, Date.now());
const trialUsed = userId => !!db.prepare(`SELECT 1 FROM orders WHERE user_id = ? AND plan LIKE 'iptv:trial:%' AND status IN ('pending', 'approving', 'approved')`).get(userId);

// ------------------------------------------------------------------ passwords, tokens, users
function hashPassword(pw) {
  const salt = crypto.randomBytes(16);
  const hash = crypto.scryptSync(pw, salt, 64);
  return `scrypt$${salt.toString('hex')}$${hash.toString('hex')}`;
}
function checkPassword(pw, stored) {
  const [, saltHex, hashHex] = String(stored).split('$');
  if (!saltHex || !hashHex) return false;
  const hash = crypto.scryptSync(pw, Buffer.from(saltHex, 'hex'), 64);
  return crypto.timingSafeEqual(hash, Buffer.from(hashHex, 'hex'));
}
const newToken = () => crypto.randomBytes(32).toString('hex');

// Device sign-ins use signed tokens, so a TV stays signed in even when the free server restarts
// and forgets its database (the admin account is recreated from ADMIN_EMAIL / ADMIN_PASSWORD).
const TOKEN_SECRET = env.TOKEN_SECRET || crypto.createHash('sha256').update(`kvtv:${ADMIN_EMAIL}:${ADMIN_PASSWORD}`).digest('hex');
function signDeviceToken(email, deviceId, name) {
  const body = Buffer.from(JSON.stringify({ e: email, d: deviceId, n: String(name || 'Device').slice(0, 60), t: Date.now() })).toString('base64url');
  const sig = crypto.createHmac('sha256', TOKEN_SECRET).update(body).digest('base64url');
  return `v2.${body}.${sig}`;
}
function verifyDeviceToken(tok) {
  const [v, body, sig] = String(tok || '').split('.');
  if (v !== 'v2' || !body || !sig) return null;
  const want = crypto.createHmac('sha256', TOKEN_SECRET).update(body).digest('base64url');
  if (want.length !== sig.length || !crypto.timingSafeEqual(Buffer.from(want), Buffer.from(sig))) return null;
  try { return JSON.parse(Buffer.from(body, 'base64url').toString('utf8')); } catch { return null; }
}
function revoke(userId, deviceId) {
  if (deviceId) db.prepare('INSERT OR IGNORE INTO revoked (user_id, device_id) VALUES (?, ?)').run(userId, deviceId);
}
const userByEmail = e => db.prepare('SELECT * FROM users WHERE email = ?').get(String(e || '').trim().toLowerCase());
const userById = id => db.prepare('SELECT * FROM users WHERE id = ?').get(id);
function isAdmin(u) { return !!u && (u.role === 'admin' || (ADMIN_EMAIL && u.email === ADMIN_EMAIL)); }
function isPremium(u) { return isAdmin(u) || (!!u && u.premium_until > Date.now()); }
function createUser(email, password, role = 'user') {
  email = String(email || '').trim().toLowerCase();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) throw new Error('Enter a valid email address.');
  if (String(password || '').length < 6) throw new Error('Password must be at least 6 characters.');
  if (userByEmail(email)) throw new Error('An account with this email already exists.');
  const r = db.prepare('INSERT INTO users (email, pass, role, created) VALUES (?, ?, ?, ?)').run(email, hashPassword(password), role, Date.now());
  return userById(Number(r.lastInsertRowid));
}
function extendPremium(u, plan, days) {
  const base = Math.max(Date.now(), u.premium_until || 0);
  const until = plan === 'lifetime' || days === 'lifetime' ? LIFETIME : base + days * DAY;
  db.prepare('UPDATE users SET plan = ?, premium_until = ? WHERE id = ?').run(plan, until, u.id);
}
function accountJson(u, deviceId) {
  const devices = db.prepare('SELECT id, device_id, name, last_seen FROM devices WHERE user_id = ? ORDER BY last_seen DESC').all(u.id);
  return {
    email: u.email,
    admin: isAdmin(u),
    premium: isPremium(u),
    plan: isAdmin(u) ? 'admin' : u.plan,
    planLabel: isAdmin(u) ? 'Administrator' : PLAN_LABEL[u.plan] || u.plan,
    expiresAt: isAdmin(u) || u.premium_until >= LIFETIME ? null : (u.premium_until || null),
    deviceLimit: DEVICE_LIMIT,
    devices: devices.map(d => ({ id: d.id, name: d.name, lastSeen: d.last_seen, thisDevice: d.device_id === deviceId })),
    buyUrl: `${PUBLIC_URL}/account`,
    checkedAt: Date.now(),
    // TV service logins bought on the store: the app adds them as a playlist by itself.
    iptv: activeLines(u.id).filter(l => l.server && l.username).map(l => ({
      name: `${APP_NAME}`, server: l.server, username: l.username, password: l.password, expiresAt: l.expires, trial: !!l.trial })),
  };
}

// Bootstrap the admin account from ADMIN_EMAIL / ADMIN_PASSWORD (after loading the cloud copy, see startup).
function bootstrapAdmin() {
  if (!ADMIN_EMAIL || !ADMIN_PASSWORD) return;
  const u = userByEmail(ADMIN_EMAIL);
  if (!u) createUser(ADMIN_EMAIL, ADMIN_PASSWORD, 'admin');
  else if (u.role !== 'admin') db.prepare(`UPDATE users SET role = 'admin' WHERE id = ?`).run(u.id);
}

// ------------------------------------------------------------------ app
const app = express();
app.disable('x-powered-by');

// Stripe webhook needs the raw body — register before the JSON parser.
app.post('/stripe/webhook', express.raw({ type: 'application/json' }), async (req, res) => {
  if (!stripe) return res.status(400).send('Stripe not configured');
  let event;
  try { event = stripe.webhooks.constructEvent(req.body, req.headers['stripe-signature'], STRIPE_WEBHOOK_SECRET); }
  catch (e) { return res.status(400).send(`Webhook error: ${e.message}`); }
  try { await handleStripeEvent(event); } catch (e) { console.error('webhook', e); return res.status(500).send('error'); }
  res.json({ received: true });
});

app.use(express.json({ limit: '100kb' }));
app.use(express.urlencoded({ extended: false }));

// CORS for the app and the web version.
app.use('/api', (req, res, next) => {
  res.set('Access-Control-Allow-Origin', '*');
  res.set('Access-Control-Allow-Headers', 'Authorization, Content-Type');
  res.set('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
  if (req.method === 'OPTIONS') return res.sendStatus(204);
  next();
});

// Very small login rate limit (per IP).
const attempts = new Map();
function rateLimited(ip) {
  const now = Date.now(); const a = (attempts.get(ip) || []).filter(t => now - t < 15 * 60e3);
  a.push(now); attempts.set(ip, a); return a.length > 20;
}

function authFromRequest(req) {
  const h = req.headers.authorization || '';
  const cookieTok = (req.headers.cookie || '').split(/;\s*/).map(c => c.split('=')).find(([k]) => k === 'novatv_session')?.[1];
  const tok = h.startsWith('Bearer ') ? h.slice(7) : cookieTok;
  if (!tok) return null;
  let t = db.prepare('SELECT * FROM tokens WHERE token = ?').get(tok);
  if (!t) {
    // Unknown token: a signed device token from before a server restart -> restore the device.
    const p = verifyDeviceToken(tok);
    const u = p && userByEmail(p.e);
    if (!u || !p.d) return null;
    if (db.prepare('SELECT 1 FROM revoked WHERE user_id = ? AND device_id = ?').get(u.id, p.d)) return null;
    if (!db.prepare('SELECT 1 FROM devices WHERE user_id = ? AND device_id = ?').get(u.id, p.d)) {
      const count = db.prepare('SELECT COUNT(*) AS n FROM devices WHERE user_id = ?').get(u.id).n;
      if (count >= DEVICE_LIMIT && !isAdmin(u)) return null;
      db.prepare('INSERT INTO devices (user_id, device_id, name, last_seen) VALUES (?, ?, ?, ?)').run(u.id, p.d, p.n || 'Device', Date.now());
    }
    db.prepare('INSERT OR IGNORE INTO tokens (token, user_id, device_id, created) VALUES (?, ?, ?, ?)').run(tok, u.id, p.d, Date.now());
    t = db.prepare('SELECT * FROM tokens WHERE token = ?').get(tok);
    if (!t) return null;
  }
  const u = userById(t.user_id);
  return u ? { user: u, token: t } : null;
}

// ================================================================== APP API
// Step 1 on the TV (TiviMate-style "Log in" / "Sign up"): check the account and list its devices
// so the user can activate a new device or restore an existing one.
function devicePreview(u) {
  const devices = db.prepare('SELECT id, name, last_seen FROM devices WHERE user_id = ? ORDER BY last_seen DESC').all(u.id);
  return { email: u.email, admin: isAdmin(u), premium: isPremium(u), deviceLimit: DEVICE_LIMIT,
    devices: devices.map(d => ({ id: d.id, name: d.name, lastSeen: d.last_seen })) };
}
app.post('/api/check', (req, res) => {
  if (rateLimited(req.ip)) return res.status(429).json({ error: 'Too many attempts. Try again in 15 minutes.' });
  const { email, password } = req.body || {};
  const u = userByEmail(email);
  if (!u || !checkPassword(String(password || ''), u.pass)) return res.status(401).json({ error: 'Wrong email or password.' });
  res.json(devicePreview(u));
});
app.post('/api/signup', (req, res) => {
  if (rateLimited(req.ip)) return res.status(429).json({ error: 'Too many attempts. Try again in 15 minutes.' });
  const { email, password } = req.body || {};
  try { res.json(devicePreview(createUser(email, password))); }
  catch (e) { res.status(400).json({ error: e.message }); }
});

app.post('/api/login', (req, res) => {
  if (rateLimited(req.ip)) return res.status(429).json({ error: 'Too many attempts. Try again in 15 minutes.' });
  const { email, password, deviceId, deviceName, restoreId } = req.body || {};
  const u = userByEmail(email);
  if (!u || !checkPassword(String(password || ''), u.pass)) return res.status(401).json({ error: 'Wrong email or password.' });
  if (!deviceId) return res.status(400).json({ error: 'Missing device id.' });
  // "Your devices": restore an existing activation on this device (e.g. after reinstalling).
  const restore = restoreId ? db.prepare('SELECT * FROM devices WHERE id = ? AND user_id = ?').get(+restoreId, u.id) : null;
  if (restore && restore.device_id !== deviceId) {
    db.prepare('DELETE FROM devices WHERE user_id = ? AND device_id = ?').run(u.id, deviceId);
    db.prepare('DELETE FROM tokens WHERE user_id = ? AND device_id = ?').run(u.id, restore.device_id);
    db.prepare('UPDATE devices SET device_id = ?, name = ?, last_seen = ? WHERE id = ?')
      .run(deviceId, String(deviceName || restore.name).slice(0, 60), Date.now(), restore.id);
  }
  const existing = db.prepare('SELECT * FROM devices WHERE user_id = ? AND device_id = ?').get(u.id, deviceId);
  if (!existing) {
    const count = db.prepare('SELECT COUNT(*) AS n FROM devices WHERE user_id = ?').get(u.id).n;
    if (count >= DEVICE_LIMIT && !isAdmin(u)) {
      return res.status(409).json({ error: `This account is already signed in on ${DEVICE_LIMIT} devices. Remove one at ${PUBLIC_URL}/account and try again.` });
    }
    db.prepare('INSERT INTO devices (user_id, device_id, name, last_seen) VALUES (?, ?, ?, ?)').run(u.id, deviceId, String(deviceName || 'Device').slice(0, 60), Date.now());
  } else {
    db.prepare('UPDATE devices SET name = ?, last_seen = ? WHERE id = ?').run(String(deviceName || existing.name).slice(0, 60), Date.now(), existing.id);
  }
  db.prepare('DELETE FROM revoked WHERE user_id = ? AND device_id = ?').run(u.id, deviceId);
  const token = signDeviceToken(u.email, deviceId, deviceName);
  db.prepare('INSERT OR REPLACE INTO tokens (token, user_id, device_id, created) VALUES (?, ?, ?, ?)').run(token, u.id, deviceId, Date.now());
  res.json({ token, account: accountJson(u, deviceId) });
});

app.get('/api/account', (req, res) => {
  const a = authFromRequest(req);
  if (!a || !a.token.device_id) return res.status(401).json({ error: 'Signed out. Please sign in again.' });
  const dev = db.prepare('SELECT * FROM devices WHERE user_id = ? AND device_id = ?').get(a.user.id, a.token.device_id);
  if (!dev) { db.prepare('DELETE FROM tokens WHERE token = ?').run(a.token.token); return res.status(401).json({ error: 'This device was removed from the account.' }); }
  db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?').run(Date.now(), dev.id);
  res.json({ account: accountJson(a.user, a.token.device_id) });
});

// The app reports the playlist logins (IPTV lines) it uses, so the admin can see them under the customer.
app.post('/api/lines', (req, res) => {
  const a = authFromRequest(req);
  if (!a || !a.token.device_id) return res.status(401).json({ error: 'Signed out. Please sign in again.' });
  const list = Array.isArray(req.body?.lines) ? req.body.lines.slice(0, 20) : [];
  const str = (v, n = 300) => String(v ?? '').slice(0, n);
  db.prepare('DELETE FROM lines WHERE user_id = ? AND device_id = ?').run(a.user.id, a.token.device_id);
  for (const l of list) {
    const streams = (Array.isArray(l.streams) ? l.streams : []).slice(0, 5).map(h => str(h, 120)).join(',');
    db.prepare('INSERT INTO lines (user_id, device_id, name, type, server, username, password, mac, streams, updated) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)')
      .run(a.user.id, a.token.device_id, str(l.name, 80), str(l.type, 20), str(l.server, 500), str(l.username, 120), str(l.password, 120), str(l.mac, 40), streams, Date.now());
  }
  res.json({ ok: true });
});

app.post('/api/logout', (req, res) => {
  const a = authFromRequest(req);
  if (a) {
    if (a.token.device_id) db.prepare('DELETE FROM devices WHERE user_id = ? AND device_id = ?').run(a.user.id, a.token.device_id);
    if (a.token.device_id) db.prepare('DELETE FROM lines WHERE user_id = ? AND device_id = ?').run(a.user.id, a.token.device_id);
    revoke(a.user.id, a.token.device_id);
    db.prepare('DELETE FROM tokens WHERE token = ?').run(a.token.token);
  }
  res.json({ ok: true });
});

app.delete('/api/devices/:id', (req, res) => {
  const a = authFromRequest(req);
  if (!a) return res.status(401).json({ error: 'Signed out.' });
  removeDevice(a.user.id, +req.params.id);
  res.json({ account: accountJson(a.user, a.token.device_id) });
});

app.get('/api/plans', (req, res) => {
  const p = prices();
  res.json({ testMode: testMode(), deviceLimit: DEVICE_LIMIT, buyUrl: `${PUBLIC_URL}/account`,
    plans: [{ id: 'monthly', price: p.monthly }, { id: 'yearly', price: p.yearly }, { id: 'lifetime', price: p.lifetime }] });
});

function removeDevice(userId, deviceRowId) {
  const d = db.prepare('SELECT * FROM devices WHERE id = ? AND user_id = ?').get(deviceRowId, userId);
  if (!d) return;
  db.prepare('DELETE FROM tokens WHERE user_id = ? AND device_id = ?').run(userId, d.device_id);
  db.prepare('DELETE FROM devices WHERE id = ?').run(d.id);
  db.prepare('DELETE FROM lines WHERE user_id = ? AND device_id = ?').run(userId, d.device_id);
  revoke(userId, d.device_id);
}

// ================================================================== WEBSITE
const css = `
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;background:#101216;color:#e6e8eb}
a{color:#6ea8ff}.wrap{max-width:980px;margin:0 auto;padding:28px 20px}
header{display:flex;align-items:center;gap:16px;margin-bottom:26px}header .logo{font-weight:700;font-size:22px;flex:1}
.card{background:#1a1d23;border-radius:12px;padding:22px;margin-bottom:18px}
.plans{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px}
.plan{background:#1a1d23;border-radius:12px;padding:22px;display:flex;flex-direction:column;gap:8px;border:2px solid transparent}
.plan.best{border-color:#2F7BF5}.price{font-size:30px;font-weight:700}.muted{color:rgba(230,232,235,.6)}
button,.btn{background:#2F7BF5;color:#fff;border:0;border-radius:8px;padding:11px 16px;font-size:15px;cursor:pointer;text-decoration:none;display:inline-block;text-align:center}
.btn.gray,button.gray{background:#2b3038}button.red{background:#c62828}
input,select{width:100%;padding:11px 12px;border-radius:8px;border:1px solid #333a44;background:#0f1115;color:#e6e8eb;font-size:15px;margin:4px 0 12px}
table{width:100%;border-collapse:collapse;font-size:14px}td,th{padding:8px 6px;border-bottom:1px solid #2a2f37;text-align:left;vertical-align:top}
.ok{color:#66bb6a}.warn{background:#3b2f00;color:#ffd54f;padding:10px 14px;border-radius:8px;margin-bottom:16px}
.err{background:#3b0d0d;color:#ff8a80;padding:10px 14px;border-radius:8px;margin-bottom:16px}
.row{display:flex;gap:10px;flex-wrap:wrap;align-items:center}form.inline{display:inline}
.line{margin-top:8px;padding:10px 12px;background:#12151a;border-radius:8px;font-size:14px;line-height:1.8;overflow-wrap:anywhere;min-width:280px}.line .k{display:block;color:rgba(230,232,235,.6);font-size:12px;margin-top:4px}code{background:#23272f;padding:2px 6px;border-radius:4px;font-size:14px;overflow-wrap:anywhere}
ul.features{margin:6px 0 0;padding-left:18px;color:rgba(230,232,235,.8);line-height:1.7}
header nav{display:flex;gap:18px;align-items:center;flex-wrap:wrap}header nav a{color:#e6e8eb;text-decoration:none;font-size:15px}header nav a:hover{color:#6ea8ff}
header .logo a{color:#fff;text-decoration:none}header .logo span{color:#2F7BF5}
.hero{padding:56px 0 40px;display:grid;grid-template-columns:1.1fr .9fr;gap:30px;align-items:center}
.hero h1{font-size:46px;line-height:1.08;margin:0 0 16px}.hero p{font-size:18px;color:rgba(230,232,235,.75);margin:0 0 24px;line-height:1.5}
.hero .cta{display:flex;gap:12px;flex-wrap:wrap}.btn.big{padding:14px 22px;font-size:17px;font-weight:600}.btn.ghost{background:transparent;border:1px solid #3a4250}
.screen{background:linear-gradient(145deg,#1d2a44,#101216);border:1px solid #2a3140;border-radius:16px;aspect-ratio:16/10;padding:16px;display:grid;grid-template-columns:1fr 2fr;gap:10px;box-shadow:0 30px 80px rgba(47,123,245,.18)}
.screen .col{display:flex;flex-direction:column;gap:8px}.screen i{display:block;border-radius:6px;background:#233049}.screen i.on{background:#2F7BF5}
.screen .tile{background:linear-gradient(135deg,#2a3b5f,#1a2233);border-radius:8px}
.section{padding:44px 0}.section h2{font-size:30px;margin:0 0 8px;text-align:center}.section .sub{text-align:center;color:rgba(230,232,235,.65);margin:0 0 28px}
.plans[hidden]{display:none}.tabs{display:flex;justify-content:center;gap:8px;margin:0 0 22px;flex-wrap:wrap}.tabs button{background:#1a1d23;border:1px solid #2b3038}.tabs button.on{background:#2F7BF5;border-color:#2F7BF5}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(210px,1fr));gap:16px}
.feat{background:#1a1d23;border-radius:12px;padding:20px}.feat b{display:block;font-size:17px;margin:8px 0 6px}.feat p{margin:0;color:rgba(230,232,235,.7);line-height:1.5;font-size:15px}
.feat .ico{width:38px;height:38px;border-radius:10px;background:rgba(47,123,245,.15);color:#6ea8ff;display:grid;place-items:center;font-size:20px}
.steps{counter-reset:s}.step{background:#1a1d23;border-radius:12px;padding:20px 20px 20px 64px;position:relative}.step:before{counter-increment:s;content:counter(s);position:absolute;left:18px;top:18px;width:32px;height:32px;border-radius:50%;background:#2F7BF5;display:grid;place-items:center;font-weight:700}
.step b{display:block;margin-bottom:4px}.step p{margin:0;color:rgba(230,232,235,.7);line-height:1.5}
details{background:#1a1d23;border-radius:10px;padding:14px 18px;margin-bottom:10px}summary{cursor:pointer;font-weight:600}details p{color:rgba(230,232,235,.75);line-height:1.6;margin:10px 0 0}
.plan ul{margin:6px 0 10px;padding-left:18px;color:rgba(230,232,235,.75);line-height:1.7;font-size:14px}.plan .per{color:rgba(230,232,235,.6);font-size:14px}
.tag{display:inline-block;background:#2F7BF5;color:#fff;border-radius:20px;padding:2px 10px;font-size:12px;font-weight:600}
.band{background:linear-gradient(135deg,#1d4fb8,#2F7BF5);border-radius:16px;padding:34px;text-align:center;margin:30px 0}.band h2{margin:0 0 10px}.band .btn{background:#fff;color:#1d4fb8;font-weight:700}
footer{border-top:1px solid #23272f;margin-top:40px;padding:26px 0;display:flex;gap:20px;flex-wrap:wrap;justify-content:space-between;color:rgba(230,232,235,.6);font-size:14px}footer a{color:rgba(230,232,235,.8);margin-right:14px}
.login{background:#12151a;border-radius:10px;padding:14px 16px;line-height:1.9}.login .k{color:rgba(230,232,235,.6);display:inline-block;width:92px}
@media (max-width:760px){.hero{grid-template-columns:1fr;padding-top:24px}.hero h1{font-size:34px}header nav{gap:12px}}
`;
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function page(title, body, user) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} · ${esc(APP_NAME)}</title><style>${css}</style></head><body><div class="wrap">
<header><div class="logo"><a href="/">${esc(APP_NAME)}</a></div><nav><a href="/">Home</a><a href="/pricing">Pricing</a><a href="/setup">Setup</a><a href="/faq">FAQ</a>
${user ? `${isAdmin(user) ? '<a href="/admin">Admin</a>' : ''}<a href="/account">My account</a><a href="/logout">Sign out</a>` : '<a href="/login">Sign in</a><a class="btn" href="/signup">Create account</a>'}</nav></header>
${testMode() ? '<div class="warn"><b>Test mode.</b> No payment method is set up yet, so purchases are simulated and nobody is charged.</div>' : ''}
${body}${footerHtml()}</div></body></html>`;
}
function footerHtml() {
  const email = setting('contact_email', ''), phone = setting('contact_phone', '');
  const digits = phone.replace(/[^\d]/g, '');
  return `<footer><div><b style="color:#e6e8eb">${esc(APP_NAME)}</b><br>${email ? `<a href="mailto:${esc(email)}">${esc(email)}</a>` : ''}
    ${digits ? `<a href="https://wa.me/${esc(digits)}">WhatsApp</a><a href="sms:${esc(digits)}">Text ${esc(phone)}</a>` : ''}</div>
    <div><a href="/pricing">Pricing</a><a href="/setup">Setup</a><a href="/faq">FAQ</a><a href="/premium">App Premium</a><a href="/terms">Terms</a><a href="/refund">Refund policy</a></div></footer>`;
}
const FEATURES = ['Multiple playlists', 'Recording (live, scheduled, recurring)', 'Catch-up TV', 'Multiview (up to 4 channels)',
  'Favorites and custom channel groups', 'Hide, sort and rename channels', 'Parental control', 'Backup and restore',
  'Themes and layout options', 'Auto frame rate, picture-in-picture, external player', `Use on up to ${DEVICE_LIMIT} devices`];
function plansHtml(user) {
  const p = prices();
  const card = (id, name, sub, best) => `<div class="plan${best ? ' best' : ''}"><div class="muted">${name}</div><div class="price">${money(p[id])}</div><div class="muted">${sub}</div>
    ${!user ? `<a class="btn" href="/signup?plan=${id}">Get ${name}</a>` : `
      ${cashtag() ? `<form method="post" action="/checkout/cashapp/${id}"><button style="width:100%;background:#00c244">Pay with Cash App</button></form>` : ''}
      ${stripe || testMode() ? `<form method="post" action="/checkout/${id}"><button style="width:100%"${cashtag() ? ' class="gray"' : ''}>${stripe ? 'Pay with card' : `Choose ${name}`}</button></form>` : ''}`}</div>`;
  return `<div class="plans">${card('monthly', 'Monthly', 'per month · cancel anytime')}${card('yearly', 'Yearly', 'per year', true)}${card('lifetime', 'Lifetime', 'pay once, keep forever')}</div>`;
}
function setSession(res, userId) {
  const token = newToken();
  db.prepare('INSERT INTO tokens (token, user_id, device_id, created) VALUES (?, ?, NULL, ?)').run(token, userId, Date.now());
  res.set('Set-Cookie', `novatv_session=${token}; HttpOnly; Path=/; SameSite=Lax; Max-Age=${60 * 60 * 24 * 30}${PUBLIC_URL.startsWith('https') ? '; Secure' : ''}`);
}
function webUser(req) { const a = authFromRequest(req); return a && !a.token.device_id ? a.user : null; }
function requireUser(req, res) { const u = webUser(req); if (!u) { res.redirect(`/login${req.method === 'GET' ? `?next=${encodeURIComponent(req.originalUrl)}` : ''}`); return null; } return u; }
const safeNext = n => (typeof n === 'string' && /^\/[a-z]/i.test(n) && !n.startsWith('//') ? n : '');

// ---- store pages
function iptvPlansHtml() {
  const names = { 1: 'Monthly', 3: '3 Months', 6: '6 Months', 12: 'Yearly' };
  const perks = ['All live channels, movies & series in HD / 4K', 'Works on Firestick, Android TV, phones & more', `${APP_NAME} app Premium included`, 'Setup help by text or WhatsApp'];
  if (!bundlePremium()) perks.splice(2, 1);
  const sets = IPTV_DEVICES.map(d => `<div class="plans" data-d="${d}"${d === 1 ? '' : ' hidden'}>${IPTV_MONTHS.map(m => {
    const price = iptvPrice(m, d);
    return `<div class="plan${m === 12 ? ' best' : ''}">${m === 12 ? '<span class="tag">Best value</span>' : ''}<div class="muted">${names[m]}</div>
      <div class="price">${money(price)}</div><div class="per">${plural(d, 'device')} · ${m > 1 ? `${money(Math.round(price / m))}/month` : 'per month'}</div>
      <ul>${perks.map(x => `<li>${esc(x)}</li>`).join('')}</ul>
      <a class="btn" href="/buy?months=${m}&devices=${d}" style="margin-top:auto">Buy now</a></div>`;
  }).join('')}</div>`).join('');
  return `<div class="tabs" role="tablist">${IPTV_DEVICES.map(d => `<button type="button" data-d="${d}" class="${d === 1 ? 'on' : ''}">${plural(d, 'Device')}</button>`).join('')}</div>${sets}
  ${trialOn() ? `<p style="text-align:center;margin-top:18px"><a href="/trial">Not sure yet? Try it free for ${TRIAL_HOURS} hours →</a></p>` : ''}
  <script>document.querySelectorAll('.tabs button').forEach(b=>b.onclick=()=>{document.querySelectorAll('.tabs button').forEach(x=>x.classList.toggle('on',x===b));
  document.querySelectorAll('.plans[data-d]').forEach(p=>p.hidden=p.dataset.d!==b.dataset.d)})</script>`;
}
const FAQS = [
  ['How does it work?', `Pick a plan and pay. Your login is created and shows up in your account right away. Install the ${APP_NAME} app, sign in with your email, and your channels load by themselves.`],
  ['How fast do I get my login?', 'Card and approved Cash App payments are set up automatically. Cash App payments are confirmed by hand, usually within minutes during the day.'],
  ['What devices can I use?', `Amazon Firestick and Fire TV, Android TV and Google TV boxes, Onn, NVIDIA Shield, Android phones and tablets. Your login also works in most Xtream Codes players on other devices.`],
  ['Can I watch on more than one TV?', 'Yes. Choose 1 to 5 devices when you buy. Each device you choose can watch at the same time.'],
  ['Is there a free trial?', `Yes, ${TRIAL_HOURS} hours, one per customer. Create an account and press "Start free trial".`],
  ['Do I need a satellite dish or cable box?', 'No. All you need is an internet connection. 25 Mbps or faster is recommended for HD, 50 Mbps for 4K.'],
  ['How do I renew?', 'Buy the same plan again from your account before it ends. Your app keeps working without any changes.'],
  ['What if a channel is down or buffering?', 'Restart the app first. If it keeps happening, message us with the channel name and we will check it.'],
];
app.get('/', (req, res) => {
  const u = webUser(req);
  const feats = [['📺', 'Live TV', 'Sports, news, kids, local and international channels in one place.'], ['🎬', 'Movies & series', 'A big on-demand library with new titles added often.'],
    ['⚡', 'Fast & stable', 'Quick channel changes and smooth playback on good connections.'], ['📱', 'All your devices', 'TV box, Firestick, phone or tablet: use the devices you already have.'],
    ['🗓️', 'TV guide & catch-up', 'See what’s on, set reminders, and record in the app.'], ['💬', 'Real support', 'Text or WhatsApp us for help with setup or any problem.']];
  res.send(page('Live TV, movies & series', `
  <section class="hero"><div><h1>All your TV in one app.</h1><p>Live channels, movies and series on your TV, phone and tablet, set up in minutes with the ${esc(APP_NAME)} app. No cable box, no contract.</p>
    <div class="cta"><a class="btn big" href="/pricing">See plans</a>${trialOn() ? `<a class="btn big ghost" href="/trial">Free ${TRIAL_HOURS}-hour trial</a>` : ''}</div></div>
    <div class="screen" aria-hidden="true"><div class="col"><i class="on" style="height:22px"></i><i style="height:22px"></i><i style="height:22px"></i><i style="height:22px"></i><i style="height:22px"></i><i style="height:22px"></i></div>
    <div class="col"><div class="tile" style="flex:2"></div><div style="display:grid;grid-template-columns:repeat(3,1fr);gap:8px;flex:1"><div class="tile"></div><div class="tile"></div><div class="tile"></div></div></div></div></section>
  <section class="section" id="pricing"><h2>Choose your plan</h2><p class="sub">Pick how many devices, then how long. Prices include everything.</p>${iptvPlansHtml()}</section>
  <section class="section"><h2>Everything you want to watch</h2><p class="sub">One subscription, all your screens.</p>
    <div class="grid">${feats.map(([i, t, d]) => `<div class="feat"><div class="ico">${i}</div><b>${t}</b><p>${d}</p></div>`).join('')}</div></section>
  <section class="section"><h2>Watching in 3 steps</h2><p class="sub">No technical skills needed.</p><div class="grid steps">
    <div class="step"><b>Pick a plan</b><p>Choose devices and length, or start the free trial.</p></div>
    <div class="step"><b>Install the app</b><p>Get the ${esc(APP_NAME)} app on your Firestick or Android TV box. <a href="/setup">How?</a></p></div>
    <div class="step"><b>Sign in & watch</b><p>Sign in with your email. Your channels load by themselves.</p></div></div></section>
  <section class="section"><h2>Questions</h2><p class="sub">More on the <a href="/faq">FAQ page</a>.</p>${FAQS.slice(0, 5).map(([q, a]) => `<details><summary>${esc(q)}</summary><p>${esc(a)}</p></details>`).join('')}</section>
  <div class="band"><h2>Ready to watch?</h2><p>Set up tonight and start watching in minutes.</p><a class="btn big" href="/pricing">Get started</a></div>`, u));
});
app.get('/pricing', (req, res) => {
  const u = webUser(req);
  res.send(page('Pricing', `<section class="section"><h2>Plans & pricing</h2><p class="sub">Choose how many devices can watch at the same time, then how long.</p>${iptvPlansHtml()}</section>
    <div class="card"><h3 style="margin-top:0">Only need the app?</h3><p class="muted">Already have a TV service? ${esc(APP_NAME)} app Premium is sold on its own too.</p><a class="btn gray" href="/premium">App Premium plans</a></div>`, u));
});
app.get('/faq', (req, res) => {
  const u = webUser(req);
  res.send(page('FAQ', `<section class="section"><h2>Frequently asked questions</h2><p class="sub">Can’t find your answer? Contact us at the bottom of the page.</p>
    ${FAQS.map(([q, a]) => `<details><summary>${esc(q)}</summary><p>${esc(a)}</p></details>`).join('')}</section>`, u));
});
app.get('/setup', (req, res) => {
  const u = webUser(req);
  const code = setting('downloader_code', '');
  res.send(page('Setup', `<section class="section"><h2>Set up in minutes</h2><p class="sub">Pick your device.</p>
  <div class="card"><h3 style="margin-top:0">Amazon Firestick / Fire TV</h3><div class="steps grid" style="grid-template-columns:1fr">
    <div class="step"><b>Allow apps</b><p>Settings › My Fire TV › Developer options › Install unknown apps › turn on <b>Downloader</b>. (No Developer options? Settings › My Fire TV › About › click the device name 7 times.)</p></div>
    <div class="step"><b>Install Downloader</b><p>Search for <b>Downloader</b> on the Fire TV home screen and install it.</p></div>
    <div class="step"><b>Get ${esc(APP_NAME)}</b><p>Open Downloader and type ${code ? `the code <b style="font-size:20px">${esc(code)}</b>` : `this link: <code>${esc(APK_URL)}</code>`}, then press Install.</p></div>
    <div class="step"><b>Sign in</b><p>Open the app › Settings › Premium account › Sign in with the email and password of your account here. Your channels are added by themselves.</p></div></div></div>
  <div class="card"><h3 style="margin-top:0">Android TV, Google TV, Onn, Shield</h3><p class="muted" style="line-height:1.6">Install <b>Downloader</b> from the Play Store, allow it to install apps when asked, then follow steps 3 and 4 above.</p></div>
  <div class="card"><h3 style="margin-top:0">Android phone or tablet</h3><p class="muted" style="line-height:1.6">Open <a href="${esc(APK_URL)}">this link</a> on the phone, install the app (allow “install unknown apps” if asked), and sign in.</p></div>
  <div class="card"><h3 style="margin-top:0">Other apps and devices</h3><p class="muted" style="line-height:1.6">Your login also works in most players that support <b>Xtream Codes</b> (Smart TVs, iPhone, computers). In the player choose “Xtream Codes”, then enter the server, username and password shown on <a href="/account">your account page</a>.</p></div>
  </section>`, u));
});
const legal = (title, paras) => (req, res) => res.send(page(title, `<div class="card"><h2 style="margin-top:0">${title}</h2>${paras.map(t => `<p style="line-height:1.6">${esc(t)}</p>`).join('')}</div>`, webUser(req)));
app.get('/terms', legal('Terms of service', [
  `By buying from ${APP_NAME} you agree to these terms. Your login is for your own household and the number of devices you paid for; sharing or reselling it can get it closed without a refund.`,
  'Availability of individual channels and titles can change without notice. We do our best to keep the service running and fix problems quickly.',
  'You are responsible for making sure using the service is allowed where you live.',
  'We may update these terms. Continuing to use the service means you accept the latest version.']));
app.get('/refund', legal('Refund policy', [
  `Please use the free ${TRIAL_HOURS}-hour trial to test the service on your devices before buying.`,
  'If the service does not work for you within 48 hours of buying, contact us and we will try to fix it; if we can’t, we’ll refund you.',
  'After 48 hours, or once most of a plan has been used, payments are not refundable.']));

// App Premium on its own (for customers who bring their own TV service).
app.get('/premium', (req, res) => {
  const u = webUser(req);
  res.send(page('App Premium', `<div class="card"><h1 style="margin-top:0">Unlock everything in ${esc(APP_NAME)}</h1>
    <ul class="features">${FEATURES.map(f => `<li>${esc(f)}</li>`).join('')}</ul></div>${plansHtml(u)}
    <p class="muted">After buying, open the app on your TV box › Settings › Premium account › Sign in, with the same email and password.</p>`, u));
});

// Buying a TV service plan: confirm, then pay with Cash App.
function iptvChoice(q) {
  const m = +q.months, d = +q.devices;
  return IPTV_MONTHS.includes(m) && IPTV_DEVICES.includes(d) ? { m, d } : null;
}
app.get('/buy', (req, res) => {
  const c = iptvChoice(req.query);
  if (!c) return res.redirect('/pricing');
  const u = webUser(req);
  if (!u) return res.redirect(`/signup?next=${encodeURIComponent(req.originalUrl)}`);
  const plan = `iptv:${c.m}:${c.d}`;
  res.send(page('Checkout', `<div class="card" style="max-width:560px;margin:0 auto"><h2 style="margin-top:0">${esc(planLabel(plan))}</h2>
    <div class="price">${money(iptvPrice(c.m, c.d))}</div>
    <ul class="features"><li>Your login is created for ${plural(c.d, 'device')}</li>${bundlePremium() ? `<li>${esc(APP_NAME)} app Premium for ${plural(c.m, 'month')} included</li>` : ''}<li>Account: ${esc(u.email)}</li></ul>
    ${cashtag() ? `<form method="post" action="/checkout/iptv" style="margin-top:16px"><input type="hidden" name="months" value="${c.m}"><input type="hidden" name="devices" value="${c.d}">
      <button class="big" style="width:100%;background:#00c244">Pay with Cash App</button></form>` : '<div class="warn">Online payment isn’t set up yet. Please contact us to order.</div>'}
    <p class="muted" style="margin-top:14px"><a href="/pricing">← Change plan</a></p></div>`, u));
});
app.post('/checkout/iptv', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  const c = iptvChoice(req.body);
  if (!c || !cashtag()) return res.redirect('/pricing');
  const plan = `iptv:${c.m}:${c.d}`;
  const open = db.prepare(`SELECT * FROM orders WHERE user_id = ? AND plan = ? AND status = 'pending'`).get(u.id, plan);
  const code = open ? open.code : newOrderCode();
  if (!open) db.prepare('INSERT INTO orders (code, user_id, plan, amount, created) VALUES (?, ?, ?, ?, ?)').run(code, u.id, plan, iptvPrice(c.m, c.d), Date.now());
  res.redirect(303, `/pay/${code}`);
});
// Free trial: one per account. Created in the panel right away when the XUI API is set up, otherwise it waits for the admin.
app.get('/trial', (req, res) => {
  const u = webUser(req);
  if (!trialOn()) return res.redirect('/pricing');
  if (!u) return res.redirect('/signup?next=/trial');
  if (trialUsed(u.id)) return res.redirect('/account');
  res.send(page('Free trial', `<div class="card" style="max-width:560px;margin:0 auto"><h2 style="margin-top:0">Free ${TRIAL_HOURS}-hour trial</h2>
    <p class="muted" style="line-height:1.6">One trial per customer, for 1 device. Your login appears on your account page${xuiOn() ? ' right away' : ' as soon as we set it up'}.</p>
    <form method="post" action="/trial"><button class="big" style="width:100%">Start free trial</button></form></div>`, u));
});
app.post('/trial', async (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  if (!trialOn() || trialUsed(u.id)) return res.redirect('/account');
  const code = newOrderCode();
  db.prepare('INSERT INTO orders (code, user_id, plan, amount, created) VALUES (?, ?, ?, ?, ?)').run(code, u.id, 'iptv:trial:1', 0, Date.now());
  if (xuiOn() && setting('iptv_pkg_trial', '')) {
    const o = db.prepare('SELECT * FROM orders WHERE code = ?').get(code);
    try {
      await provisionLine(u, o);
      db.prepare(`UPDATE orders SET status = 'approved', decided = ? WHERE id = ?`).run(Date.now(), o.id);
    } catch (e) { console.error('trial line failed:', e.message); /* stays pending for the admin */ }
  }
  res.redirect('/account');
});

app.get('/signup', (req, res) => {
  res.send(page('Create account', `<div class="card" style="max-width:440px"><h2 style="margin-top:0">Create your account</h2>
  <form method="post" action="/signup"><input type="hidden" name="plan" value="${esc(req.query.plan || '')}"><input type="hidden" name="next" value="${esc(safeNext(req.query.next))}">
  <label>Email<input name="email" type="email" required></label><label>Password<input name="password" type="password" minlength="6" required></label>
  <button style="width:100%">Create account</button></form><p class="muted">Already have one? <a href="/login${req.query.next ? `?next=${encodeURIComponent(safeNext(req.query.next))}` : ''}">Sign in</a></p></div>`));
});
app.post('/signup', (req, res) => {
  try {
    const u = createUser(req.body.email, req.body.password);
    setSession(res, u.id);
    res.redirect(safeNext(req.body.next) || (req.body.plan ? `/account?plan=${encodeURIComponent(req.body.plan)}` : '/account'));
  } catch (e) {
    res.status(400).send(page('Create account', `<div class="err">${esc(e.message)}</div><a href="/signup">Try again</a>`));
  }
});
app.get('/login', (req, res) => {
  res.send(page('Sign in', `<div class="card" style="max-width:440px"><h2 style="margin-top:0">Sign in</h2>
  ${req.query.e ? '<div class="err">Wrong email or password.</div>' : ''}
  <form method="post" action="/login"><input type="hidden" name="next" value="${esc(safeNext(req.query.next))}"><label>Email<input name="email" type="email" required></label><label>Password<input name="password" type="password" required></label>
  <button style="width:100%">Sign in</button></form><p class="muted">New here? <a href="/signup${req.query.next ? `?next=${encodeURIComponent(safeNext(req.query.next))}` : ''}">Create an account</a></p></div>`));
});
app.post('/login', (req, res) => {
  if (rateLimited(req.ip)) return res.status(429).send(page('Sign in', '<div class="err">Too many attempts. Try again in 15 minutes.</div>'));
  const u = userByEmail(req.body.email);
  const next = safeNext(req.body.next);
  if (!u || !checkPassword(String(req.body.password || ''), u.pass)) return res.redirect(`/login?e=1${next ? `&next=${encodeURIComponent(next)}` : ''}`);
  setSession(res, u.id);
  res.redirect(next || (isAdmin(u) ? '/admin' : '/account'));
});
app.get('/logout', (req, res) => {
  const a = authFromRequest(req); if (a && !a.token.device_id) db.prepare('DELETE FROM tokens WHERE token = ?').run(a.token.token);
  res.set('Set-Cookie', 'novatv_session=; Path=/; Max-Age=0'); res.redirect('/');
});

app.get('/account', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  const a = accountJson(u);
  const status = a.premium
    ? `<span class="ok">● Premium</span> · ${esc(a.planLabel)}${a.expiresAt ? ` · ${u.plan === 'monthly' || u.plan === 'yearly' ? 'renews/ends' : 'ends'} ${new Date(a.expiresAt).toLocaleDateString()}` : ' · never expires'}`
    : '<span class="muted">● Free</span>';
  const devices = a.devices.length
    ? `<table><tr><th>Device</th><th>Last used</th><th></th></tr>${a.devices.map(d => `<tr><td>${esc(d.name)}</td><td>${new Date(d.lastSeen).toLocaleString()}</td>
       <td><form class="inline" method="post" action="/account/devices/${d.id}/remove"><button class="gray">Remove</button></form></td></tr>`).join('')}</table>`
    : '<p class="muted">No devices yet. Sign in inside the app to add one.</p>';
  res.send(page('My account', `<div class="card"><h2 style="margin-top:0">${esc(u.email)}</h2><p>${status}</p>
    ${u.stripe_subscription && stripe ? '<form method="post" action="/billing"><button class="gray">Manage billing / cancel</button></form>' : ''}</div>
    ${db.prepare(`SELECT * FROM orders WHERE user_id = ? AND status = 'pending' ORDER BY created DESC`).all(u.id).map(o =>
      o.amount ? `<div class="warn">Order <b>${esc(o.code)}</b> · ${esc(planLabel(o.plan))} · ${money(o.amount)} is waiting for payment. <a href="/pay/${esc(o.code)}">Show payment details</a></div>`
        : `<div class="warn">Your <b>${esc(planLabel(o.plan))}</b> is being set up. Your login will appear here shortly.</div>`).join('')}
    ${tvServiceHtml(u)}
    ${a.premium && (u.plan === 'lifetime' || isAdmin(u)) ? '' : `<h3>${a.premium ? 'Change or extend your plan' : 'Get Premium'}</h3>${plansHtml(u)}`}
    <div class="card" style="margin-top:18px"><h3 style="margin-top:0">Devices (${a.devices.length} of ${DEVICE_LIMIT})</h3>${devices}</div>
    <div class="card"><h3 style="margin-top:0">Sign in on your TV box</h3><p class="muted">Open the app › Settings › Premium account › Sign in, and use this email and password.</p></div>`, u));
});
function tvServiceHtml(u) {
  const lines = db.prepare('SELECT * FROM iptv_lines WHERE user_id = ? ORDER BY created DESC LIMIT 5').all(u.id);
  const row = (k, v) => `<div><span class="k">${k}</span> <code>${esc(v)}</code></div>`;
  const body = lines.length ? lines.map(l => {
    const on = l.expires > Date.now();
    return `<div class="login" style="margin-bottom:10px"><div><b>${l.trial ? 'Free trial' : 'TV service'}</b> · ${plural(l.devices || 1, 'device')} ·
      ${on ? `<span class="ok">active until ${new Date(l.expires).toLocaleString()}</span>` : '<span class="muted">ended</span>'}</div>
      ${l.server ? row('Server', l.server) : ''}${row('Username', l.username)}${row('Password', l.password)}</div>`;
  }).join('') + `<p class="muted" style="line-height:1.6">In the ${esc(APP_NAME)} app, just sign in with this account: your TV service is added by itself. In other apps choose “Xtream Codes” and type the details above. <a href="/setup">Setup help</a></p>`
    : `<p class="muted">You don’t have a TV service plan yet.</p>`;
  const trial = trialOn() && !trialUsed(u.id) ? `<a class="btn gray" href="/trial">Start free ${TRIAL_HOURS}-hour trial</a>` : '';
  return `<div class="card"><h3 style="margin-top:0">Your TV service</h3>${body}<div class="row"><a class="btn" href="/pricing">${lines.length ? 'Renew or upgrade' : 'See plans'}</a>${trial}</div></div>`;
}
app.post('/account/devices/:id/remove', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  removeDevice(u.id, +req.params.id); res.redirect('/account');
});

// ---- checkout
app.post('/checkout/test-complete', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  if (!testMode()) return res.status(403).send('Not in test mode');
  const plan = req.body.plan;
  if (!['monthly', 'yearly', 'lifetime'].includes(plan)) return res.status(400).send('Unknown plan');
  extendPremium(u, plan, plan === 'monthly' ? 31 : plan === 'yearly' ? 366 : 'lifetime');
  db.prepare('INSERT INTO payments (user_id, plan, amount, ref, created) VALUES (?, ?, ?, ?, ?)').run(u.id, plan, prices()[plan], 'TEST', Date.now());
  res.redirect('/checkout/success');
});
app.post('/checkout/:plan', async (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  const plan = req.params.plan;
  const p = prices();
  if (!['monthly', 'yearly', 'lifetime'].includes(plan)) return res.status(400).send('Unknown plan');
  if (testMode()) {
    return res.send(page('Test checkout', `<div class="card" style="max-width:520px"><h2 style="margin-top:0">Test checkout</h2>
      <p>${esc(PLAN_LABEL[plan])} · ${money(p[plan])}</p><p class="muted">Stripe isn’t connected, so this simulates a successful payment.</p>
      <form method="post" action="/checkout/test-complete"><input type="hidden" name="plan" value="${plan}"><button>Simulate successful payment</button></form></div>`, u));
  }
  if (!u.stripe_customer) {
    const c = await stripe.customers.create({ email: u.email, metadata: { user_id: String(u.id) } });
    db.prepare('UPDATE users SET stripe_customer = ? WHERE id = ?').run(c.id, u.id); u.stripe_customer = c.id;
  }
  const productData = { name: `${APP_NAME} Premium (${PLAN_LABEL[plan]})` };
  const session = await stripe.checkout.sessions.create(plan === 'lifetime'
    ? { mode: 'payment', customer: u.stripe_customer,
        line_items: [{ quantity: 1, price_data: { currency: 'usd', unit_amount: p.lifetime, product_data: productData } }],
        metadata: { user_id: String(u.id), plan }, success_url: `${PUBLIC_URL}/checkout/success`, cancel_url: `${PUBLIC_URL}/account` }
    : { mode: 'subscription', customer: u.stripe_customer,
        line_items: [{ quantity: 1, price_data: { currency: 'usd', unit_amount: p[plan], recurring: { interval: plan === 'monthly' ? 'month' : 'year' }, product_data: productData } }],
        metadata: { user_id: String(u.id), plan }, subscription_data: { metadata: { user_id: String(u.id), plan } },
        success_url: `${PUBLIC_URL}/checkout/success`, cancel_url: `${PUBLIC_URL}/account` });
  res.redirect(303, session.url);
});
// ---- Cash App checkout: shows a QR code to pay $cashtag the exact amount with an order code in the note.
const ORDER_STATUS = { approving: 'Setting up…', approved: 'Approved', rejected: 'Rejected', cancelled: 'Cancelled by customer', reversed: 'Undone (not paid)' };
const PLAN_DAYS = { monthly: 31, yearly: 366, lifetime: 'lifetime' };
function newOrderCode() {
  for (;;) {
    const code = 'KV-' + String(crypto.randomInt(1000, 10000));
    if (!db.prepare('SELECT 1 FROM orders WHERE code = ?').get(code)) return code;
  }
}
app.post('/checkout/cashapp/:plan', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  const plan = req.params.plan;
  if (!PLAN_DAYS[plan] || !cashtag()) return res.status(400).send(page('Checkout', '<div class="err">Cash App payments are not available.</div>', u));
  // Reuse an open order for the same plan instead of piling up codes.
  const open = db.prepare(`SELECT * FROM orders WHERE user_id = ? AND plan = ? AND status = 'pending'`).get(u.id, plan);
  const code = open ? open.code : newOrderCode();
  if (!open) db.prepare('INSERT INTO orders (code, user_id, plan, amount, created) VALUES (?, ?, ?, ?, ?)').run(code, u.id, plan, prices()[plan], Date.now());
  res.redirect(303, `/pay/${code}`);
});
app.get('/pay/:code', async (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  const o = db.prepare('SELECT * FROM orders WHERE code = ? AND user_id = ?').get(String(req.params.code), u.id);
  if (!o) return res.status(404).send(page('Payment', '<div class="err">Order not found.</div><a href="/account">Back</a>', u));
  if (o.status === 'approved') return res.send(page('Payment', parsePlan(o.plan)
    ? `<div class="card"><h2 style="margin-top:0">Payment received — you’re all set 🎉</h2><p>Order ${esc(o.code)} · ${esc(planLabel(o.plan))}. Your login is on your account page, and the ${esc(APP_NAME)} app adds it by itself when you sign in.</p><a class="btn" href="/account">See my login</a></div>`
    : `<div class="card"><h2 style="margin-top:0">Payment received — you’re Premium 🎉</h2>
    <p>Order ${esc(o.code)} · ${esc(PLAN_LABEL[o.plan])}. Your TV box picks it up automatically (or open Settings › Premium account).</p><a class="btn" href="/account">My account</a></div>`, u));
  if (o.status !== 'pending') return res.send(page('Payment', `<div class="card"><h2 style="margin-top:0">Order ${esc(o.code)} was ${esc(o.status)}</h2>
    <p class="muted">If you already paid, contact us with your order code.</p><a class="btn" href="/account">Back</a></div>`, u));
  const tag = cashtag();
  const amount = (o.amount / 100).toFixed(2);
  const link = `https://cash.app/$${encodeURIComponent(tag)}/${amount}`;
  const qr = await QRCode.toString(link, { type: 'svg', margin: 1, width: 260, color: { dark: '#000000', light: '#ffffff' } });
  res.send(page('Pay with Cash App', `<meta http-equiv="refresh" content="20">
    <div class="card" style="max-width:560px;margin:0 auto;text-align:center">
      <h2 style="margin-top:0">Pay ${money(o.amount)} with Cash App</h2>
      <p>${esc(parsePlan(o.plan) ? planLabel(o.plan) : `${PLAN_LABEL[o.plan]} Premium`)} · to <b>$${esc(tag)}</b></p>
      <div style="background:#fff;border-radius:12px;display:inline-block;padding:10px;width:280px">${qr}</div>
      <p style="margin:14px 0 6px">1. Scan with your phone camera (or tap the button on your phone)</p>
      <a class="btn" style="background:#00c244" href="${esc(link)}">Open Cash App</a>
      <p style="margin:18px 0 6px">2. In the <b>note</b> ("For"), type this code:</p>
      <div style="font-size:34px;font-weight:800;letter-spacing:2px">${esc(o.code)}</div>
      <p style="margin:18px 0 6px">3. Send exactly <b>${money(o.amount)}</b>. We activate your order as soon as the payment is confirmed.</p>
      <p class="muted">This page updates by itself. Status: <b>waiting for payment</b></p>
      <form method="post" action="/pay/${esc(o.code)}/cancel"><button class="gray">Cancel this order</button></form>
    </div>`, u));
});
app.post('/pay/:code/cancel', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  db.prepare(`UPDATE orders SET status = 'cancelled', decided = ? WHERE code = ? AND user_id = ? AND status = 'pending'`).run(Date.now(), String(req.params.code), u.id);
  res.redirect('/account');
});

app.get('/checkout/success', (req, res) => {
  const u = webUser(req);
  res.send(page('Thank you', `<div class="card"><h2 style="margin-top:0">You’re Premium 🎉</h2><p>Open the app on your TV box › Settings › Premium account › Sign in with ${u ? esc(u.email) : 'your email'}.</p>
    <p class="muted">If you just paid by card it can take a few seconds to activate.</p><a class="btn" href="/account">Go to my account</a></div>`, u));
});
app.post('/billing', async (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  if (!stripe || !u.stripe_customer) return res.redirect('/account');
  const s = await stripe.billingPortal.sessions.create({ customer: u.stripe_customer, return_url: `${PUBLIC_URL}/account` });
  res.redirect(303, s.url);
});

async function handleStripeEvent(event) {
  const o = event.data.object;
  if (event.type === 'checkout.session.completed') {
    const u = userById(+(o.metadata?.user_id)); if (!u) return;
    const plan = o.metadata.plan;
    if (plan === 'lifetime') extendPremium(u, 'lifetime', 'lifetime');
    else {
      db.prepare('UPDATE users SET stripe_subscription = ? WHERE id = ?').run(o.subscription || null, u.id);
      extendPremium(u, plan, plan === 'monthly' ? 31 : 366); // corrected by invoice.paid below
    }
    db.prepare('INSERT INTO payments (user_id, plan, amount, ref, created) VALUES (?, ?, ?, ?, ?)').run(u.id, plan, o.amount_total || 0, o.id, Date.now());
  } else if (event.type === 'invoice.paid') {
    const line = o.lines?.data?.[0];
    const u = db.prepare('SELECT * FROM users WHERE stripe_customer = ?').get(o.customer); if (!u || !line?.period?.end) return;
    if (u.plan === 'lifetime') return;
    const plan = line.metadata?.plan || u.plan;
    db.prepare('UPDATE users SET plan = ?, premium_until = ? WHERE id = ?').run(plan === 'none' ? 'monthly' : plan, line.period.end * 1000 + DAY, u.id);
  } else if (event.type === 'customer.subscription.deleted') {
    const u = db.prepare('SELECT * FROM users WHERE stripe_customer = ?').get(o.customer); if (!u || u.plan === 'lifetime') return;
    db.prepare('UPDATE users SET stripe_subscription = NULL WHERE id = ?').run(u.id); // premium runs until premium_until
  }
}

// ================================================================== ADMIN
function requireAdmin(req, res) { const u = requireUser(req, res); if (!u) return null; if (!isAdmin(u)) { res.status(403).send(page('Admin', '<div class="err">Admins only.</div>', u)); return null; } return u; }
app.get('/admin', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const q = String(req.query.q || '').trim().toLowerCase();
  const users = db.prepare(`SELECT * FROM users ${q ? 'WHERE email LIKE ?' : ''} ORDER BY created DESC LIMIT 200`).all(...(q ? [`%${q}%`] : []));
  const total = db.prepare('SELECT COUNT(*) n FROM users').get().n;
  const premiumCount = db.prepare('SELECT COUNT(*) n FROM users WHERE premium_until > ?').get(Date.now()).n;
  const revenue = db.prepare(`SELECT COALESCE(SUM(amount),0) s FROM payments WHERE ref != 'TEST'`).get().s;
  const p = prices();
  const lineRows = db.prepare(`SELECT l.*, d.name AS device FROM lines l LEFT JOIN devices d ON d.user_id = l.user_id AND d.device_id = l.device_id ORDER BY l.updated DESC`).all();
  const linesHtml = uid => {
    const seen = new Set();
    const mine = lineRows.filter(l => l.user_id === uid).filter(l => { const k = [l.server, l.username, l.password, l.mac].join('|'); if (seen.has(k)) return false; seen.add(k); return true; });
    return mine.map(l => {
      // Domain the playlist comes from; M3U links often carry the login in the link itself (get.php?username=…&password=…).
      let domain = '', user = l.username, pass = l.password;
      try { const u = new URL(/^[a-z]+:\/\//i.test(l.server) ? l.server : `http://${l.server}`); domain = u.origin;
        user = user || u.searchParams.get('username') || ''; pass = pass || u.searchParams.get('password') || ''; } catch { /* not a link */ }
      const streams = String(l.streams || '').split(',').filter(Boolean);
      l = { ...l, username: user, password: pass };
      return `<div class="line"><b>${esc(l.name || l.type || 'Playlist')}</b>${l.device ? ` <span class="muted">on ${esc(l.device)}</span>` : ''}
      ${l.type ? `<span class="k">Type</span><code>${esc(l.type)}</code>` : ''}${domain ? `<span class="k">Domain</span><code>${esc(domain)}</code>` : ''}${streams.length ? `<span class="k">Streams from</span>${streams.map(h => `<code>${esc(h)}</code>`).join('<br>')}` : ''}${l.server ? `<span class="k">Full link</span>${esc(l.server)}` : ''}${l.username ? `<span class="k">Username</span><code>${esc(l.username)}</code>` : ''}${l.password ? `<span class="k">Password</span><code>${esc(l.password)}</code>` : ''}${l.mac ? `<span class="k">MAC</span><code>${esc(l.mac)}</code>` : ''}</div>`;
    }).join('');
  };
  const tvLinesAdmin = uid => db.prepare('SELECT * FROM iptv_lines WHERE user_id = ? ORDER BY created DESC LIMIT 3').all(uid).map(l =>
    `<div class="line" style="border-left:3px solid ${l.expires > Date.now() ? '#66bb6a' : '#555'}"><b>${l.trial ? 'Trial' : 'TV service'} (sold here)</b> <span class="muted">${l.expires > Date.now() ? `until ${new Date(l.expires).toLocaleDateString()}` : 'ended'} · ${plural(l.devices || 1, 'device')}</span>
      <span class="k">Username</span><code>${esc(l.username)}</code><span class="k">Password</span><code>${esc(l.password)}</code></div>`).join('');
  const rows = users.map(u => {
    const devs = db.prepare('SELECT COUNT(*) n FROM devices WHERE user_id = ?').get(u.id).n;
    const st = isAdmin(u) ? '<span class="ok">Admin (always Premium)</span>' : isPremium(u)
      ? `<span class="ok">Premium</span> · ${esc(PLAN_LABEL[u.plan] || u.plan)}${u.premium_until >= LIFETIME ? '' : ` · until ${new Date(u.premium_until).toLocaleDateString()}`}`
      : '<span class="muted">Free</span>';
    return `<tr><td>${esc(u.email)}<div class="muted">joined ${new Date(u.created).toLocaleDateString()}${u.note ? ' · ' + esc(u.note) : ''}</div>${tvLinesAdmin(u.id)}${linesHtml(u.id)}</td><td>${st}</td><td>${devs}/${DEVICE_LIMIT}</td>
      <td><form class="inline row" method="post" action="/admin/users/${u.id}/grant"><select name="days" style="width:auto;margin:0">
        <option value="31">+1 month</option><option value="93">+3 months</option><option value="366">+1 year</option><option value="lifetime">Lifetime</option></select>
        <button>Give Premium</button></form>
        ${!isAdmin(u) && isPremium(u) ? `<form class="inline" method="post" action="/admin/users/${u.id}/revoke"><button class="red">Remove Premium</button></form>` : ''}
        <form class="inline" method="post" action="/admin/users/${u.id}/signout"><button class="gray">Sign out devices</button></form>
        <form class="inline row" method="post" action="/admin/users/${u.id}/password" style="margin-top:6px"><input name="password" placeholder="new password" minlength="6" required style="width:150px;margin:0"><button class="gray">Set password</button></form></td></tr>`;
  }).join('');
  const pending = db.prepare(`SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id WHERE o.status = 'pending' ORDER BY o.created`).all();
  const recent = db.prepare(`SELECT o.*, u.email FROM orders o JOIN users u ON u.id = o.user_id WHERE o.status != 'pending' ORDER BY o.decided DESC LIMIT 10`).all();
  const ordersHtml = `<div class="card"><h3 style="margin-top:0">Cash App payments waiting (${pending.length})</h3>
    ${pending.length ? `<p class="muted">Check your Cash App: find a payment of the amount below with the order code in the note, then press Approve.
      ${xuiOn() ? 'TV service orders get their line created in your panel automatically when you approve.' : 'For TV service orders, create the line in your panel and type its username and password before approving.'}</p>
    <table><tr><th>Code</th><th>Account</th><th>Plan</th><th>Amount</th><th>Ordered</th><th></th></tr>${pending.map(o => {
      const ip = parsePlan(o.plan);
      const ask = o.amount ? `Did you receive ${money(o.amount)} in Cash App with ${esc(o.code)} in the note?\\n\\nOnly approve if the payment is in your Cash App.` : `Approve the free trial for ${esc(o.email)}?`;
      return `<tr><td><b>${esc(o.code)}</b></td><td>${esc(o.email)}</td><td>${esc(planLabel(o.plan))}</td><td>${o.amount ? money(o.amount) : 'Free'}</td><td>${new Date(o.created).toLocaleString()}</td>
      <td><form method="post" action="/admin/orders/${o.id}/approve" onsubmit="return confirm('${ask}')" style="margin-bottom:6px">
        ${ip ? `<input name="username" placeholder="${xuiOn() ? 'username (blank = create automatically)' : 'line username'}" ${xuiOn() ? '' : 'required'} style="margin:0 0 6px">
          <input name="password" placeholder="${xuiOn() ? 'password (blank = automatic)' : 'line password'}" ${xuiOn() ? '' : 'required'} style="margin:0 0 6px">` : ''}
        <button style="background:#00c244">${ip && xuiOn() ? 'Approve & create line' : 'Approve'}</button></form>
        <form class="inline" method="post" action="/admin/orders/${o.id}/reject"><button class="red">Reject</button></form></td></tr>`;
    }).join('')}</table>`
      : '<p class="muted">No payments waiting.</p>'}
    ${recent.length ? `<details style="margin-top:10px" open><summary class="muted">Recent orders</summary><table>
      <tr><th>Code</th><th>Account</th><th>Plan</th><th>Amount</th><th>Status</th><th></th></tr>${recent.map(o => `<tr><td>${esc(o.code)}</td><td>${esc(o.email)}</td>
      <td>${esc(planLabel(o.plan))}</td><td>${o.amount ? money(o.amount) : 'Free'}</td><td>${esc(ORDER_STATUS[o.status] || o.status)}</td>
      <td>${o.status === 'approved' ? `<form class="inline" method="post" action="/admin/orders/${o.id}/undo" onsubmit="return confirm('Undo ${esc(o.code)}? ${parsePlan(o.plan) ? 'This removes the login from the customer’s account and takes back the Premium it gave. Also delete or disable the line in your panel.' : `This removes the ${esc(PLAN_LABEL[o.plan])} Premium it gave.`} ${o.amount ? `It takes ${money(o.amount)} out of Revenue.` : ''}')"><button class="red">Undo approval</button></form>` : ''}</td></tr>`).join('')}</table></details>` : ''}
    <form class="row" method="post" action="/admin/cashtag" style="margin-top:12px"><label style="flex:1">Your Cash App $cashtag (leave empty to turn Cash App off)
      <input name="cashtag" value="${esc(cashtag() ? '$' + cashtag() : '')}" placeholder="$yourcashtag"></label><button class="gray">Save</button></form></div>`;
  const cloudHtml = cloudState.on && cloudState.restored
    ? `<div class="card" style="padding:12px 18px"><span class="ok">● Customer data is saved in the cloud</span> <span class="muted">— accounts survive server restarts.${cloudState.errors ? ` (${cloudState.errors} cloud write retries, last: ${esc(cloudState.lastError)})` : ''}</span></div>`
    : `<div class="err"><b>Customer data is NOT saved.</b> Accounts and orders are erased when the server restarts. ${cloud ? `Cloud database error: ${esc(cloudState.lastError)}` : 'Set TURSO_DATABASE_URL and TURSO_AUTH_TOKEN on Render to fix this.'}</div>`;
  res.send(page('Admin', `${cloudHtml}${ordersHtml}<div class="row" style="margin-bottom:18px"><div class="card" style="flex:1;margin:0"><div class="muted">Accounts</div><div class="price">${total}</div></div>
    <div class="card" style="flex:1;margin:0"><div class="muted">Premium now</div><div class="price">${premiumCount}</div></div>
    <div class="card" style="flex:1;margin:0"><div class="muted">Revenue (real payments)</div><div class="price">${money(revenue)}</div></div></div>
    <div class="card"><h3 style="margin-top:0">Add a subscriber</h3><form class="row" method="post" action="/admin/users">
      <input name="email" type="email" placeholder="email" required style="flex:2;min-width:200px"><input name="password" placeholder="temporary password" minlength="6" required style="flex:1;min-width:160px">
      <select name="days" style="flex:1;min-width:140px"><option value="0">Free</option><option value="31">1 month Premium</option><option value="366">1 year Premium</option><option value="lifetime">Lifetime Premium</option></select>
      <button>Create</button></form></div>
    <div class="card"><h3 style="margin-top:0">Prices</h3><form class="row" method="post" action="/admin/prices">
      <label style="flex:1">Monthly ($)<input name="monthly" value="${(p.monthly / 100).toFixed(2)}"></label>
      <label style="flex:1">Yearly ($)<input name="yearly" value="${(p.yearly / 100).toFixed(2)}"></label>
      <label style="flex:1">Lifetime ($)<input name="lifetime" value="${(p.lifetime / 100).toFixed(2)}"></label><button>Save prices</button></form>
      <p class="muted">New prices apply to new purchases. Existing subscriptions keep their price.</p></div>
    ${storeAdminHtml()}
    <div class="card"><form class="row" method="get" action="/admin"><input name="q" placeholder="Search by email" value="${esc(q)}" style="flex:1;margin:0"><button class="gray">Search</button></form>
      <table style="margin-top:12px"><tr><th>Account</th><th>Status</th><th>Devices</th><th>Actions</th></tr>${rows}</table></div>`, me));
});
app.post('/admin/users', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  try {
    const u = createUser(req.body.email, req.body.password);
    db.prepare('UPDATE users SET note = ? WHERE id = ?').run(`added by admin`, u.id);
    if (req.body.days && req.body.days !== '0') extendPremium(u, req.body.days === 'lifetime' ? 'lifetime' : 'gift', req.body.days === 'lifetime' ? 'lifetime' : +req.body.days);
    res.redirect('/admin');
  } catch (e) { res.status(400).send(page('Admin', `<div class="err">${esc(e.message)}</div><a href="/admin">Back</a>`, me)); }
});
app.post('/admin/users/:id/grant', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const u = userById(+req.params.id); if (!u) return res.redirect('/admin');
  const d = req.body.days;
  if (d === 'lifetime') extendPremium(u, 'lifetime', 'lifetime'); else extendPremium(u, u.plan === 'none' ? 'gift' : u.plan, +d || 31);
  res.redirect('/admin');
});
app.post('/admin/users/:id/revoke', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  db.prepare(`UPDATE users SET plan = 'none', premium_until = 0 WHERE id = ? AND role != 'admin'`).run(+req.params.id);
  res.redirect('/admin');
});
// Customer forgot their password: the admin sets a new one and tells them (passwords can't be shown, they're stored scrambled).
app.post('/admin/users/:id/password', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const u = userById(+req.params.id);
  const pw = String(req.body.password || '');
  if (!u) return res.redirect('/admin');
  if (pw.length < 6) return res.status(400).send(page('Admin', '<div class="err">Password must be at least 6 characters.</div><a href="/admin">Back</a>', me));
  db.prepare('UPDATE users SET pass = ? WHERE id = ?').run(hashPassword(pw), u.id);
  res.send(page('Admin', `<div class="card"><h3 style="margin-top:0">Password changed</h3><p>New password for <b>${esc(u.email)}</b>: <code>${esc(pw)}</code></p>
    <p class="muted">Give it to the customer. Devices already signed in stay signed in.</p><a href="/admin">Back to admin</a></div>`, me));
});
app.post('/admin/users/:id/signout', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  for (const d of db.prepare('SELECT device_id FROM devices WHERE user_id = ?').all(+req.params.id)) revoke(+req.params.id, d.device_id);
  db.prepare('DELETE FROM tokens WHERE user_id = ? AND device_id IS NOT NULL').run(+req.params.id);
  db.prepare('DELETE FROM devices WHERE user_id = ?').run(+req.params.id);
  db.prepare('DELETE FROM lines WHERE user_id = ?').run(+req.params.id);
  res.redirect('/admin');
});
app.post('/admin/orders/:id/approve', async (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  // "approving" locks the order so a double click can't create two lines.
  const lock = db.prepare(`UPDATE orders SET status = 'approving' WHERE id = ? AND status = 'pending'`).run(+req.params.id);
  const o = lock.changes ? db.prepare('SELECT * FROM orders WHERE id = ?').get(+req.params.id) : null;
  const u = o && userById(o.user_id);
  if (!o || !u) { if (o) db.prepare(`UPDATE orders SET status = 'pending' WHERE id = ?`).run(o.id); return res.redirect('/admin'); }
  const ip = parsePlan(o.plan);
  if (ip) {
    const username = String(req.body.username || '').trim(), password = String(req.body.password || '').trim();
    try {
      if (username && password) {
        saveLine(u.id, o.code, { server: iptvServer(), username, password, devices: ip.devices, trial: ip.trial,
          expires: Date.now() + (ip.trial ? TRIAL_HOURS * 3600e3 : ip.months * 31 * DAY) });
      } else if (xuiOn()) {
        await provisionLine(u, o);
      } else throw new Error('Type the username and password of the line you made in your panel, then press Approve.');
    } catch (e) {
      db.prepare(`UPDATE orders SET status = 'pending' WHERE id = ?`).run(o.id);
      return res.status(400).send(page('Admin', `<div class="err">Order ${esc(o.code)} was not approved: ${esc(e.message)}</div>
        <p class="muted">Nothing changed. You can make the line in your panel yourself and type its username and password on the order.</p><a class="btn" href="/admin">Back to admin</a>`, me));
    }
    if (!ip.trial) giveBundlePremium(userById(u.id), ip.months);
  } else {
    extendPremium(u, o.plan, PLAN_DAYS[o.plan]);
  }
  db.prepare(`UPDATE orders SET status = 'approved', decided = ? WHERE id = ?`).run(Date.now(), o.id);
  if (o.amount) db.prepare('INSERT INTO payments (user_id, plan, amount, ref, created) VALUES (?, ?, ?, ?, ?)').run(u.id, o.plan, o.amount, `CASHAPP ${o.code}`, Date.now());
  res.redirect('/admin');
});
// Undo an approval (approved by mistake / payment never arrived): take back the Premium it gave and its revenue.
app.post('/admin/orders/:id/undo', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const o = db.prepare(`SELECT * FROM orders WHERE id = ? AND status = 'approved'`).get(+req.params.id);
  const u = o && userById(o.user_id);
  if (o) {
    db.prepare(`UPDATE orders SET status = 'reversed', decided = ? WHERE id = ?`).run(Date.now(), o.id);
    db.prepare('DELETE FROM payments WHERE ref = ?').run(`CASHAPP ${o.code}`);
  }
  const ip = o && parsePlan(o.plan);
  if (ip) {
    db.prepare('DELETE FROM iptv_lines WHERE order_code = ? AND user_id = ?').run(o.code, o.user_id);
    if (u && !ip.trial) takeBundlePremium(u, ip.months);
  } else if (o && u && !isAdmin(u)) {
    const days = PLAN_DAYS[o.plan];
    const until = days === 'lifetime' ? 0 : (u.premium_until >= LIFETIME ? LIFETIME : u.premium_until - days * DAY);
    if (until > Date.now()) db.prepare('UPDATE users SET premium_until = ? WHERE id = ?').run(until, u.id);
    else db.prepare(`UPDATE users SET plan = 'none', premium_until = 0 WHERE id = ?`).run(u.id);
  }
  res.redirect('/admin');
});
app.post('/admin/orders/:id/reject', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  db.prepare(`UPDATE orders SET status = 'rejected', decided = ? WHERE id = ? AND status = 'pending'`).run(Date.now(), +req.params.id);
  res.redirect('/admin');
});
function storeAdminHtml() {
  const cell = (m, d) => `<td><input name="price_${m}_${d}" value="${(iptvPrice(m, d) / 100).toFixed(2)}" style="width:80px;margin:0 0 4px">
    <input name="pkg_${m}_${d}" value="${esc(setting(`iptv_pkg_${m}_${d}`, ''))}" placeholder="package id" style="width:80px;margin:0"></td>`;
  return `<div class="card"><h3 style="margin-top:0">TV service (reseller store)</h3>
    <p>${xuiOn() ? '<span class="ok">● Panel connected</span> — approving an order creates the line automatically.' : '<span class="muted">● Panel not connected</span> — you type each line’s login when approving. To automate it, set <code>XUI_API_URL</code> and <code>XUI_API_KEY</code> on Render.'}
    ${xuiOn() ? '<form class="inline" method="post" action="/admin/xui/test"><button class="gray">Test connection & show packages</button></form>' : ''}</p>
    <form method="post" action="/admin/store">
      <div class="row"><label style="flex:2;min-width:240px">Server link customers use (Xtream / DNS)<input name="iptv_server" value="${esc(iptvServer())}" placeholder="http://your-dns.com:80"></label>
        <label style="flex:1;min-width:160px">Downloader code for your app<input name="downloader_code" value="${esc(setting('downloader_code', ''))}" placeholder="optional"></label></div>
      <div class="row"><label style="flex:1;min-width:200px">Contact email<input name="contact_email" value="${esc(setting('contact_email', ''))}"></label>
        <label style="flex:1;min-width:200px">WhatsApp / text number<input name="contact_phone" value="${esc(setting('contact_phone', ''))}" placeholder="+1 555 123 4567"></label></div>
      <div class="row" style="margin-bottom:12px"><label><input type="checkbox" name="bundle" value="1" style="width:auto;margin:0 6px 0 0"${bundlePremium() ? ' checked' : ''}>Include ${esc(APP_NAME)} app Premium with every plan</label>
        <label><input type="checkbox" name="trial" value="1" style="width:auto;margin:0 6px 0 0"${trialOn() ? ' checked' : ''}>Offer the free ${TRIAL_HOURS}-hour trial</label>
        <label>Trial package id <input name="pkg_trial" value="${esc(setting('iptv_pkg_trial', ''))}" style="width:90px;margin:0 0 0 6px"></label></div>
      <p class="muted" style="margin:0 0 6px">Prices ($) and, under each, the panel package id used to create that line (see “Test connection”).</p>
      <div style="overflow-x:auto"><table><tr><th></th>${IPTV_DEVICES.map(d => `<th>${plural(d, 'device')}</th>`).join('')}</tr>
        ${IPTV_MONTHS.map(m => `<tr><th>${plural(m, 'month')}</th>${IPTV_DEVICES.map(d => cell(m, d)).join('')}</tr>`).join('')}</table></div>
      <button style="margin-top:12px">Save store settings</button></form></div>`;
}
app.post('/admin/store', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const b = req.body;
  setSetting('iptv_server', String(b.iptv_server || '').trim().replace(/\/+$/, '').slice(0, 200));
  setSetting('downloader_code', String(b.downloader_code || '').trim().slice(0, 20));
  setSetting('contact_email', String(b.contact_email || '').trim().slice(0, 120));
  setSetting('contact_phone', String(b.contact_phone || '').trim().slice(0, 30));
  setSetting('iptv_bundle_premium', b.bundle ? '1' : '0');
  setSetting('iptv_trial', b.trial ? '1' : '0');
  setSetting('iptv_pkg_trial', String(b.pkg_trial || '').trim().slice(0, 20));
  for (const m of IPTV_MONTHS) for (const d of IPTV_DEVICES) {
    const v = Math.round(parseFloat(String(b[`price_${m}_${d}`] || '').replace(/[$,]/g, '')) * 100);
    if (v > 0) setSetting(`iptv_price_${m}_${d}`, v);
    setSetting(`iptv_pkg_${m}_${d}`, String(b[`pkg_${m}_${d}`] || '').trim().slice(0, 20));
  }
  res.redirect('/admin');
});
app.post('/admin/xui/test', async (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  let info = '', pk = '';
  try {
    const u = await xui('user_info');
    const d = Array.isArray(u) ? u[0] : u;
    info = `<p class="ok">● Connected to your panel${d?.username ? ` as <b>${esc(d.username)}</b>` : ''}${d?.credits != null ? ` · <b>${esc(d.credits)}</b> credits` : ''}.</p>`;
  } catch (e) { info = `<div class="err">Couldn’t connect: ${esc(e.message)}</div>`; }
  try {
    const list = await xuiPackages();
    const arr = Array.isArray(list) ? list : Object.values(list || {});
    pk = arr.length ? `<table><tr><th>Package id</th><th>Name</th><th>Details</th></tr>${arr.map(x => `<tr><td><code>${esc(x.id)}</code></td><td>${esc(x.package_name || x.name || '')}</td>
      <td class="muted">${esc(['is_trial', 'is_official', 'trial_credits', 'official_credits', 'trial_duration', 'trial_duration_in', 'official_duration', 'official_duration_in', 'max_connections']
        .filter(k => x[k] != null && x[k] !== '').map(k => `${k.replace(/_/g, ' ')}: ${x[k]}`).join(' · '))}</td></tr>`).join('')}</table>`
      : '<p class="muted">No packages returned.</p>';
  } catch (e) { pk = `<div class="err">Couldn’t load packages: ${esc(e.message)}</div>`; }
  res.send(page('Panel connection', `<div class="card"><h2 style="margin-top:0">Panel connection</h2>${info}<h3>Your packages</h3>${pk}
    <p class="muted">Type each package id under the matching price in Admin › TV service, then save.</p><a class="btn" href="/admin">Back to admin</a></div>`, me));
});
app.post('/admin/cashtag', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  const tag = String(req.body.cashtag || '').replace(/^\$/, '').trim().slice(0, 40);
  if (tag && !/^[A-Za-z0-9_-]+$/.test(tag)) return res.status(400).send(page('Admin', '<div class="err">That doesn’t look like a $cashtag.</div><a href="/admin">Back</a>', me));
  db.prepare('INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v').run('cashtag', tag);
  res.redirect('/admin');
});
app.post('/admin/prices', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  for (const k of ['monthly', 'yearly', 'lifetime']) {
    const cents = Math.round(parseFloat(req.body[k]) * 100);
    if (cents > 0) db.prepare('INSERT INTO settings (k, v) VALUES (?, ?) ON CONFLICT(k) DO UPDATE SET v = excluded.v').run('price_' + k, String(cents));
  }
  res.redirect('/admin');
});

app.get('/health', (req, res) => res.json({ ok: true, testMode: testMode(), cloud: cloudState.on && cloudState.restored, cloudWrites: cloudState.writes, cloudErrors: cloudState.errors }));

app.use((err, req, res, next) => { console.error(err); res.status(500).send(page('Error', `<div class="err">Something went wrong: ${esc(err.message)}</div>`)); });

(async () => {
  if (cloud) {
    // Retry for a while: never start with an empty customer list just because the cloud was slow to answer.
    for (let attempt = 1; ; attempt++) {
      try { await restoreFromCloud(); break; }
      catch (e) {
        cloudState.lastError = e.message; console.error(`cloud restore failed (try ${attempt}):`, e.message);
        if (attempt >= 10) { console.error('Starting without the cloud copy.'); cloudState.on = false; break; }
        await new Promise(r => setTimeout(r, 3000));
      }
    }
  }
  bootstrapAdmin();
  app.listen(PORT, () => {
    console.log(`${APP_NAME} license server on ${PUBLIC_URL} ${testMode() ? '(TEST MODE — no payment method)' : ''}`);
    console.log(cloud ? `Cloud database: ${cloudState.restored ? 'connected' : 'NOT connected'}` : 'Cloud database: not set up (accounts reset when the server restarts)');
    if (!ADMIN_EMAIL) console.log('Tip: set ADMIN_EMAIL and ADMIN_PASSWORD to create your admin account.');
  });
})();
