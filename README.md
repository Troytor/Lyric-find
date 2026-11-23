# Cadence

Real-time microphone song recognition and word-by-word synced lyrics for Android, powered by live free music APIs (LRCLIB, iTunes Search, Deezer), an on-device prosodic & beat-grid word-pacing engine, and a real-time 16-band microphone beat detector.

## Building APKs on GitHub

This repository includes two GitHub Actions workflows under `.github/workflows/`:

### 1. Automatic Debug APK (`Build Android APK`)
- Runs automatically on every push or pull request to `main` / `master`, or manually from the **Actions** tab (**Build Android APK → Run workflow**).
- Uses JDK 21 and Gradle 9.3.1 to build `app-debug.apk` and uploads it as the downloadable **`cadence-debug-apk`** workflow artifact.

### 2. GitHub Releases (`Release Android APK`)
- Push a version tag (e.g. `git tag v1.0.0 && git push origin v1.0.0`) or run **Release Android APK** manually from the **Actions** tab.
- Publishes a GitHub Release with the built APK attached.

#### Optional: Signed Release APKs
To build signed release APKs (`cadence-release.apk`) automatically in the Release workflow, add these three secrets under **Settings → Secrets and variables → Actions**:

| Secret | Description |
| --- | --- |
| `KEYSTORE_BASE64` | Base64-encoded `.jks` upload keystore (`base64 -w 0 my-upload-key.jks`) with alias `upload` |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Key password for the `upload` alias |

If those secrets are not configured, the Release workflow falls back to building and attaching `cadence-debug.apk`.
