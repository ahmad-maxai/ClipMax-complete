# ClipMax F-Droid Readiness Checklist & Audit Review

This document tracks compliance status, audit findings, and remaining actions for submitting **ClipMax** (`com.clipmax.app`) to F-Droid.

---

## 1. Metadata and App Identification

- [x] **Package ID**: `com.clipmax.app` (consistent across `AndroidManifest.xml` and `build.gradle.kts`).
- [x] **Version Code**: `11`
- [x] **Version Name**: `1.0.1`
- [x] **Target Android SDK**: API 35 (Android 15)
- [x] **Minimum Android SDK**: API 26 (Android 8.0 Oreo)
- [x] **Fastlane Metadata Structure**:
  - `fastlane/metadata/android/en-US/short_description.txt`: 51 characters (below the 80-character maximum limit).
  - `fastlane/metadata/android/en-US/full_description.txt`: 488 characters (accurate summary without marketing fluff).
  - `fastlane/metadata/android/en-US/changelogs/11.txt`: Accompanying release changelog for version 11.
- [x] **Documentation Accuracy**: `README.md` completely updated to accurately describe the dual-tier architecture (native Kotlin IME + offline WebView), actual build commands, and GitHub Actions CI workflow.

---

## 2. Permissions & Anti-Features Audit

- [x] **Zero Tracking / Analytics**: No Firebase Analytics, Google Play Services, Adjust, AppsFlyer, or telemetry SDKs.
- [x] **Zero Advertisements**: No AdMob or external ad networks.
- [x] **Zero Network Permission**: `android.permission.INTERNET` is completely absent from `AndroidManifest.xml`.
- [x] **Declared Permissions**:
  - `android.permission.RECEIVE_BOOT_COMPLETED`: Used solely by `CaptureExpiryReceiver` to re-register local `AlarmManager` timers for expiring temporary capture cards after a device reboot.
  - `android.permission.BIND_INPUT_METHOD`: Standard system protection for `ClipMaxImeService`.
- [x] **Data Isolation**: All clips and backups reside strictly in app-private sandbox storage or user-selected folders via Storage Access Framework (SAF).

---

## 3. License Compatibility

- [x] **Project License**: GNU General Public License v3.0 (`LICENSE` present in repository root).
- [x] **Android Runtime Dependencies**:
  - `androidx.webkit:webkit:1.12.1` — Apache 2.0 (FSDG compatible).
- [x] **Bundled Web Assets**:
  - `react`, `react-dom` — MIT (FSDG compatible).
  - `lucide-react` — ISC (FSDG compatible).
  - `jszip` — MIT / GPL-3.0 dual license (FSDG compatible).
  - `motion`, `clsx`, `tailwind-merge` — MIT (FSDG compatible).
- [x] **License Inventory**: Detailed inventory documented in `THIRD_PARTY_LICENSES.md`.

---

## 4. External Fonts & Network References (Item Under Review)

### Current Situation
- `index.html` currently contains `<link>` tags pointing to Google Fonts:
  ```html
  <link rel="preconnect" href="https://fonts.googleapis.com" />
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
  <link href="https://fonts.googleapis.com/css2?family=Geist:wght@300;400;500;600;700&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet" />
  ```
- **Device Reality**: Because the Android app has no `INTERNET` permission, the Android WebView fails to load these fonts silently. The web UI on actual Android devices falls back immediately to the system fonts: `Roboto` for sans-serif and system `monospace`.
- **F-Droid Policy**: F-Droid scanners flag external URLs (`fonts.googleapis.com`) as Anti-Features (NonFreeNet or external tracking), even if the app cannot reach them.

### Recommended Path (Option A)
- Remove the Google Fonts `<link>` tags from `index.html`.
- Rely entirely on the clean system font stack already declared in `src/index.css` (`system-ui, -apple-system, Roboto, sans-serif` and `ui-monospace, monospace`).
- **Impact**: Zero visual change on physical Android devices (as it was already using Roboto). Eliminates all remote network warnings and reduces HTML parsing overhead.

---

## 5. Build Reproducibility & F-Droid Submission

- [x] **Gradle Wrapper**: Standard `gradlew` (Gradle 8.9) checked into repository.
- [x] **CI Validation**: GitHub Actions builds the installable debug APK on every commit to `main`.
- [ ] **Release Tag**: An annotated git tag (e.g. `v1.0.1`) must be created on the release commit once ready.
- [ ] **F-Droid Build Recipe Metadata**: The F-Droid build recipe will build the Gradle project from source using `assembleRelease`.
