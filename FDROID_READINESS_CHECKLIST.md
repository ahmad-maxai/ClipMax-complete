# ClipMax F-Droid Readiness Checklist & Audit Review

This document tracks compliance status, audit findings, and remaining actions for preparing **ClipMax** (`com.clipmax.app`) for F-Droid.

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
  - `fastlane/metadata/android/en-US/short_description.txt`: 77 characters (below the 80-character limit), covering offline clipboard management and English/Arabic keyboard.
  - `fastlane/metadata/android/en-US/full_description.txt`: Factual summary covering bilingual English/Arabic native keyboard, Arabic RTL UI localization, offline storage, chunked insertion, and privacy.
  - `fastlane/metadata/android/en-US/changelogs/12.txt`: Release changelog for versionCode 12.
  - `fastlane/metadata/android/en-US/images/icon.png`: 512x512 official icon.
  - `fastlane/metadata/android/en-US/images/phoneScreenshots/`: Genuine 720x1600 physical device screenshots from Android 15 Go testing.
- [x] **Documentation Accuracy**: `README.md` describes the dual-tier architecture (native Kotlin English/Arabic IME + offline bilingual LTR/RTL WebView), actual build commands, and testing procedures.

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
- [x] **English Suggestion Wordlist (`en_words.txt`)**: **RESOLVED** — Replaced with a deterministically extracted 10,001-word list from AOSP `platform/packages/inputmethods/LatinIME` (`dictionaries/en_US_wordlist.combined.gz`, commit `127336e9f29d69607eab55982324b210279ae8c5`, `Apache-2.0` per `Android.bp` and `NOTICE`), documented in `THIRD_PARTY_LICENSES.md`.
- [x] **License Inventory**: Detailed inventory and verified upstream notices documented in `THIRD_PARTY_LICENSES.md`.

---

## 4. Typography, Assets & Offline Audit
- [x] **Zero Remote Network References**: No external font links, CDNs, or remote API calls in HTML, CSS, or JS.
- [x] **Clean System Font Stacks**: Configured with local system fonts (`system-ui` and `ui-monospace`).
- [x] **Fully Offline Compliant**: All web assets load strictly from local storage (`android/app/src/main/assets/web/`) with zero network dependency.
- [x] **Development Evidence Removed & Guarded**: Removed development archives, correspondence PDF, prior APKs, and `assets/aistudio/` screenshots from `public/` and `android/app/src/main/assets/web/`, enforced by Vite build checks, Gradle `verifyOfflineWebAssets` / `verify*ApkAssets`, and `scripts/check-version-consistency.py`.
- [x] **No Unverified External Media**: All shipped artwork belongs to ClipMax (`logo.jpg` and launcher icons).

---

## 5. Build Reproducibility, Repository Maturity & F-Droid Submission
- [x] **Gradle Wrapper**: Standard `gradlew` (Gradle 8.9) checked into repository with verified `distributionSha256Sum`.
- [x] **Automated Unit Tests**: 127 `@Test` JVM unit tests covering IME, bilingual Arabic/English layouts, RTL localization, typing intelligence, expiry protection, history transactions, and storage.
- [x] **Resolve `en_words.txt` Provenance**: Verified Apache-2.0 license and exact AOSP commit `127336e9f29d69607eab55982324b210279ae8c5` documented in `THIRD_PARTY_LICENSES.md`.
- [x] **Branch Merge**: Verified release integration branch merged into `main` via Pull Request #5 (merge commit `b3adc5292247915f4d2a17c1dc3e159a242feba5`).
- [x] **Release Tag**: Annotated Git tag `v0.5.1` created and pushed to remote, pointing exactly to approved release commit `b3adc5292247915f4d2a17c1dc3e159a242feba5` (verified by GitHub Actions Run #100).
- [x] **F-Droid Build Recipe Metadata**: Draft recipe template in `com.clipmax.app.yml` pins `commit:` to the approved tagged release commit `b3adc5292247915f4d2a17c1dc3e159a242feba5`.
- [ ] **Three-Month Public Development History Expectation**: GitHub repository `ahmad-maxai/ClipMax-complete` was created on `2026-10-23T12:43:20Z` (~27 days old as of `2026-11-19`). Because F-Droid's Inclusion Policy generally expects at least 3 months of public development/release history before accepting new apps into the official catalog (and no exception has been granted), public history maturity remains an open non-code timeline gate prior to upstream `fdroiddata` RFP or merge request submission.
