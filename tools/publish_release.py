#!/usr/bin/env python3
"""Upload all three staged assets in a draft, then publish the complete release."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import re


def validate_upload(directory, tag, target):
    manifest = json.loads((directory / "update.json").read_text(encoding="utf-8"))
    apk = manifest["apk"]
    if (manifest.get("packageName") != "cn.edu.bucea.library" or manifest.get("schemaVersion") != 1
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk", apk["name"])
            or not re.fullmatch(r"[0-9a-f]{40}", target)
            or tag != "v" + manifest["versionName"]
            and not tag.startswith("v" + manifest["versionName"] + "-")):
        raise ValueError("invalid release metadata, tag or commit SHA")
    digest = hashlib.sha256()
    with (directory / apk["name"]).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    if (digest.hexdigest() != apk["sha256"] or (directory / apk["name"]).stat().st_size != apk["size"]
            or (directory / "SHA256SUMS").read_text(encoding="utf-8") != f"{apk['sha256']}  {apk['name']}\n"):
        raise ValueError("staged release assets no longer agree")
    return manifest


def gh(*args, capture=False):
    return subprocess.run(["gh", *args], check=True, text=True, capture_output=capture)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--target", required=True, help="Reviewed, pushed commit SHA")
    parser.add_argument("--notes", type=Path, required=True)
    parser.add_argument("--prerelease", action="store_true")
    args = parser.parse_args()
    manifest = validate_upload(args.directory, args.tag, args.target)
    # Refuse overwriting a released asset: the mirror's asset-ID URLs are immutable.
    existing = subprocess.run(["gh", "release", "view", args.tag, "--repo", "miofelix/bucea-library-seat"], capture_output=True)
    if existing.returncode == 0:
        raise SystemExit("Release already exists; use a new versionCode/tag instead of replacing assets.")
    title = f"北建大图书馆选座 v{manifest['versionName']}" + (" 测试版" if args.prerelease else "")
    gh("release", "create", args.tag, "--repo", "miofelix/bucea-library-seat", "--draft",
       "--target", args.target, "--title", title, "--notes-file", str(args.notes))
    gh("release", "upload", args.tag, "--repo", "miofelix/bucea-library-seat",
       str(args.directory / manifest["apk"]["name"]), str(args.directory / "SHA256SUMS"),
       str(args.directory / "update.json"))
    gh("release", "edit", args.tag, "--repo", "miofelix/bucea-library-seat", "--draft=false",
       "--prerelease=" + str(args.prerelease).lower())


if __name__ == "__main__":
    main()
