#!/usr/bin/env python3
"""Stage a locally built, signed release APK and metadata from the APK itself."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess


EXPECTED_CERTIFICATE_SHA256 = "211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801"


def inspect_apk(apk, aapt, apksigner):
    verification = subprocess.run([str(apksigner), "verify", "--verbose", "--print-certs", str(apk)],
                                  check=True, capture_output=True, text=True)
    certificates = set(re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})$", verification.stdout, re.M))
    if certificates != {EXPECTED_CERTIFICATE_SHA256}:
        raise ValueError("APK signing certificate changed; restore the existing keystore before distributing updates")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^sdkVersion:'(\d+)'", badging, re.M)
    if not package or not sdk:
        raise ValueError("cannot read APK package/version/minSdk")
    if re.search(r"^application-debuggable", badging, re.M):
        raise ValueError("distributed APK must have debuggable=false")
    name, code, version = package.groups()
    if name != "cn.edu.bucea.library":
        raise ValueError("unexpected APK package")
    if not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version):
        raise ValueError("APK versionName must use major.minor.patch for the release filename")
    return {"schemaVersion": 1, "packageName": name, "versionCode": int(code),
            "versionName": version, "minSdk": int(sdk.group(1))}


def stage(apk, aapt, apksigner, output, tag, notes):
    if not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag):
        raise ValueError("release tag must be a portable version tag beginning with v")
    metadata = inspect_apk(apk, aapt, apksigner)
    expected = "v" + metadata["versionName"]
    if tag != expected and not tag.startswith(expected + "-"):
        raise ValueError("release tag disagrees with APK versionName")
    if len(notes) > 16000 or not notes.strip():
        raise ValueError("provide nonempty release notes (at most 16000 characters)")
    output.mkdir(parents=True, exist_ok=True)
    name = f"北建大图书馆选座-v{metadata['versionName']}.apk"
    destination = output / name
    # Remove old staged output so a wildcard upload cannot mix releases.
    for old in output.iterdir():
        if old.is_file() and (old.suffix == ".apk" or old.name in ("update.json", "SHA256SUMS")):
            old.unlink()
    shutil.copyfile(apk, destination)
    digest = hashlib.sha256()
    with destination.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    metadata.update(notes=notes.strip(), apk={"name": name, "size": destination.stat().st_size,
                                           "sha256": digest.hexdigest()})
    (output / "SHA256SUMS").write_text(f"{digest.hexdigest()}  {name}\n", encoding="utf-8")
    (output / "update.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("apk", "aapt", "apksigner", "output", "notes"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--tag", required=True)
    args = parser.parse_args()
    metadata = stage(args.apk, args.aapt, args.apksigner, args.output, args.tag,
                     args.notes.read_text(encoding="utf-8"))
    print(f"Staged {metadata['apk']['name']} ({metadata['apk']['size']} bytes), versionCode={metadata['versionCode']}")


if __name__ == "__main__":
    main()
