ClipMax F-Droid Readiness Checklist

This checklist tracks preparation tasks for "com.clipmax.app". A checked item means it was inspected in the repository, not that F-Droid has approved the app.

1. App Identity and Metadata

- [x] Application ID is "com.clipmax.app" in the checked-in Gradle configuration.
- [x] Version code and name are "11" / "1.0.1" in "android/app/build.gradle.kts".
- [x] Minimum SDK is 26; target and compile SDK are 35 in the checked-in Gradle configuration.
- [ ] Review Fastlane metadata wording and release changelog before submission.
- [ ] Confirm all metadata meets current F-Droid requirements.

2. Manifest and Privacy Review

- [x] The checked-in "android/app/src/main/AndroidManifest.xml" does not declare "android.permission.INTERNET".
- [x] The manifest declares "RECEIVE_BOOT_COMPLETED" and the "CaptureExpiryReceiver".
- [x] The keyboard service is protected by Android's "BIND_INPUT_METHOD" permission.
- [ ] Confirm the boot receiver's behavior matches the intended local expiry behavior.
- [ ] Review the merged release manifest from an actual release build.
- [ ] Inspect source code and packaged assets for network requests, telemetry, and tracking.
- [ ] Test runtime behavior and privacy claims; do not infer all behavior solely from manifest permissions.

3. Fonts and Bundled Assets

- [x] Source HTML and packaged HTML contain Google Fonts references.
- [ ] Remove external Google Fonts links from both "index.html" files.
- [ ] Switch CSS font declarations to system font stacks.
- [ ] Build web assets from source and compare the output with checked-in Android assets.
- [ ] Search packaged assets for remaining remote URLs, scripts, stylesheets, and font requests.
- [ ] Verify the origin and license of app icons, logos, fonts, and other bundled assets.

4. Third-Party Licensing

- [x] A GPL version 3 license file is present in the repository.
- [x] A preliminary third-party license inventory is present.
- [ ] Verify exact versions and licenses for all runtime and build dependencies, including transitive dependencies.
- [ ] Confirm license compatibility and include required notices for bundled code and assets.
- [ ] Confirm copyright and licensing declarations accurately cover the submitted source.

5. Build and Release

- [x] The Gradle wrapper is present under "android/".
- [x] The checked-in GitHub Actions workflow runs on pushes to "main" and manual dispatch.
- [x] The workflow uploads a debug APK artifact with 14-day retention.
- [ ] Run a clean build of the current branch and retain the logs.
- [ ] Test the resulting APK on a device, including the main app, keyboard, long-text insertion, backup/restore, and offline behavior.
- [ ] Prepare and verify a release build; a debug APK is not a release artifact.
- [ ] Determine the appropriate F-Droid build recipe and verify that the build can be reproduced from source.
- [ ] Create a release tag only after the release commit is approved and tested.
- [ ] Submit the app to F-Droid; acceptance is determined by F-Droid's review.

Current Status

Repository configuration has been inspected, but font cleanup, clean-build verification, device testing, complete license auditing, and F-Droid submission remain outstanding.

This checklist is not a declaration of F-Droid compliance, reproducibility, or acceptance.
