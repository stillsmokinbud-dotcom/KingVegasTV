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
const TEST_MODE = !STRIPE_KEY;
const stripe = STRIPE_KEY ? require('stripe')(STRIPE_KEY) : null;
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
CREATE TABLE IF NOT EXISTS revoked (user_id INTEGER NOT NULL, device_id TEXT NOT NULL, PRIMARY KEY (user_id, device_id));
CREATE TABLE IF NOT EXISTS payments (
  id INTEGER PRIMARY KEY AUTOINCREMENT, user_id INTEGER, plan TEXT, amount INTEGER, ref TEXT, created INTEGER
);
`);

// Prices in cents — editable in the admin panel. Defaults undercut TiviMate on purpose.
const DEFAULT_PRICES = { monthly: 99, yearly: 499, lifetime: 1499 };
function prices() {
  const out = { ...DEFAULT_PRICES };
  for (const r of db.prepare(`SELECT k, v FROM settings WHERE k LIKE 'price_%'`).all()) out[r.k.slice(6)] = +r.v;
  return out;
}
const money = c => `$${(c / 100).toFixed(2)}`;
const PLAN_LABEL = { none: 'Free', monthly: 'Monthly', yearly: 'Yearly', lifetime: 'Lifetime', gift: 'Given by admin' };

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
  };
}

// Bootstrap the admin account from ADMIN_EMAIL / ADMIN_PASSWORD.
if (ADMIN_EMAIL && ADMIN_PASSWORD) {
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

app.post('/api/logout', (req, res) => {
  const a = authFromRequest(req);
  if (a) {
    if (a.token.device_id) db.prepare('DELETE FROM devices WHERE user_id = ? AND device_id = ?').run(a.user.id, a.token.device_id);
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
  res.json({ testMode: TEST_MODE, deviceLimit: DEVICE_LIMIT, buyUrl: `${PUBLIC_URL}/account`,
    plans: [{ id: 'monthly', price: p.monthly }, { id: 'yearly', price: p.yearly }, { id: 'lifetime', price: p.lifetime }] });
});

function removeDevice(userId, deviceRowId) {
  const d = db.prepare('SELECT * FROM devices WHERE id = ? AND user_id = ?').get(deviceRowId, userId);
  if (!d) return;
  db.prepare('DELETE FROM tokens WHERE user_id = ? AND device_id = ?').run(userId, d.device_id);
  db.prepare('DELETE FROM devices WHERE id = ?').run(d.id);
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
ul.features{margin:6px 0 0;padding-left:18px;color:rgba(230,232,235,.8);line-height:1.7}
`;
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
function page(title, body, user) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(title)} · ${esc(APP_NAME)}</title><style>${css}</style></head><body><div class="wrap">
<header><div class="logo">${esc(APP_NAME)} Premium</div>${user ? `${isAdmin(user) ? '<a href="/admin">Admin</a>' : ''}<a href="/account">My account</a><a href="/logout">Sign out</a>` : '<a href="/login">Sign in</a><a class="btn" href="/signup">Create account</a>'}</header>
${TEST_MODE ? '<div class="warn"><b>Test mode.</b> Stripe isn’t connected yet, so purchases are simulated and nobody is charged.</div>' : ''}
${body}</div></body></html>`;
}
const FEATURES = ['Multiple playlists', 'Recording (live, scheduled, recurring)', 'Catch-up TV', 'Multiview (up to 4 channels)',
  'Favorites and custom channel groups', 'Hide, sort and rename channels', 'Parental control', 'Backup and restore',
  'Themes and layout options', 'Auto frame rate, picture-in-picture, external player', `Use on up to ${DEVICE_LIMIT} devices`];
function plansHtml(user) {
  const p = prices();
  const card = (id, name, sub, best) => `<div class="plan${best ? ' best' : ''}"><div class="muted">${name}</div><div class="price">${money(p[id])}</div><div class="muted">${sub}</div>
    ${user ? `<form method="post" action="/checkout/${id}"><button style="width:100%">Choose ${name}</button></form>` : `<a class="btn" href="/signup?plan=${id}">Get ${name}</a>`}</div>`;
  return `<div class="plans">${card('monthly', 'Monthly', 'per month · cancel anytime')}${card('yearly', 'Yearly', 'per year', true)}${card('lifetime', 'Lifetime', 'pay once, keep forever')}</div>`;
}
function setSession(res, userId) {
  const token = newToken();
  db.prepare('INSERT INTO tokens (token, user_id, device_id, created) VALUES (?, ?, NULL, ?)').run(token, userId, Date.now());
  res.set('Set-Cookie', `novatv_session=${token}; HttpOnly; Path=/; SameSite=Lax; Max-Age=${60 * 60 * 24 * 30}${PUBLIC_URL.startsWith('https') ? '; Secure' : ''}`);
}
function webUser(req) { const a = authFromRequest(req); return a && !a.token.device_id ? a.user : null; }
function requireUser(req, res) { const u = webUser(req); if (!u) { res.redirect('/login'); return null; } return u; }

app.get('/', (req, res) => {
  const u = webUser(req);
  res.send(page('Premium', `<div class="card"><h1 style="margin-top:0">Unlock everything in ${esc(APP_NAME)}</h1>
    <ul class="features">${FEATURES.map(f => `<li>${esc(f)}</li>`).join('')}</ul></div>${plansHtml(u)}
    <p class="muted">After buying, open the app on your TV box › Settings › Premium account › Sign in, with the same email and password.</p>`, u));
});

app.get('/signup', (req, res) => {
  res.send(page('Create account', `<div class="card" style="max-width:440px"><h2 style="margin-top:0">Create your account</h2>
  <form method="post" action="/signup"><input type="hidden" name="plan" value="${esc(req.query.plan || '')}">
  <label>Email<input name="email" type="email" required></label><label>Password<input name="password" type="password" minlength="6" required></label>
  <button style="width:100%">Create account</button></form><p class="muted">Already have one? <a href="/login">Sign in</a></p></div>`));
});
app.post('/signup', (req, res) => {
  try {
    const u = createUser(req.body.email, req.body.password);
    setSession(res, u.id);
    res.redirect(req.body.plan ? `/account?plan=${encodeURIComponent(req.body.plan)}` : '/account');
  } catch (e) {
    res.status(400).send(page('Create account', `<div class="err">${esc(e.message)}</div><a href="/signup">Try again</a>`));
  }
});
app.get('/login', (req, res) => {
  res.send(page('Sign in', `<div class="card" style="max-width:440px"><h2 style="margin-top:0">Sign in</h2>
  ${req.query.e ? '<div class="err">Wrong email or password.</div>' : ''}
  <form method="post" action="/login"><label>Email<input name="email" type="email" required></label><label>Password<input name="password" type="password" required></label>
  <button style="width:100%">Sign in</button></form><p class="muted">New here? <a href="/signup">Create an account</a></p></div>`));
});
app.post('/login', (req, res) => {
  if (rateLimited(req.ip)) return res.status(429).send(page('Sign in', '<div class="err">Too many attempts. Try again in 15 minutes.</div>'));
  const u = userByEmail(req.body.email);
  if (!u || !checkPassword(String(req.body.password || ''), u.pass)) return res.redirect('/login?e=1');
  setSession(res, u.id);
  res.redirect(isAdmin(u) ? '/admin' : '/account');
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
    ${a.premium && (u.plan === 'lifetime' || isAdmin(u)) ? '' : `<h3>${a.premium ? 'Change or extend your plan' : 'Get Premium'}</h3>${plansHtml(u)}`}
    <div class="card" style="margin-top:18px"><h3 style="margin-top:0">Devices (${a.devices.length} of ${DEVICE_LIMIT})</h3>${devices}</div>
    <div class="card"><h3 style="margin-top:0">Sign in on your TV box</h3><p class="muted">Open the app › Settings › Premium account › Sign in, and use this email and password.</p></div>`, u));
});
app.post('/account/devices/:id/remove', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  removeDevice(u.id, +req.params.id); res.redirect('/account');
});

