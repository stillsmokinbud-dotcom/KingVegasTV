# Signing

Builds are signed with the private key from Documents\NovaTV-signing, passed to GitHub Actions
through the repository secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD.
The key itself is never stored in this repository.

`test-key.p12.b64` is a **public test key** (password `kingvegas-test`, alias `test`). It is only used
if those secrets are missing, so builds still install as updates of each other.
