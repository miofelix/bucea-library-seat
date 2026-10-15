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
    version = manifest.get("versionName")
    if (manifest.get("packageName") != "cn.edu.bucea.library" or manifest.get("schemaVersion") != 1
            or not isinstance(version, str)
            or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version)
            or apk["name"] != f"北建大图书馆选座-v{version}.apk"
            or not isinstance(apk.get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", apk["sha256"])
            or type(apk.get("size")) is not int or apk["size"] <= 0
            or not re.fullmatch(r"[0-9a-f]{40}", target)
            or not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag)
            or tag != "v" + version and not tag.startswith("v" + version + "-")):
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


def validate_remote_assets(assets, directory, manifest):
    expected = {}
    for name in (manifest["apk"]["name"], "SHA256SUMS", "update.json"):
        path = directory / name
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        expected[name] = (path.stat().st_size, "sha256:" + digest.hexdigest())
    if not isinstance(assets, list) or len(assets) != len(expected):
        raise ValueError("draft must contain exactly the three reviewed assets")
    seen = set()
    for asset in assets:
        name = asset.get("name")
        if name not in expected or name in seen:
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
    uploaded = json.loads(gh("release", "view", args.tag, "--repo", "miofelix/bucea-library-seat",
                            "--json", "assets", capture=True).stdout)
    validate_remote_assets(uploaded.get("assets"), args.directory, manifest)
    gh("release", "edit", args.tag, "--repo", "miofelix/bucea-library-seat", "--draft=false",
       "--prerelease=" + str(args.prerelease).lower())


if __name__ == "__main__":
    main()
