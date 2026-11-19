# ClipMax F-Droid Readiness Checklist & Audit Review

This document tracks compliance status, audit findings, and remaining actions for submitting **ClipMax** (`com.clipmax.app`) to F-Droid.

---

## 1. Metadata and App Identification

- [x] **Package ID**: `com.clipmax.app` (consistent across `AndroidManifest.xml` and `android/app/build.gradle.kts`).
- [x] **Version Code**: `12`
- [x] **Version Name**: `0.5.1`
- [x] **Target Android SDK**: API 35 (Android 15)
- [x] **Minimum Android SDK**: API 26 (Android 8.0 Oreo)
- [x] **Compile Android SDK**: API 35 (Android 15)
- [x] **Fastlane Metadata Structure**:
  - `fastlane/metadata/android/en-US/title.txt`: "ClipMax"
  - `fastlane/metadata/android/en-US/short_description.txt`: 73 characters (below the 80-character limit).
  - `fastlane/metadata/android/en-US/full_description.txt`: Factual summary covering native keyboard, offline storage, chunked insertion, and privacy.
  - `fastlane/metadata/android/en-US/changelogs/12.txt`: Release changelog for versionCode 12.
  - `fastlane/metadata/android/en-US/images/icon.png`: 512x512 official icon.
  - `fastlane/metadata/android/en-US/images/phoneScreenshots/`: Genuine 720x1600 physical device screenshots from Android 15 Go testing.
- [x] **Documentation Accuracy**: `README.md` completely updated to describe the dual-tier architecture (native Kotlin IME + offline WebView), actual build commands, and testing procedures.

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

- [x] **Project License**: GNU General Public License v3.0 or later (`GPL-3.0-or-later`), with official `LICENSE` file in repository root.
- [x] **Android Runtime Dependencies**:
  - `androidx.webkit:webkit:1.12.1` — Apache 2.0 (FSDG compatible).
- [x] **Bundled Web Assets**:
  - `react`, `react-dom` — MIT (FSDG compatible).
  - `lucide-react` — ISC (FSDG compatible).
  - `jszip` — MIT / GPL-3.0 dual license (FSDG compatible).
  - `motion`, `clsx`, `tailwind-merge` — MIT (FSDG compatible).
- [x] **English Suggestion Wordlist (`en_words.txt`)**: **RESOLVED** — Verified upstream provenance from Google 10,000 English Most Common Words by Josh Kaufman (`https://github.com/first20hours/google-10000-english`, commit `a0149023ebcd1e30932da6c28f14b60098f9d0c6`), licensed under the MIT License and documented in `THIRD_PARTY_LICENSES.md`.
- [x] **License Inventory**: Detailed inventory and unresolved wordlist notice documented in `THIRD_PARTY_LICENSES.md`.

---

## 4. Typography, Assets & Offline Audit

- [x] **Zero Remote Network References**: No external font links, CDNs, or remote API calls in HTML, CSS, or JS.
- [x] **Clean System Font Stacks**: Configured with local system fonts (`system-ui` and `ui-monospace`).
- [x] **Fully Offline Compliant**: All web assets load strictly from local storage (`android/app/src/main/assets/web/`) with zero network dependency.
- [x] **Development Evidence Removed & Guarded**: Removed development archives, correspondence PDF, prior APKs, and `assets/aistudio/` screenshots from `public/` and `android/app/src/main/assets/web/`, enforced by Vite build checks, Gradle `verifyOfflineWebAssets` / `verify*ApkAssets`, and `scripts/check-version-consistency.py`.
- [x] **No Unverified External Media**: All shipped artwork belongs to ClipMax (`logo.jpg` and launcher icons).

---

## 5. Build Reproducibility & F-Droid Submission

- [x] **Gradle Wrapper**: Standard `gradlew` (Gradle 8.9) checked into repository.
- [x] **Automated Unit Tests**: 115 JVM unit tests passing in CI.
- [x] **Release Branch Prepared**: `release/fdroid-v0.5.1` based on verified commit `60a626701170e129c431a567341020c6c4e33079`.
- [x] **Resolve `en_words.txt` Provenance**: Verified and documented MIT license and upstream provenance from Google 10,000 English.
- [ ] **Release Tag**: An annotated git tag `v0.5.1` must be created on the final release commit once approved by the project owner.
- [ ] **F-Droid Build Recipe Metadata**: Draft template in `com.clipmax.app.yml` currently references pre-submission commit SHA `20c02d1dddd7ca7753094da3603a1c6551d8421b`; update to final tagged release commit when submitting to `fdroiddata`.
