# Third-Party Licenses and Notices

ClipMax is licensed under the **GNU General Public License v3.0 or later** (`GPL-3.0-or-later`), as documented in the root [`LICENSE`](LICENSE) file.

This document lists the third-party open-source libraries, assets, and components utilized by ClipMax in its native Android runtime, bundled offline web companion interface, and build environment.

---

## 1. Android Native Runtime Dependencies

### AndroidX WebKit
- **Coordinate**: `androidx.webkit:webkit:1.12.1`
- **Origin**: Android Open Source Project / Google LLC
- **Project URL**: https://developer.android.com/jetpack/androidx/releases/webkit
- **License**: Apache License, Version 2.0 (Apache-2.0)
- **Notice**: Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
- **Usage**: Provides API compatibility wrappers for hosting the offline local WebView interface in `MainActivity.kt`.

### Kotlin Standard Library
- **Origin**: JetBrains s.r.o.
- **Project URL**: https://kotlinlang.org/
- **License**: Apache License, Version 2.0 (Apache-2.0)
- **Notice**: Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
- **Usage**: Core language runtime for Android native components (`ClipMaxImeService`, `ClipStore`, `AndroidBridge`, `CapturePreferences`).

### JUnit 4 (Test Dependency Only)
- **Coordinate**: `junit:junit:4.13.2`
- **Origin**: JUnit Team
- **Project URL**: https://junit.org/junit4/
- **License**: Eclipse Public License 1.0 (EPL-1.0)
- **Scope**: Used only during JVM unit test execution (`testImplementation`). Not packaged in release APKs.

---

## 2. Bundled Offline Web Interface Dependencies

The following libraries are compiled into the static, offline single-page application bundled within the Android APK at `android/app/src/main/assets/web/`. ClipMax does not request `android.permission.INTERNET`, ensuring these assets run strictly in a local, offline sandbox.

### React & React DOM
- **Package**: `react` (^19.0.1), `react-dom` (^19.0.1)
- **Origin**: Meta Platforms, Inc.
- **Project URL**: https://react.dev/
- **License**: MIT License
- **Notice**: Copyright (c) Meta Platforms, Inc. and affiliates.

### Lucide Icons
- **Package**: `lucide-react` (^0.546.0)
- **Origin**: Lucide Project
- **Project URL**: https://lucide.dev
- **License**: ISC License
- **Notice**: Copyright (c) 2022-present Lucide Contributors. Portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT).

### JSZip
- **Package**: `jszip` (^3.10.2)
- **Origin**: Stuart Knightley, David Duponchel, Franz Buchinger, António Afonso
- **Project URL**: https://stuk.github.io/jszip/
- **License**: Dual licensed under the MIT License and the GNU General Public License v3.0.
- **Usage**: Used for client-side compression and extraction of local clip backup archives (`.zip`).

### Motion (Framer Motion)
- **Package**: `motion` (^12.23.24)
- **Origin**: Framer B.V.
- **Project URL**: https://motion.dev/
- **License**: MIT License
- **Notice**: Copyright (c) Framer B.V.

### Tailwind CSS & Utility Libraries
- **Packages**: `clsx` (^2.1.1), `tailwind-merge` (^3.7.0), `tailwindcss` (^4.3.3)
- **License**: MIT License
- **Usage**: Class name composition and styling engine compiled into static CSS.

---

## 3. Build & Development Tooling (Not Packaged in APK)

The following development packages are declared in `package.json` for compilation and development server tooling. They are never packaged or shipped inside the Android APK:
- `vite` (^8.3.0), `@vitejs/plugin-react` (^6.1.1), `@tailwindcss/vite` (^4.3.3) — MIT
- `typescript` (^7.0.2), `tsx` (^4.21.0), `esbuild` (^0.25.0) — Apache-2.0 / MIT
- Note: Environment boilerplate packages (`@google/genai`, `express`, `dotenv`) are present in `package.json` from the AI Studio environment template but are not imported, compiled, or referenced anywhere in ClipMax runtime code.

---

## 4. Typography, Wordlists & Artwork Resources

### ClipMax Brand Artwork
- **Files**: `public/logo.jpg`, `src/assets/logo.jpg`, `android/app/src/main/res/mipmap-*/ic_launcher.png`, `android/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png`
- **Origin**: ClipMax original project artwork
- **License**: Distributed under GPL-3.0-or-later as confirmed by the project owner.

### English Suggestion Wordlist
- **File**: `android/app/src/main/res/raw/en_words.txt` (10,001 lines, 82,398 bytes; SHA-256 `1185cfac9cad7ab4aa683a4645e5c93e650fdf7a6717f5624430bf03b6510dd1`)
- **Origin**: Android Open Source Project (AOSP) — LatinIME English US Wordlist (`dictionaries/en_US_wordlist.combined.gz`, Git blob `8aed9c5e0b94d0f3e9654f66104d7af1e84c454f`)
- **Upstream URL**: https://android.googlesource.com/platform/packages/inputmethods/LatinIME
- **Pinned Commit / Version**: Commit `127336e9f29d69607eab55982324b210279ae8c5`
- **License**: Apache License, Version 2.0 (`SPDX-license-identifier-Apache-2.0` declared in `Android.bp` and `NOTICE` at commit `127336e9f29d69607eab55982324b210279ae8c5`; compatible with `GPL-3.0-or-later`)
- **Copyright Notice**: Copyright (c) 2008, The Android Open Source Project. Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0.
- **Extraction & Modifications**: Extracted deterministically from AOSP `dictionaries/en_US_wordlist.combined.gz` at commit `127336e9f29d69607eab55982324b210279ae8c5`: excluded entries with `not_a_word=true`, `possibly_offensive=true`, or `offensive`/`nonword` flags, retained lowercase ASCII words matching `^[a-z]+(?:'[a-z]+)?$` with positive frequency (`f > 0`), sorted descending by `f` (then lexicographically), selected the top 10,000 words plus AOSP entry `clipboard` (`f=67`), and normalized during streaming load in `TypingIntelligenceEngine.fromStream()`.
- **Status**: **RESOLVED — Verified Apache-2.0 License & Upstream AOSP Provenance**

### Typography
- **Configuration**: The web stylesheet (`src/index.css`) and native IME (`ClipMaxImeService.kt`) use only local system font stacks (`system-ui`, `ui-monospace`, `Typeface.SANS_SERIF`, `Typeface.MONOSPACE`). No remote fonts (such as Google Fonts) or external font binaries are included or requested.
