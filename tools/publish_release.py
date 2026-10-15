#!/usr/bin/env python3
"""Upload every staged APK and its metadata, then publish the complete draft."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import re


SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def release_apks(manifest):
    """Return universal first, followed by all four optional ABI packages."""
    version = manifest.get("versionName")
    apk = manifest.get("apk")
    if not isinstance(apk, dict):
        raise ValueError("invalid universal APK metadata")
    apks = [apk]
    if "apkVariants" in manifest:
        variants = manifest["apkVariants"]
        if (not isinstance(variants, list) or len(variants) != len(SUPPORTED_ABIS)
                or type(manifest.get("minSdk")) is not int or manifest["minSdk"] <= 0):
            raise ValueError("provide every supported ABI variant or omit apkVariants")
        for abi, variant in zip(SUPPORTED_ABIS, variants):
            if (not isinstance(variant, dict) or variant.get("abi") != abi
                    or type(variant.get("minSdk")) is not int
                    or variant["minSdk"] != manifest["minSdk"]):
                raise ValueError("invalid ABI order or minimum SDK")
        apks.extend(variants)
    for index, item in enumerate(apks):
        suffix = "" if index == 0 else "-" + SUPPORTED_ABIS[index - 1]
        if (item.get("name") != f"BUCEA-Library-Seat-v{version}{suffix}.apk"
                or not isinstance(item.get("sha256"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", item["sha256"])
                or type(item.get("size")) is not int or item["size"] <= 0):
            raise ValueError("invalid APK filename, size or digest")
    return apks


def release_asset_names(manifest):
    return [apk["name"] for apk in release_apks(manifest)] + ["SHA256SUMS", "update.json"]


def validate_upload(directory, tag, target):
    manifest = json.loads((directory / "update.json").read_text(encoding="utf-8"))
    if not isinstance(manifest, dict):
        raise ValueError("invalid release metadata")
    version = manifest.get("versionName")
    if (manifest.get("packageName") != "cn.edu.bucea.library"
            or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1
            or not isinstance(version, str)
            or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version)
            or not re.fullmatch(r"[0-9a-f]{40}", target)
            or not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag)
            or tag != "v" + version and not tag.startswith("v" + version + "-")):
        raise ValueError("invalid release metadata, tag or commit SHA")
    apks = release_apks(manifest)
    for apk in apks:
        digest = hashlib.sha256()
        with (directory / apk["name"]).open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != apk["sha256"] or (directory / apk["name"]).stat().st_size != apk["size"]:
            raise ValueError("staged release assets no longer agree")
    expected_sums = "".join(f"{apk['sha256']}  {apk['name']}\n" for apk in apks)
    if (directory / "SHA256SUMS").read_text(encoding="utf-8") != expected_sums:
        raise ValueError("staged release assets no longer agree")
    return manifest


def gh(*args, capture=False):
    return subprocess.run(["gh", *args], check=True, text=True, capture_output=capture)


def validate_remote_assets(assets, directory, manifest):
    expected = {}
    for name in release_asset_names(manifest):
        path = directory / name
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        expected[name] = (path.stat().st_size, "sha256:" + digest.hexdigest())
    if not isinstance(assets, list) or len(assets) != len(expected):
        raise ValueError("draft must contain exactly the reviewed release assets")
    seen = set()
    for asset in assets:
        if not isinstance(asset, dict):
            raise ValueError("invalid GitHub asset metadata")
        name = asset.get("name")
        if not isinstance(name, str) or name not in expected or name in seen:
            raise ValueError("GitHub asset names differ from the reviewed release")
        size, digest = expected[name]
        if (asset.get("state") != "uploaded" or asset.get("size") != size
                or asset.get("digest") not in (None, digest)):
            raise ValueError("GitHub asset size, state or digest differs from the reviewed release")
        seen.add(name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--target", required=True, help="Reviewed, pushed commit SHA")
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--prerelease", action="store_true")
    args = parser.parse_args()
    manifest = validate_upload(args.directory, args.tag, args.target)
    # Refuse replacing an existing release: published APK URLs are immutable.
    existing = subprocess.run(["gh", "release", "view", args.tag, "--repo", "miofelix/bucea-library-seat"], capture_output=True)
    if existing.returncode == 0:
        raise SystemExit("Release already exists; use a new versionCode/tag instead of replacing assets.")
    title = f"北建大图书馆选座 v{manifest['versionName']}" + (" 测试版" if args.prerelease else "")
    gh("release", "create", args.tag, "--repo", "miofelix/bucea-library-seat", "--draft",
       "--target", args.target, "--title", title, "--notes-file", str(args.notes))
    gh("release", "upload", args.tag, "--repo", "miofelix/bucea-library-seat",
       *(str(args.directory / name) for name in release_asset_names(manifest)))
    uploaded = json.loads(gh("release", "view", args.tag, "--repo", "miofelix/bucea-library-seat",
                            "--json", "assets", capture=True).stdout)
    validate_remote_assets(uploaded.get("assets"), args.directory, manifest)
    gh("release", "edit", args.tag, "--repo", "miofelix/bucea-library-seat", "--draft=false",
       "--prerelease=" + str(args.prerelease).lower())


if __name__ == "__main__":
    main()
