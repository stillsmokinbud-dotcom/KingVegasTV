# Test signing key

`test-key.p12.b64` is a **public test key** (password `kingvegas-test`, alias `test`).
GitHub Actions uses it when the repo has no `KEYSTORE_BASE64` secret, so every build is
signed with the same key and installs as an update over the previous one.

Because it is public, anyone could sign an app with it. Before you sell the app, add your
private key from Documents\NovaTV-signing as repository secrets (see SETUP.md). Switching
keys means uninstalling the test build once.
