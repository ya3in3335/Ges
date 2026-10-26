# Android build, signing and release

## Build configuration (public values)
Passed as Gradle properties (`-PNAME=value`) or environment variables:
| Name | Purpose |
|---|---|
| `API_BASE_URL` | HTTPS API URL ending with `/` (required for a working app) |
| `VERSION_NAME`, `VERSION_CODE` | Release version (CI sets them from the tag / run number) |
| `FIREBASE_APP_ID`, `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID` | Optional push notifications |

In GitHub: Settings → Secrets and variables → Actions → **Variables**: `API_BASE_URL` and the Firebase values.

## Signing (secrets)
Create the keystore once and keep it safe offline — losing it means users cannot update the app:
```bash
keytool -genkeypair -v -keystore release.jks -alias association -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks > release.jks.b64
```
Repository **secrets**: `ANDROID_KEYSTORE_BASE64` (content of `release.jks.b64`),
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Never commit `.jks` files
(`.gitignore` blocks them).

Local signed build:
```bash
ANDROID_KEYSTORE_FILE=/path/release.jks ANDROID_KEYSTORE_PASSWORD=… ANDROID_KEY_ALIAS=association ANDROID_KEY_PASSWORD=… \
  ./gradlew :app:assembleRelease -PAPI_BASE_URL=https://api.example.org/ -PVERSION_NAME=1.0.0 -PVERSION_CODE=1
```

## CI
- Every push/PR touching `android-app/`: unit tests, lint, debug APK → artifact **app-debug-apk** (Actions → run → Artifacts).
- Tag `v1.2.3` (or manual run with *release*): signed release APK, `apksigner verify`, SHA-256 file, GitHub Release with both files.
```bash
git tag v1.0.0 && git push origin v1.0.0
```
Only distribute the signed release APK from the GitHub Release / official website.

## Re-generating client assets
- Intro video: `NODE_PATH=$(npm root -g) node tools/render_intro_video.js` (after any change to `branding/client-animation-src/oran-motion.html`).
- Logo/icons: `python3 tools/build_logo_assets.py`.
