# Releasing

Quiet Archive ships signed APKs through GitHub Actions. Nothing secret lives in
the repository — the signing key stays in your account and in GitHub secrets.

## One-time setup

1. **Create the keystore** (if you do not have one yet):

   ```powershell
   keytool -genkeypair -v -keystore quietarchive-release.jks `
     -alias quietarchive -keyalg RSA -keysize 2048 -validity 10000 `
     -dname "CN=Quiet Archive, O=ssuvamm, C=IN"
   ```

   Keep it somewhere safe and **back it up**. Losing it means users can never
   update over an installed build.

2. **Local builds** read `keystore.properties` from the repository root:

   ```properties
   storeFile=C:/path/to/quietarchive-release.jks
   storePassword=…
   keyAlias=quietarchive
   keyPassword=…
   ```

   The file is git-ignored. Without it the release build falls back to the
   debug key so the APK still installs (but users cannot update over it later).

3. **GitHub secrets** (Settings → Secrets and variables → Actions):

   | Secret | Value |
   | --- | --- |
   | `SIGNING_KEYSTORE_BASE64` | `base64 -w0 quietarchive-release.jks` (macOS/Linux: `base64 < quietarchive-release.jks \| tr -d '\n'`) |
   | `SIGNING_STORE_PASSWORD` | keystore password |
   | `SIGNING_KEY_ALIAS` | `quietarchive` |
   | `SIGNING_KEY_PASSWORD` | key password |

   Set them with the CLI:

   ```powershell
   gh secret set SIGNING_KEYSTORE_BASE64 < (certutil -encode quietarchive-release.jks stdout | Select-Object -Skip 1 -First 40)
   gh secret set SIGNING_STORE_PASSWORD
   gh secret set SIGNING_KEY_ALIAS
   gh secret set SIGNING_KEY_PASSWORD
   ```

## Cutting a release

```powershell
# 1. Update CHANGELOG.md with the new version section.
git commit -am "Release 1.1.0"
git tag -a v1.1.0 -m "Quiet Archive 1.1.0"
git push origin main --tags
```

Pushing the tag triggers `.github/workflows/release.yml`, which builds a signed
universal APK, writes the matching `CHANGELOG.md` section into the release
notes and publishes the APK plus a `SHA256SUMS.txt` to the GitHub release.

You can also run it by hand: **Actions → Release → Run workflow**, with the
tag that should be released.

## What CI needs

The Gradle build cross-compiles the Rust core, so the workflow installs Rust,
`cargo-ndk` and the Android NDK before running Gradle. If `cargo-ndk` fails to
install, pin it with `cargo install cargo-ndk --locked --version <x.y.z>`.
