# F-Droid Submission Guide for ClipMax

Unlike Google Play (where developers upload a pre-compiled `.apk`), **F-Droid builds all applications directly from source code** on their automated, isolated build servers to ensure transparency and trust.

Here is the exact, step-by-step path to get ClipMax listed on the official F-Droid catalog.

---

## Step 1: Merge Your Verified Branch into `main`

Your verified working branch is `chore/fdroid-readiness-and-docs`.

To merge it into `main`:
1. Open this link on your phone:
   👉 **https://github.com/ahmad-maxai/ClipMax-complete/compare/main...chore/fdroid-readiness-and-docs**
2. Tap **Create pull request**.
3. Tap **Merge pull request** -> **Confirm merge**.

---

## Step 2: Tag the `v1.0.1` Release on GitHub

F-Droid requires a git tag matching your `versionName` (`1.0.1` / `versionCode 11`):
1. Open your repository releases page:
   👉 **https://github.com/ahmad-maxai/ClipMax-complete/releases/new**
2. In **Choose a tag**, enter `v1.0.1` and select **Create new tag: v1.0.1 on main**.
3. In **Release title**, enter `ClipMax 1.0.1`.
4. Under description, paste your changelog:
   ```text
   - 100% offline clipboard manager and native virtual keyboard (IME).
   - Zero network permissions (INTERNET permission omitted).
   - Local system font stacks for clean, private typography.
   - Surrogate-safe 50,000-character chunking for long text insertion.
   - Import/export backup support (.txt and .zip).
   ```
5. Tap **Publish release**.

---

## Step 3: Submit to F-Droid (Choose Path A or Path B)

### Path A: The Easiest Way (Request for Packaging — 2 Minutes)
If you don't want to edit F-Droid git files yourself, open an RFP issue and the F-Droid team will package it:
1. Go to the F-Droid RFP issue tracker:
   👉 **https://gitlab.com/fdroid/rfp/-/issues/new**
2. Fill in the short template:
   - **App Name**: ClipMax
   - **Package Name**: `com.clipmax.app`
   - **Source Code**: `https://github.com/ahmad-maxai/ClipMax-complete`
   - **License**: GPL-3.0-or-later
   - **Description**: Offline Android clipboard manager and system keyboard (IME).
3. Tap **Create issue**. The F-Droid inclusion team will pick it up and run the build test.

---

### Path B: The Direct Developer Way (Submit Merge Request to `fdroiddata`)
If you want the fastest inclusion:
1. Fork `https://gitlab.com/fdroid/fdroiddata`.
2. Add the file `metadata/com.clipmax.app.yml` with the contents provided in `com.clipmax.app.yml`.
3. Open a Merge Request against `fdroid/fdroiddata:master`.
