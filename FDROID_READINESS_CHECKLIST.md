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

## 4. External Fonts & Network References (Completed)

- [x] **Zero Remote Network References**: Removed all `<link>` tags pointing to `fonts.googleapis.com` and `fonts.gstatic.com` from `index.html` and the bundled Android web assets.
- [x] **Clean System Font Stacks**: Configured `--font-sans` with `system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif` and `--font-mono` with `ui-monospace, SFMono-Regular, "Roboto Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`.
- [x] **Preserved Layout & Visual Hierarchy**: Visual hierarchy, padding, and tap targets are strictly preserved with zero rendering regression.
- [x] **Fully Offline Compliant**: All assets load strictly from local storage (`android/app/src/main/assets/web/`) with zero network dependency.

---

## 5. Build Reproducibility & F-Droid Submission

- [x] **Gradle Wrapper**: Standard `gradlew` (Gradle 8.9) checked into repository.
- [x] **CI Validation**: GitHub Actions builds the installable debug APK on every commit to `main`.
- [ ] **Release Tag**: An annotated git tag (e.g. `v1.0.1`) must be created on the release commit once ready.
- [ ] **F-Droid Build Recipe Metadata**: The F-Droid build recipe will build the Gradle project from source using `assembleRelease`.
