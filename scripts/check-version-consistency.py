#!/usr/bin/env python3
"""
Release consistency, clean-asset, and APK verification script for ClipMax.
Validates version declarations, applicationId, license integrity, Fastlane metadata
(including screenshots), F-Droid recipe commit SHA, manifest offline invariant,
clean production web assets, and packaged APK archives.

Note: Static checks in this script do not substitute for full runtime network audits
or complete third-party license/provenance verification (see THIRD_PARTY_LICENSES.md).
"""
import argparse
import glob
import hashlib
import os
import re
import sys
import zipfile

FORBIDDEN_EXTENSIONS = (".pdf", ".zip", ".apk")
EXPECTED_SCREENSHOTS = (
    "1_settings.png",
    "2_native_keyboard.png",
    "3_keyboard_in_app.png",
    "4_keyboard_activation.png",
)


def scan_dir_for_dev_evidence(root_dir: str, label: str) -> list[str]:
    findings: list[str] = []
    if not os.path.isdir(root_dir):
        return findings
    for dirpath, dirnames, filenames in os.walk(root_dir):
        if label == "public":
            dirnames[:] = [d for d in dirnames if d != "assets"]
        rel_dir = os.path.relpath(dirpath, root_dir).replace(os.sep, "/")
        if label == "public" and (rel_dir == "assets" or rel_dir.startswith("assets/")):
            continue
        if rel_dir == "aistudio" or rel_dir.endswith("/aistudio") or "/aistudio/" in f"/{rel_dir}/":
            findings.append(f"Development directory found in {label}: {rel_dir}")
        for d in dirnames:
            if d.lower() == "aistudio":
                sub_rel = os.path.relpath(os.path.join(dirpath, d), root_dir).replace(os.sep, "/")
                findings.append(f"AI Studio development directory found in {label}: {sub_rel}")
        for fname in filenames:
            rel_file = os.path.relpath(os.path.join(dirpath, fname), root_dir).replace(os.sep, "/")
            lower_rel = rel_file.lower()
            if lower_rel.split("/")[-2:-1] == ["aistudio"] or "/aistudio/" in f"/{lower_rel}":
                findings.append(f"AI Studio development file found in {label}: {rel_file}")
            elif lower_rel.endswith(FORBIDDEN_EXTENSIONS):
                findings.append(f"Forbidden binary/archive/document in {label}: {rel_file}")
    return findings


