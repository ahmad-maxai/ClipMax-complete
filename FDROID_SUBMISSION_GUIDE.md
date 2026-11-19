# F-Droid Submission Guide for ClipMax (v0.5.1)

Unlike Google Play (where developers upload a pre-compiled `.apk`), **F-Droid builds all applications directly from source code** on their automated, isolated build servers to ensure transparency and software freedom.

Here is the step-by-step path to get ClipMax listed on the official F-Droid catalog.

---

## Step 1: Resolve Pre-Submission Blockers & Merge `release/fdroid-v0.5.1`

The F-Droid release preparation branch is `release/fdroid-v0.5.1`.

Before tagging or submitting to F-Droid:
1. Resolve the `en_words.txt` wordlist provenance/replacement documented in `THIRD_PARTY_LICENSES.md` (`UNRESOLVED`).
2. Review all changes on GitHub against `main` and merge the release branch into `main` with owner approval.
3. Update `com.clipmax.app.yml` (`commit:`) from the draft pre-tag commit SHA to the final tagged release commit SHA.

---

## Step 2: Tag the `v0.5.1` Release on GitHub

F-Droid's `UpdateCheckMode: Tags` / `AutoUpdateMode: Version` workflow requires a git tag matching the `versionName` (`v0.5.1` / `versionCode 12`) once the release is finalized (do not tag prematurely before blockers are resolved):
1. Open the repository releases page:
   👉 **https://github.com/ahmad-maxai/ClipMax-complete/releases/new**
2. In **Choose a tag**, enter `v0.5.1` and select **Create new tag: v0.5.1 on main**.
3. In **Release title**, enter `ClipMax 0.5.1`.
4. Under description, paste the changelog:
   ```text
   ClipMax v0.5.1 (versionCode 12):
   * Added automatic numeric-field detection and numpad activation in the native keyboard.
   * Added per-item expiry protection to lock clips from automatic retention cleanup.
   * Added interactive first-launch onboarding guide for keyboard activation.
   * Added About ClipMax modal with GPL-3.0-or-later license notice and repository link.
   * Enhanced paste undo/redo compatibility across compact search and single-line text fields.
   * Updated release metadata and official GNU General Public License v3.0 license.
   ```
5. Publish release.

---

## Step 3: Submit to F-Droid (Choose Path A or Path B)

### Path A: Request for Packaging (RFP)
Open an RFP issue and the F-Droid packaging team will assist with the build recipe:
1. Go to the F-Droid RFP issue tracker:
   👉 **https://gitlab.com/fdroid/rfp/-/issues/new**
2. Fill in the template:
   - **App Name**: ClipMax
   - **Package Name**: `com.clipmax.app`
   - **Source Code**: `https://github.com/ahmad-maxai/ClipMax-complete`
   - **License**: GPL-3.0-or-later
   - **Description**: Offline Android clipboard manager and native system keyboard (IME).
3. Submit the issue.

---

### Path B: Direct Merge Request to `fdroiddata`
For direct submission:
1. Fork `https://gitlab.com/fdroid/fdroiddata`.
2. Add the file `metadata/com.clipmax.app.yml` using the template provided in `com.clipmax.app.yml`.
3. Open a Merge Request against `fdroid/fdroiddata:master`.
