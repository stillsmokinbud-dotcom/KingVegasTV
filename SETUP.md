# King Vegas TV setup guide

This covers four things: putting the project on GitHub, getting a download code for TV boxes,
running your Premium server, and which devices the app works on.

---

## 1. Put the project on GitHub (about 10 minutes)

1. Create a free account at **github.com** if you don't have one.
2. Install **GitHub Desktop** (desktop.github.com) and sign in.
3. **One-time folder fix:** in `Documents\KingVegasTV`, rename the folder `github-workflows` to
   `.github`, then inside it make a folder `workflows` and move the two `.yml` files into it
   (so you have `.github\workflows\android.yml` and `web.yml`).
   Tip: in File Explorer type the name as `.github.` (with a dot at the end); Windows keeps `.github`.
   (If you extract `KingVegasTV.zip` from the chat instead, this folder is already set up.)
4. In GitHub Desktop: **File › Add local repository…** › choose `Documents\KingVegasTV`.
   It will say it isn't a repository yet: click **create a repository**, then **Create repository**.
5. Click **Publish repository**. Untick "Keep this code private" if you want the APK downloads to be
   public (needed for Downloader codes). Click **Publish**.

GitHub now builds the app automatically every time you publish changes
(repo page › **Actions** tab › "Android APK").

### Add your signing key (once)

Your signing key is in `Documents\NovaTV-signing` (**not** inside the project, never upload it).
Open `README-KEEP-SAFE.txt` there and add the four values it lists as repository secrets:
repo page › **Settings › Secrets and variables › Actions › New repository secret**.

Also add a repository **variable** (same page, "Variables" tab):
`LICENSE_SERVER_URL` = your Premium server address from step 3 (e.g. `https://kingvegastv-server.onrender.com`).

### Releases are automatic

Every time you click **Push origin** in GitHub Desktop, GitHub builds the app (about 5–8 minutes,
repo page › **Actions** tab) and publishes it as the newest release with `KingVegasTV.apk` attached.
Name the repository **KingVegasTV** when you publish it so the link below matches.

This link always points to your newest APK:

```
https://github.com/<your-username>/KingVegasTV/releases/latest/download/KingVegasTV.apk
```

---

## 2. Downloader code for Fire TV / Android TV / Google TV boxes

1. Go to **go.aftvnews.com**, the free link shortener from the maker of the Downloader app.
   (If that address has moved, search "AFTVnews URL shortener".)
2. Paste the link above and create the short link.
3. You get a number, for example `12345`. Customers open the **Downloader** app on their box,
   type the number, and install King Vegas TV. The same number also works in any browser as
   `aftv.news/12345`.

Because the link always points to the latest release, you never have to change the code:
publish a new release and the same code installs the new version.

Phones and tablets (Android) can open the same link in a browser to download and install.

---

## 3. Premium server (accounts, payments, admin panel)

The `server` folder is your Premium website + license server:

* `/` – plans and prices · `/signup` · `/login` · `/account` (subscriber's plan and devices)
* `/admin` – **your** panel: all accounts, give or remove Premium (1 month, 3 months, 1 year,
  lifetime), add subscribers, sign out devices, change prices, revenue.
* Up to **10 devices per account** (change with `DEVICE_LIMIT`).
* Your admin account always has Premium, on every device, without paying.

### Run it online (Render.com, simplest)

1. Sign up at render.com with your GitHub account.
2. **New › Blueprint** › pick your King Vegas TV repo. Render reads `render.yaml`.
3. Fill in the values it asks for:
   * `PUBLIC_URL` – the address Render gives you, e.g. `https://kingvegastv-server.onrender.com`
   * `ADMIN_EMAIL` / `ADMIN_PASSWORD` – your admin login
   * leave the Stripe values empty for now → **test mode** (purchases are simulated)
4. Open `PUBLIC_URL/admin` and sign in with your admin email and password.

(The blueprint uses Render's Starter plan with a small disk so accounts are kept between restarts.
Any host that runs Node 22 or Docker works too; see `server/Dockerfile`.)

### Take real payments (Stripe)

1. Create an account at stripe.com and finish account verification.
2. Developers › API keys › copy the **Secret key** → set `STRIPE_SECRET_KEY` on Render.
3. Developers › Webhooks › **Add endpoint** › URL `PUBLIC_URL/stripe/webhook`, events:
   `checkout.session.completed`, `invoice.paid`, `customer.subscription.deleted`.
   Copy the **Signing secret** → set `STRIPE_WEBHOOK_SECRET`.
4. Restart the service. The yellow "Test mode" banner disappears and checkout is real.
   Monthly and yearly renew automatically; subscribers can cancel from their account page.

Prices start at **$0.99/month, $4.99/year, $14.99 lifetime** (well under TiviMate) and can be
changed any time in `/admin › Prices`.

### How subscribers get Premium (same flow as TiviMate)

1. They open your website on a phone or computer (or scan the QR code in the app), create an
   account and choose a plan.
2. On the TV box: **Settings › Premium account › Sign in** with the same email and password.
3. Premium features unlock on that device. They can use up to 10 devices and remove old ones
   from the website or the app.

### Testing Premium yourself

* **Web preview:** Settings › Premium account › Sign in with `admin@demo.com` / `admin`
  (Premium always on) or `user@demo.com` / `user` (free, to see the paywall). To use your real
  server instead, fill in Settings › Premium account › **Account server** first.
* **Android test (debug) builds** also have **Settings › Premium account › Developer: unlock Premium**.
  It's hidden in release builds.

---

## 4. Which devices work

| Device | How | Status |
|---|---|---|
| Fire TV / Fire Stick, Android TV, Google TV, Nvidia Shield, Android boxes | Downloader code or APK link | ✅ this app |
| Android phones and tablets | APK link | ✅ same app (touch works; phone layout to be polished) |
| Windows / Mac PCs, Chromebooks | Web version (`web/`, published to GitHub Pages) | ⚠️ works, but browsers block `http://` streams on an `https` page and some providers block web players. A proper desktop app (Windows/Mac installer) is the fix, planned next. |
| iPhone, iPad, Apple TV | Needs a separate Apple app | ❌ not yet — requires a Mac and an Apple Developer account ($99/year), and Apple reviews IPTV players strictly |
| Samsung (Tizen) and LG (webOS) smart TVs | Needs separate TV apps / store review | ❌ not yet — can be built from the web version later |
| Roku | Needs a Roku channel (BrightScript) | ❌ not planned yet |

Android covers the large majority of IPTV boxes and sticks, so it comes first.