// ---- checkout
app.post('/checkout/test-complete', (req, res) => {
  const u = requireUser(req, res); if (!u) return;
  if (!TEST_MODE) return res.status(403).send('Not in test mode');
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
  if (TEST_MODE) {
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
  const rows = users.map(u => {
    const devs = db.prepare('SELECT COUNT(*) n FROM devices WHERE user_id = ?').get(u.id).n;
    const st = isAdmin(u) ? '<span class="ok">Admin (always Premium)</span>' : isPremium(u)
      ? `<span class="ok">Premium</span> · ${esc(PLAN_LABEL[u.plan] || u.plan)}${u.premium_until >= LIFETIME ? '' : ` · until ${new Date(u.premium_until).toLocaleDateString()}`}`
      : '<span class="muted">Free</span>';
    return `<tr><td>${esc(u.email)}<div class="muted">joined ${new Date(u.created).toLocaleDateString()}${u.note ? ' · ' + esc(u.note) : ''}</div></td><td>${st}</td><td>${devs}/${DEVICE_LIMIT}</td>
      <td><form class="inline row" method="post" action="/admin/users/${u.id}/grant"><select name="days" style="width:auto;margin:0">
        <option value="31">+1 month</option><option value="93">+3 months</option><option value="366">+1 year</option><option value="lifetime">Lifetime</option></select>
        <button>Give Premium</button></form>
        ${!isAdmin(u) && isPremium(u) ? `<form class="inline" method="post" action="/admin/users/${u.id}/revoke"><button class="red">Remove Premium</button></form>` : ''}
        <form class="inline" method="post" action="/admin/users/${u.id}/signout"><button class="gray">Sign out devices</button></form></td></tr>`;
  }).join('');
  res.send(page('Admin', `<div class="row" style="margin-bottom:18px"><div class="card" style="flex:1;margin:0"><div class="muted">Accounts</div><div class="price">${total}</div></div>
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
app.post('/admin/users/:id/signout', (req, res) => {
  const me = requireAdmin(req, res); if (!me) return;
  for (const d of db.prepare('SELECT device_id FROM devices WHERE user_id = ?').all(+req.params.id)) revoke(+req.params.id, d.device_id);
  db.prepare('DELETE FROM tokens WHERE user_id = ? AND device_id IS NOT NULL').run(+req.params.id);
  db.prepare('DELETE FROM devices WHERE user_id = ?').run(+req.params.id);
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

app.get('/health', (req, res) => res.json({ ok: true, testMode: TEST_MODE }));

app.use((err, req, res, next) => { console.error(err); res.status(500).send(page('Error', `<div class="err">Something went wrong: ${esc(err.message)}</div>`)); });

app.listen(PORT, () => {
  console.log(`${APP_NAME} license server on ${PUBLIC_URL} ${TEST_MODE ? '(TEST MODE — no Stripe key)' : ''}`);
  if (!ADMIN_EMAIL) console.log('Tip: set ADMIN_EMAIL and ADMIN_PASSWORD to create your admin account.');
});
