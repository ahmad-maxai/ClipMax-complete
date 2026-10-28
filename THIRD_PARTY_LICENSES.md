# Third-Party Licenses and Notices

ClipMax is licensed under the **GNU General Public License v3.0** (`LICENSE`).

This document lists the third-party open-source libraries, assets, and components utilized by ClipMax in its native Android runtime and bundled offline web interface.

---

## 1. Android Native Dependencies

### AndroidX WebKit
- **Identifier**: `androidx.webkit:webkit:1.12.1`
- **Origin**: Android Open Source Project / Google LLC
- **Project URL**: https://developer.android.com/jetpack/androidx/releases/webkit
- **License**: Apache License, Version 2.0
- **Summary**: Provides API compatibility wrappers for hosting the offline local WebView interface in `MainActivity.kt`.

### JUnit 4 (Test Dependency Only)
- **Identifier**: `junit:junit:4.13.2`
- **Origin**: JUnit Team
- **Project URL**: https://junit.org/junit4/
- **License**: Eclipse Public License 1.0 (EPL-1.0)
- **Scope**: Used only during unit test execution (`testImplementation`). Not packaged in release APKs.

---

## 2. Bundled Offline Web Interface Dependencies

The following libraries are compiled into the static, offline single-page application bundled within the Android APK at `android/app/src/main/assets/web/`. ClipMax does not request `android.permission.INTERNET`, ensuring these assets run in a strictly local, offline sandbox.

### React & React DOM
- **Identifier**: `react` (^19.0.1), `react-dom` (^19.0.1)
- **Origin**: Meta Platforms, Inc.
- **License**: MIT License
- **Copyright**: Copyright (c) Meta Platforms, Inc. and affiliates.

### Lucide Icons
- **Identifier**: `lucide-react` (^0.546.0)
- **Origin**: Lucide Project
- **Project URL**: https://lucide.dev
- **License**: ISC License
- **Copyright**: Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT). All other copyright (c) 2022-present Lucide Contributors.

### JSZip
- **Identifier**: `jszip` (^3.10.2)
- **Origin**: Stuart Knightley, David Duponchel, Franz Buchinger, António Afonso
- **Project URL**: https://stuk.github.io/jszip/
- **License**: Dual licensed under the MIT License and the GNU General Public License v3.0.
- **Usage**: Used for client-side compression/decompression of local clip backup archives (`.zip`).

### Motion (Framer Motion)
- **Identifier**: `motion` (^12.23.24)
- **Origin**: Framer B.V.
- **License**: MIT License
- **Copyright**: Copyright (c) Framer B.V.

### Tailwind CSS & Utility Libraries
- **Identifiers**: `clsx` (^2.1.1), `tailwind-merge` (^3.7.0), `tailwindcss` (^4.3.3)
- **Licenses**: MIT License
- **Summary**: Lightweight class name composition and CSS styling engine.

---

## 3. Typography & Font Resources

### Native Android Virtual Keyboard (IME)
- **Engine**: The native Android keyboard (`ClipMaxImeService.kt`) uses the device's native system typeface (`Typeface.SANS_SERIF` and `Typeface.MONOSPACE`) and custom programmatically drawn hardware vector drawables (`IconDrawable`). It embeds no external font files or decorative emojis.

### Web Interface Typography
- **Configuration**: The web stylesheet (`src/index.css`) declares pure local system font stacks:
  - Sans-Serif: `system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif`
  - Monospace: `ui-monospace, SFMono-Regular, "Roboto Mono", Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace`
- **F-Droid Compliance & Privacy**: All external Google Fonts `<link>` references have been completely removed from `index.html` and the bundled Android assets. Typography relies exclusively on local device system fonts (Roboto and Android system monospace), ensuring 100% offline purity and zero remote network requests.