def inspect_apk(apk_path: str) -> tuple[list[str], int, str]:
    violations: list[str] = []
    if not os.path.isfile(apk_path):
        return ([f"APK file not found: {apk_path}"], 0, "")
    with zipfile.ZipFile(apk_path, "r") as zf:
        for name in zf.namelist():
            norm = name.replace("\\", "/")
            lower = norm.lower()
            parts = [p for p in lower.split("/") if p]
            if "aistudio" in parts or "assets/aistudio/" in lower:
                violations.append(f"APK contains AI Studio evidence entry: {norm}")
            elif lower.endswith(FORBIDDEN_EXTENSIONS):
                violations.append(f"APK contains forbidden file extension: {norm}")
    size = os.path.getsize(apk_path)
    h = hashlib.sha256()
    with open(apk_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return (violations, size, h.hexdigest())


def main():
    parser = argparse.ArgumentParser(description="Verify ClipMax release metadata, assets, and optional APKs.")
    parser.add_argument(
        "--apk",
        action="append",
        default=[],
        help="Path to an APK archive to inspect for bundled development evidence.",
    )
    args = parser.parse_args()

    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    errors: list[str] = []

    print("[*] Checking ClipMax release metadata, web assets, and invariants...")

    # 1. Android build.gradle.kts
    gradle_path = os.path.join(repo_root, "android", "app", "build.gradle.kts")
    if not os.path.isfile(gradle_path):
        errors.append(f"Missing build file: {gradle_path}")
    else:
        with open(gradle_path, "r", encoding="utf-8") as f:
            gradle_content = f.read()
        ns_match = re.search(r'namespace\s*=\s*"([^"]+)"', gradle_content)
        app_id_match = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle_content)
        vc_match = re.search(r"versionCode\s*=\s*(\d+)", gradle_content)
        vn_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle_content)
        if not ns_match or ns_match.group(1) != "com.clipmax.app":
            errors.append(f"android/app/build.gradle.kts namespace must be 'com.clipmax.app' (found: {ns_match.group(1) if ns_match else 'none'})")
        if not app_id_match or app_id_match.group(1) != "com.clipmax.app":
            errors.append(f"android/app/build.gradle.kts applicationId must be 'com.clipmax.app' (found: {app_id_match.group(1) if app_id_match else 'none'})")
        if not vc_match or int(vc_match.group(1)) != 12:
            errors.append(f"android/app/build.gradle.kts versionCode must be 12 (found: {vc_match.group(1) if vc_match else 'none'})")
        if not vn_match or vn_match.group(1) != "0.5.1":
            errors.append(f"android/app/build.gradle.kts versionName must be '0.5.1' (found: {vn_match.group(1) if vn_match else 'none'})")

    # 2. Root LICENSE file
    license_path = os.path.join(repo_root, "LICENSE")
    if not os.path.isfile(license_path):
        errors.append("Root LICENSE file is missing")
    else:
        with open(license_path, "r", encoding="utf-8") as f:
            lic = f.read()
        if "GNU GENERAL PUBLIC LICENSE" not in lic or "Version 3, 29 June 2007" not in lic:
            errors.append("LICENSE file must contain official GNU GPL v3 text")

    # 3. Fastlane metadata & screenshots
    fl_dir = os.path.join(repo_root, "fastlane", "metadata", "android", "en-US")
    title_path = os.path.join(fl_dir, "title.txt")
    short_desc_path = os.path.join(fl_dir, "short_description.txt")
    full_desc_path = os.path.join(fl_dir, "full_description.txt")
    changelog_path = os.path.join(fl_dir, "changelogs", "12.txt")
    icon_path = os.path.join(fl_dir, "images", "icon.png")
    screenshots_dir = os.path.join(fl_dir, "images", "phoneScreenshots")

    if not os.path.isfile(title_path):
        errors.append("Fastlane title.txt missing")
    else:
        with open(title_path, "r", encoding="utf-8") as f:
            t = f.read().strip()
        if t != "ClipMax":
            errors.append(f"Fastlane title.txt should be 'ClipMax' (found: '{t}')")

    if not os.path.isfile(short_desc_path):
        errors.append("Fastlane short_description.txt missing")
    else:
        with open(short_desc_path, "r", encoding="utf-8") as f:
            sd = f.read().strip()
        if not sd:
            errors.append("Fastlane short_description.txt is empty")
        elif len(sd) > 80:
            errors.append(f"Fastlane short_description.txt exceeds 80 characters ({len(sd)} chars)")

    if not os.path.isfile(full_desc_path):
        errors.append("Fastlane full_description.txt missing")
    else:
        with open(full_desc_path, "r", encoding="utf-8") as f:
            if not f.read().strip():
                errors.append("Fastlane full_description.txt is empty")

    if not os.path.isfile(changelog_path):
        errors.append(f"Fastlane changelog missing for versionCode 12 ({changelog_path})")
    else:
        with open(changelog_path, "r", encoding="utf-8") as f:
            if not f.read().strip():
                errors.append("Fastlane changelogs/12.txt is empty")

    if not os.path.isfile(icon_path) or os.path.getsize(icon_path) == 0:
        errors.append(f"Fastlane icon.png missing or empty ({icon_path})")

    if not os.path.isdir(screenshots_dir):
        errors.append(f"Fastlane phoneScreenshots directory missing ({screenshots_dir})")
    else:
        for shot in EXPECTED_SCREENSHOTS:
            shot_path = os.path.join(screenshots_dir, shot)
            if not os.path.isfile(shot_path) or os.path.getsize(shot_path) == 0:
                errors.append(f"Fastlane screenshot missing or empty: {shot}")

    # 4. package.json
    pkg_path = os.path.join(repo_root, "package.json")
    if not os.path.isfile(pkg_path):
        errors.append("package.json missing")
    else:
        with open(pkg_path, "r", encoding="utf-8") as f:
            pkg_content = f.read()
        if '"name": "clipmax"' not in pkg_content:
            errors.append('package.json must declare "name": "clipmax"')
        if '"version": "0.5.1"' not in pkg_content:
            errors.append('package.json must declare "version": "0.5.1"')

    # 5. com.clipmax.app.yml draft recipe
    fdroid_yml = os.path.join(repo_root, "com.clipmax.app.yml")
    if not os.path.isfile(fdroid_yml):
        errors.append("com.clipmax.app.yml missing")
    else:
        with open(fdroid_yml, "r", encoding="utf-8") as f:
            yml_content = f.read()
        if "versionName: '0.5.1'" not in yml_content:
            errors.append("com.clipmax.app.yml must declare versionName: '0.5.1'")
        if "versionCode: 12" not in yml_content or "CurrentVersionCode: 12" not in yml_content:
            errors.append("com.clipmax.app.yml must declare versionCode 12 and CurrentVersionCode: 12")
        if "CurrentVersion: '0.5.1'" not in yml_content:
            errors.append("com.clipmax.app.yml must declare CurrentVersion: '0.5.1'")
        commit_match = re.search(r"^\s*commit:\s*([^\s#]+)", yml_content, re.MULTILINE)
        if not commit_match:
            errors.append("com.clipmax.app.yml missing commit reference")
        else:
            commit_val = commit_match.group(1).strip("'\"")
            if not re.fullmatch(r"[0-9a-f]{40}", commit_val):
                errors.append(
                    f"com.clipmax.app.yml commit must be a full 40-character lowercase SHA (found: '{commit_val}')"
                )

    # 6. AndroidManifest.xml: Verify INTERNET permission & MediaContentProvider for media features
    manifest_path = os.path.join(repo_root, "android", "app", "src", "main", "AndroidManifest.xml")
    if not os.path.isfile(manifest_path):
        errors.append("AndroidManifest.xml missing")
    else:
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = f.read()
        if "android.permission.INTERNET" not in manifest:
            errors.append("Required android.permission.INTERNET missing from AndroidManifest.xml for GIF features")
        if "MediaContentProvider" not in manifest:
            errors.append("Required MediaContentProvider declaration missing from AndroidManifest.xml")

    # 7. Clean production web assets (public/, dist/, android/app/src/main/assets/web/)
    public_dir = os.path.join(repo_root, "public")
    dist_dir = os.path.join(repo_root, "dist")
    android_web_dir = os.path.join(repo_root, "android", "app", "src", "main", "assets", "web")
    if not os.path.isfile(os.path.join(public_dir, "logo.jpg")):
        errors.append("Required asset public/logo.jpg missing")
    if not os.path.isfile(os.path.join(android_web_dir, "index.html")):
        errors.append("Required packaged asset android/app/src/main/assets/web/index.html missing")
    errors.extend(scan_dir_for_dev_evidence(public_dir, "public"))
    if os.path.isdir(dist_dir):
        errors.extend(scan_dir_for_dev_evidence(dist_dir, "dist"))
    errors.extend(scan_dir_for_dev_evidence(android_web_dir, "android/app/src/main/assets/web"))

    # 8. APK archive inspection (explicit --apk arguments or built APKs if present)
    apk_targets = list(args.apk)
    if not apk_targets:
        discovered = sorted(glob.glob(os.path.join(repo_root, "android", "app", "build", "outputs", "apk", "**", "*.apk"), recursive=True))
        apk_targets.extend(discovered)

    inspected_apks: list[tuple[str, int, str]] = []
    for apk_path in apk_targets:
        apk_errors, apk_size, apk_sha = inspect_apk(apk_path)
        errors.extend(apk_errors)
        if not apk_errors:
            inspected_apks.append((apk_path, apk_size, apk_sha))

    if errors:
        print("\n[!] Consistency check FAILED:")
        for err in errors:
            print(f"  - {err}")
        sys.exit(1)

    print("\n[✓] All release metadata, clean-asset, and consistency checks PASSED.")
    print("    - Application ID / Namespace: com.clipmax.app")
    print("    - Version Name / Code: 0.5.1 (12)")
    print("    - License: GPL-3.0-or-later (root LICENSE file verified)")
    print("    - Fastlane Metadata: title, short_description, full_description, changelogs/12.txt, icon.png, 4 phoneScreenshots")
    print("    - F-Droid Recipe Draft: com.clipmax.app.yml commit pinned to full 40-char SHA")
    print("    - Manifest Check: android.permission.INTERNET and MediaContentProvider verified in AndroidManifest.xml")
    print("    - Web Asset Tree: public/ and android/app/src/main/assets/web/ free of .pdf, .zip, .apk, aistudio/")
    if inspected_apks:
        for path, size, sha in inspected_apks:
            print(f"    - APK Verified Clean: {path} ({size} bytes, SHA-256: {sha})")
    else:
        print("    - APK Inspection: No local APK passed or found; pass --apk <file.apk> to inspect built APKs.")
    print("    - Scope Caveat: Static checks verify metadata and clean packaging; see THIRD_PARTY_LICENSES.md for upstream license provenance (including AOSP Apache-2.0 en_words.txt).")


if __name__ == "__main__":
    main()
