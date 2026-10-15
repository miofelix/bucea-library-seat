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
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


def inspect_apk(apk, aapt, apksigner, include_native_abis=False):
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
    metadata = {"schemaVersion": 1, "packageName": name, "versionCode": int(code),
                "versionName": version, "minSdk": int(sdk.group(1))}
    if include_native_abis:
        native_code = re.search(r"^native-code:(.*)$", badging, re.M)
        metadata["nativeAbis"] = re.findall(r"'([^']+)'", native_code.group(1)) if native_code else []
    return metadata


def stage(apk, aapt, apksigner, output, tag, notes, abi_apks=None):
    if not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag):
        raise ValueError("release tag must be a portable version tag beginning with v")
    # Accept pairs as well as a mapping so repeated command-line options cannot silently
    # overwrite a duplicate ABI. Validate every input before changing staged output.
    variant_inputs = list(abi_apks.items() if hasattr(abi_apks, "items") else abi_apks or ())
    variants = dict(variant_inputs)
    if variant_inputs and (len(variants) != len(variant_inputs) or set(variants) != set(SUPPORTED_ABIS)):
        raise ValueError("provide exactly one --abi-apk for each of: " + ", ".join(SUPPORTED_ABIS))
    metadata = inspect_apk(apk, aapt, apksigner, include_native_abis=bool(variants))
    expected = "v" + metadata["versionName"]
    if tag != expected and not tag.startswith(expected + "-"):
        raise ValueError("release tag disagrees with APK versionName")
    if len(notes) > 16000 or not notes.strip():
        raise ValueError("provide nonempty release notes (at most 16000 characters)")
    name = f"北建大图书馆选座-v{metadata['versionName']}.apk"
    sources = [(None, Path(apk), name)]
    if variants:
        if set(metadata.pop("nativeAbis")) != set(SUPPORTED_ABIS):
            raise ValueError("universal APK must contain exactly the four supported native ABIs")
        for abi in SUPPORTED_ABIS:
            source = Path(variants[abi])
            variant = inspect_apk(source, aapt, apksigner, include_native_abis=True)
            if variant.pop("nativeAbis") != [abi]:
                raise ValueError(f"{abi} APK must contain only its declared native ABI")
            if any(variant[key] != metadata[key] for key in ("packageName", "versionName", "versionCode", "minSdk")):
                raise ValueError(f"{abi} APK package/version/minSdk differs from the universal APK")
            sources.append((abi, source, f"北建大图书馆选座-v{metadata['versionName']}-{abi}.apk"))
    for _, source, _ in sources:
        if source.resolve().parent == output.resolve():
            raise ValueError("source APKs must be outside the staged output directory")
        if source.stat().st_size == 0:
            raise ValueError("source APK must not be empty")
    output.mkdir(parents=True, exist_ok=True)
    # Remove old staged output so a wildcard upload cannot mix releases.
    for old in output.iterdir():
        if old.is_file() and (old.suffix == ".apk" or old.name in ("update.json", "SHA256SUMS")):
            old.unlink()
    assets = []
    for abi, source, asset_name in sources:
        destination = output / asset_name
        shutil.copyfile(source, destination)
        digest = hashlib.sha256()
        with destination.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        asset = {"name": asset_name, "size": destination.stat().st_size, "sha256": digest.hexdigest()}
        if abi is not None:
            asset.update(abi=abi, minSdk=metadata["minSdk"])
        assets.append(asset)
    metadata.update(notes=notes.strip(), apk=assets[0])
    if variants:
        metadata["apkVariants"] = assets[1:]
    (output / "SHA256SUMS").write_text("".join(f"{asset['sha256']}  {asset['name']}\n" for asset in assets), encoding="utf-8")
    (output / "update.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option in ("apk", "aapt", "apksigner", "output", "notes"):
        parser.add_argument("--" + option, type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--abi-apk", action="append", default=[], metavar="ABI=PATH",
                        help="Architecture APK; supply all four supported ABIs together")
    args = parser.parse_args()
    variant_inputs = []
    for option in args.abi_apk:
        abi, separator, path = option.partition("=")
        if not separator or not path:
            parser.error("--abi-apk must use ABI=PATH")
        variant_inputs.append((abi, Path(path)))
    metadata = stage(args.apk, args.aapt, args.apksigner, args.output, args.tag,
                     args.notes.read_text(encoding="utf-8"), abi_apks=variant_inputs)
    print(f"Staged {metadata['apk']['name']} ({metadata['apk']['size']} bytes), versionCode={metadata['versionCode']}")
    for variant in metadata.get("apkVariants", []):
        print(f"Staged {variant['name']} ({variant['size']} bytes), ABI={variant['abi']}")


if __name__ == "__main__":
    main()
