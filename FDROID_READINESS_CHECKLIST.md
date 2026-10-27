# ClipMax F-Droid readiness checklist (draft)

This checklist is a working document, not a statement that the app is already compliant.

## License and ownership
- [ ] Confirm the maintainer has rights to license all ClipMax-authored code and assets under GPL-3.0.
- [ ] Keep the complete, unmodified GPL-3.0 text in the root `LICENSE` file.
- [ ] Add/retain any required third-party notices and license files.
- [ ] Audit all generated or reused code, logos, icons, fonts, and other assets.

## Source and build
- [ ] Confirm the repository contains all source needed to build the APK.
- [ ] Verify the Gradle wrapper and build work from a clean checkout.
- [ ] Review the Gradle dependency tree and each dependency's license.
- [ ] Confirm the built APK contains no proprietary/non-free assets or network-downloaded components that conflict with the intended F-Droid distribution.
- [ ] Verify no secrets, API keys, local paths, or private user data are committed.
- [ ] Test install, upgrade, keyboard enablement, clipboard persistence, backup/restore, and long-text insertion on a real device.

## Metadata and release
- [ ] Update README to match current version and architecture.
- [ ] Add Fastlane metadata under `fastlane/metadata/android/en-US/`.
- [ ] Create and test a release build.
- [ ] Tag the exact source commit used for the release.
- [ ] Verify source archive, build recipe, version code, version name, and changelog all match.
- [ ] Follow the current official F-Droid submission guide and respond to reviewer requests.

## Known repository items to review
- Current `README.md` describes v0.5.1 even though Gradle reports 1.0.1 / versionCode 11.
- Current `README.md` says "No webview wrappers", but the project bundles web UI assets and declares AndroidX WebKit.
- `index.html` references Google Fonts over the network; verify whether those fonts are actually needed at runtime, and if so bundle them locally with compatible licenses.
- Inspect all icon/logo/font assets and dependencies for license compatibility.
