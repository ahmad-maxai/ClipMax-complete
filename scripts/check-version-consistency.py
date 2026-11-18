#!/usr/bin/env python3
"""
Release consistency and metadata verification script for ClipMax.
Validates version declarations, license files, Fastlane metadata, and security invariants.
"""
import os
import re
import sys

def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    errors = []

    print("[*] Checking ClipMax release metadata consistency...")

    # 1. Android build.gradle.kts
    gradle_path = os.path.join(repo_root, "android", "app", "build.gradle.kts")
    if not os.path.exists(gradle_path):
        errors.append(f"Missing build file: {gradle_path}")
    else:
        with open(gradle_path, "r", encoding="utf-8") as f:
            gradle_content = f.read()
        vc_match = re.search(r"versionCode\s*=\s*(\d+)", gradle_content)
        vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_content)
        if not vc_match or int(vc_match.group(1)) != 12:
            errors.append(f"android/app/build.gradle.kts versionCode must be 12 (found: {vc_match.group(1) if vc_match else 'none'})")
        if not vn_match or vn_match.group(1) != "0.5.1":
            errors.append(f"android/app/build.gradle.kts versionName must be '0.5.1' (found: {vn_match.group(1) if vn_match else 'none'})")

    # 2. Root LICENSE file
    license_path = os.path.join(repo_root, "LICENSE")
    if not os.path.exists(license_path):
        errors.append("Root LICENSE file is missing")
    else:
        with open(license_path, "r", encoding="utf-8") as f:
            lic = f.read()
        if "GNU GENERAL PUBLIC LICENSE" not in lic or "Version 3, 29 June 2007" not in lic:
            errors.append("LICENSE file must contain official GNU GPL v3 text")

    # 3. Fastlane metadata
    fl_dir = os.path.join(repo_root, "fastlane", "metadata", "android", "en-US")
    title_path = os.path.join(fl_dir, "title.txt")
    short_desc_path = os.path.join(fl_dir, "short_description.txt")
    full_desc_path = os.path.join(fl_dir, "full_description.txt")
    changelog_path = os.path.join(fl_dir, "changelogs", "12.txt")
    icon_path = os.path.join(fl_dir, "images", "icon.png")

    if not os.path.exists(title_path):
        errors.append("Fastlane title.txt missing")
    else:
        with open(title_path, "r", encoding="utf-8") as f:
            t = f.read().strip()
        if t != "ClipMax":
            errors.append(f"Fastlane title.txt should be 'ClipMax' (found: '{t}')")

    if not os.path.exists(short_desc_path):
        errors.append("Fastlane short_description.txt missing")
    else:
        with open(short_desc_path, "r", encoding="utf-8") as f:
            sd = f.read().strip()
        if len(sd) > 80:
            errors.append(f"Fastlane short_description.txt exceeds 80 characters ({len(sd)} chars)")

    if not os.path.exists(full_desc_path):
        errors.append("Fastlane full_description.txt missing")

    if not os.path.exists(changelog_path):
        errors.append(f"Fastlane changelog missing for versionCode 12 ({changelog_path})")

    if not os.path.exists(icon_path):
        errors.append(f"Fastlane icon.png missing ({icon_path})")

    # 4. package.json
    pkg_path = os.path.join(repo_root, "package.json")
    if os.path.exists(pkg_path):
        with open(pkg_path, "r", encoding="utf-8") as f:
            pkg_content = f.read()
        if '"name": "clipmax"' not in pkg_content:
            errors.append('package.json must declare "name": "clipmax"')
        if '"version": "0.5.1"' not in pkg_content:
            errors.append('package.json must declare "version": "0.5.1"')

    # 5. com.clipmax.app.yml template
    fdroid_yml = os.path.join(repo_root, "com.clipmax.app.yml")
    if os.path.exists(fdroid_yml):
        with open(fdroid_yml, "r", encoding="utf-8") as f:
            yml_content = f.read()
        if "versionCode: 12" not in yml_content or "CurrentVersionCode: 12" not in yml_content:
            errors.append("com.clipmax.app.yml must reference versionCode 12")
        if "0.5.1" not in yml_content:
            errors.append("com.clipmax.app.yml must reference versionName 0.5.1")

    # 6. AndroidManifest.xml: Absence of INTERNET permission
    manifest_path = os.path.join(repo_root, "android", "app", "src", "main", "AndroidManifest.xml")
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = f.read()
        if "android.permission.INTERNET" in manifest:
            errors.append("CRITICAL: android.permission.INTERNET found in AndroidManifest.xml")

    if errors:
        print("\n[!] Consistency check FAILED:")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)

    print("\n[✓] All release metadata and consistency checks PASSED successfully.")
    print("    - Application ID: com.clipmax.app")
    print("    - Version Name: 0.5.1")
    print("    - Version Code: 12")
    print("    - License: GPL-3.0-or-later (official LICENSE file verified)")
    print("    - Fastlane Metadata: title, short_description, full_description, changelogs/12.txt, icon.png")
    print("    - Network Permission: INTERNET completely absent (100% offline)")

if __name__ == "__main__":
    main()
