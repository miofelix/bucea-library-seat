#!/usr/bin/env python3
"""Publish verified GitHub Release APKs and update metadata to Cloudflare R2."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote, urlsplit


DOWNLOAD_ORIGIN = "https://download.miofelix.com"
PREFIX = "bucea-library-seat"
PACKAGE = "cn.edu.bucea.library"
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
MAX_APK = 512 * 1024 * 1024
MAX_METADATA = 128 * 1024
IMMUTABLE_CACHE = "public, max-age=31536000, immutable"
LATEST_CACHE = "no-store, max-age=0"
VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
CORE_FIELDS = ("schemaVersion", "packageName", "versionCode", "versionName", "minSdk", "notes")
RELEASE_FIELDS = ("prerelease", "releaseTag", "releaseId")


def positive_int(value, maximum=2**63 - 1):
    return type(value) is int and 0 < value <= maximum


def json_bytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode("utf-8")


def read_json(path):
    if path.stat().st_size > MAX_METADATA:
        raise ValueError("release metadata exceeds the size limit")
    return json.loads(path.read_text(encoding="utf-8"))


def file_digest(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def validate_core(value):
    if (not isinstance(value, dict) or type(value.get("schemaVersion")) is not int
            or value["schemaVersion"] != 1 or value.get("packageName") != PACKAGE
            or not positive_int(value.get("versionCode"), 2_100_000_000)
            or not positive_int(value.get("minSdk"), 1000)
            or not isinstance(value.get("versionName"), str)
            or not re.fullmatch(VERSION, value["versionName"])
            or not isinstance(value.get("notes"), str)
            or not value["notes"].strip() or len(value["notes"]) > 16000):
        raise ValueError("invalid release package, version, SDK or notes")
    return {key: value[key] for key in CORE_FIELDS}


def validate_apk(apk, expected_name=None):
    if (not isinstance(apk, dict) or not isinstance(apk.get("name"), str)
            or expected_name is not None and apk["name"] != expected_name
            or not positive_int(apk.get("size"), MAX_APK)
            or not isinstance(apk.get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", apk["sha256"])):
        raise ValueError("invalid APK filename, size or digest")
    return {key: apk[key] for key in ("name", "size", "sha256")}


def validate_release_fields(value):
    tag = value.get("releaseTag")
    if (type(value.get("prerelease")) is not bool or not positive_int(value.get("releaseId"))
            or not isinstance(tag, str) or not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag)
            or tag != "v" + value["versionName"] and not tag.startswith("v" + value["versionName"] + "-")):
        raise ValueError("invalid release identity, tag or prerelease status")
    return {key: value[key] for key in RELEASE_FIELDS}


def validate_configuration(bucket, endpoint_url, download_origin=DOWNLOAD_ORIGIN, prefix=PREFIX):
    if not isinstance(bucket, str) or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]", bucket):
        raise ValueError("provide a valid R2 bucket name")
    if not re.fullmatch(r"https://[a-f0-9]{32}\.r2\.cloudflarestorage\.com", endpoint_url):
        raise ValueError("endpoint must be the account-specific HTTPS R2 S3 endpoint")
    parsed = urlsplit(download_origin)
    if (parsed.scheme != "https" or not re.fullmatch(r"[a-z0-9][a-z0-9.-]*[a-z0-9]", parsed.netloc)
            or parsed.path or parsed.query or parsed.fragment or ".." in parsed.netloc):
        raise ValueError("download origin must be an HTTPS hostname without a path")
    if not isinstance(prefix, str) or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", prefix):
        raise ValueError("prefix must be a portable project name")


def prepare_release(directory, release, download_origin=DOWNLOAD_ORIGIN, prefix=PREFIX):
    """Validate every local byte and GitHub attachment before any R2 operation."""
    directory = Path(directory)
    value = read_json(directory / "update.json")
    core = validate_core(value)
    if (not isinstance(release, dict) or release.get("draft") is not False
            or type(release.get("prerelease")) is not bool or not positive_int(release.get("id"))):
        raise ValueError("expected a completed, published GitHub release")
    identity = {"prerelease": release["prerelease"], "releaseTag": release.get("tag_name"),
                "releaseId": release["id"], "versionName": core["versionName"]}
    release_fields = validate_release_fields(identity)
    universal = value.get("apk")
    expected_name = f"BUCEA-Library-Seat-v{core['versionName']}.apk"
    if not isinstance(universal, dict) or universal.get("name") != expected_name:
        raise ValueError("universal APK must use the official versioned filename")
    apks = [("universal", validate_apk(universal, universal["name"]))]
    if "apkVariants" in value:
        variants = value["apkVariants"]
        if not isinstance(variants, list) or len(variants) != len(SUPPORTED_ABIS):
            raise ValueError("include every supported ABI variant or omit apkVariants")
        for abi, variant in zip(SUPPORTED_ABIS, variants):
            if (not isinstance(variant, dict) or variant.get("abi") != abi
                    or type(variant.get("minSdk")) is not int or variant["minSdk"] != core["minSdk"]):
                raise ValueError("invalid ABI order or minimum SDK")
            name = universal["name"][:-4] + "-" + abi + ".apk"
            apks.append((abi, validate_apk(variant, name)))
    expected_names = {apk["name"] for _, apk in apks} | {"update.json", "SHA256SUMS"}
    assets = release.get("assets")
    if not isinstance(assets, list) or len(assets) != len(expected_names):
        raise ValueError("GitHub release must contain exactly all APKs, update.json and SHA256SUMS")
    remote = {}
    ids = set()
    for asset in assets:
        if (not isinstance(asset, dict) or asset.get("name") not in expected_names
                or asset["name"] in remote or not positive_int(asset.get("id")) or asset["id"] in ids
                or asset.get("state") != "uploaded" or not positive_int(asset.get("size"), MAX_APK)):
            raise ValueError("invalid, incomplete or duplicate GitHub release asset")
        remote[asset["name"]] = asset
        ids.add(asset["id"])
    # Refuse accidental wildcard-download mixtures and symlinks. Nothing outside
    # this directory should become a public release attachment.
    local_names = {path.name for path in directory.iterdir()}
    if local_names != expected_names:
        raise ValueError("download directory must contain exactly the declared release assets")
    for name in expected_names:
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("release attachments must be regular local files")
        asset = remote[name]
        digest = file_digest(path)
        if (type(asset.get("size")) is not int or path.stat().st_size != asset["size"]
                or asset.get("digest") not in (None, "sha256:" + digest)):
            raise ValueError("local release asset differs from GitHub size or digest")
    expected_sums = "".join(f"{apk['sha256']}  {apk['name']}\n" for _, apk in apks)
    if (directory / "SHA256SUMS").read_text(encoding="utf-8") != expected_sums:
        raise ValueError("SHA256SUMS must include every declared APK exactly once in order")
    downloads = {}
    objects = []
    for abi, apk in apks:
        path = directory / apk["name"]
        if path.stat().st_size != apk["size"] or file_digest(path) != apk["sha256"]:
            raise ValueError("APK bytes disagree with update.json")
        key = f"{prefix}/releases/{release_fields['releaseTag']}/{apk['name']}"
        public = apk | {"assetId": remote[apk["name"]]["id"], "url": download_origin + "/" + quote(key)}
        downloads[abi] = public
        objects.append({"key": key, "path": path, "size": apk["size"], "sha256": apk["sha256"],
                        "content_type": "application/vnd.android.package-archive", "filename": apk["name"]})
    public = core | release_fields | {"downloads": downloads}
    latest = core | release_fields | {"apk": dict(downloads["universal"])}
    if "apkVariants" in value:
        # Keep the updater manifest self-contained so the client can choose the
        # package matching its primary ABI without downloading the universal APK.
        latest["apkVariants"] = [
            {"abi": abi, "minSdk": core["minSdk"], **downloads[abi]}
            for abi in SUPPORTED_ABIS
        ]
    descriptor = json_bytes(public)
    objects.append({"key": f"{prefix}/releases/{release_fields['releaseTag']}/release.json", "body": descriptor,
                    "size": len(descriptor), "sha256": hashlib.sha256(descriptor).hexdigest(),
                    "content_type": "application/json; charset=utf-8"})
    return {"latest": latest, "public": public, "objects": objects}


def error_code(error):
    return str(getattr(error, "response", {}).get("Error", {}).get("Code", ""))


def absent(error):
    return error_code(error) in ("NoSuchKey", "NotFound", "404")


def precondition_failed(error):
    return error_code(error) in ("PreconditionFailed", "ConditionalRequestConflict", "412", "409")


def get_object(client, bucket, key, maximum):
    try:
        result = client.get_object(Bucket=bucket, Key=key)
    except Exception as error:
        if absent(error):
            return None, None
        raise
    stream = result["Body"]
    try:
        body = stream.read(maximum + 1)
    finally:
        stream.close()
    if len(body) > maximum:
        raise ValueError("stored release metadata exceeds the size limit")
    return body, result.get("ETag")


def verify_object(client, bucket, item):
    """Read back bytes, rather than trusting user-defined checksum metadata."""
    try:
        result = client.get_object(Bucket=bucket, Key=item["key"])
    except Exception as error:
        if absent(error):
            return False
        raise
    digest = hashlib.sha256()
    size = 0
    stream = result["Body"]
    try:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            size += len(chunk)
            if size > item["size"]:
                raise ValueError("immutable R2 object conflicts with the release: " + item["key"])
            digest.update(chunk)
    finally:
        stream.close()
    if size != item["size"] or digest.hexdigest() != item["sha256"]:
        raise ValueError("immutable R2 object conflicts with the release: " + item["key"])
    return True


def upload_immutable(client, bucket, item):
    headers = {"Bucket": bucket, "Key": item["key"], "ContentLength": item["size"],
               "ContentType": item["content_type"], "CacheControl": IMMUTABLE_CACHE,
               "Metadata": {"sha256": item["sha256"]}, "IfNoneMatch": "*"}
    if "filename" in item:
        headers["ContentDisposition"] = "attachment; filename*=UTF-8''" + quote(item["filename"])
    try:
        if "path" in item:
            with item["path"].open("rb") as stream:
                client.put_object(Body=stream, **headers)
        else:
            client.put_object(Body=item["body"], **headers)
    except Exception as error:
        if not precondition_failed(error):
            raise
    if not verify_object(client, bucket, item):
        raise ValueError("uploaded R2 object cannot be read back")


def validate_latest(value, download_origin=DOWNLOAD_ORIGIN, prefix=PREFIX):
    core = validate_core(value)
    identity = validate_release_fields(value)
    apk = validate_apk(value.get("apk"))
    raw = value["apk"]
    key = f"{prefix}/releases/{identity['releaseTag']}/{apk['name']}"
    if (not positive_int(raw.get("assetId"))
            or raw.get("url") != download_origin + "/" + quote(key)
            or apk["name"] != f"BUCEA-Library-Seat-v{core['versionName']}.apk"):
        raise ValueError("stored update manifest has an invalid APK URL or filename")
    result = core | identity | {"apk": apk | {"assetId": raw["assetId"], "url": raw["url"]}}
    if "apkVariants" in value:
        variants = value["apkVariants"]
        if not isinstance(variants, list) or len(variants) != len(SUPPORTED_ABIS):
            raise ValueError("stored update manifest has incomplete variants")
        cleaned = []
        for abi, variant in zip(SUPPORTED_ABIS, variants):
            if (not isinstance(variant, dict) or variant.get("abi") != abi
                    or type(variant.get("minSdk")) is not int
                    or variant["minSdk"] != core["minSdk"]):
                raise ValueError("stored update manifest has invalid ABI metadata")
            expected_name = f"BUCEA-Library-Seat-v{core['versionName']}-{abi}.apk"
            metadata = validate_apk(variant, expected_name)
            key = f"{prefix}/releases/{identity['releaseTag']}/{metadata['name']}"
            if (not positive_int(variant.get("assetId"))
                    or variant.get("url") != download_origin + "/" + quote(key)):
                raise ValueError("stored update manifest has an invalid ABI URL or identity")
            cleaned.append({"abi": abi, "minSdk": core["minSdk"], **metadata,
                            "assetId": variant["assetId"], "url": variant["url"]})
        result["apkVariants"] = cleaned
    return result


def rank(value):
    return value["versionCode"], not value["prerelease"], value["releaseId"]


def selected_public(client, bucket, latest, candidate, download_origin=DOWNLOAD_ORIGIN, prefix=PREFIX):
    if rank(latest) == rank(candidate["latest"]):
        comparable = validate_latest(candidate["latest"], download_origin, prefix)
        if latest != comparable:
            raise ValueError("release identity has conflicting immutable metadata")
        return candidate["public"]
    key = f"{prefix}/releases/{latest['releaseTag']}/release.json"
    body, _ = get_object(client, bucket, key, MAX_METADATA)
    if body is None:
        raise ValueError("promoted release is missing its public website descriptor")
    public = json.loads(body)
    if (not isinstance(public, dict) or validate_core(public) != {key: latest[key] for key in CORE_FIELDS}
            or validate_release_fields(public) != {key: latest[key] for key in RELEASE_FIELDS}
            or not isinstance(public.get("downloads"), dict)):
        raise ValueError("stored website descriptor disagrees with latest update")
    expected_abis = {"universal"} | (set(SUPPORTED_ABIS) if "arm64-v8a" in public["downloads"] else set())
    if set(public["downloads"]) != expected_abis:
        raise ValueError("stored website descriptor has incomplete variants")
    cleaned = validate_core(public) | validate_release_fields(public) | {"downloads": {}}
    for abi, apk in public["downloads"].items():
        metadata = validate_apk(apk)
        suffix = "" if abi == "universal" else "-" + abi
        expected_name = f"BUCEA-Library-Seat-v{public['versionName']}{suffix}.apk"
        key = f"{prefix}/releases/{public['releaseTag']}/{metadata['name']}"
        if (metadata["name"] != expected_name or not positive_int(apk.get("assetId"))
                or apk.get("url") != download_origin + "/" + quote(key)):
            raise ValueError("invalid stored website download URL or identity")
        cleaned["downloads"][abi] = metadata | {"assetId": apk["assetId"], "url": apk["url"]}
    universal = cleaned["downloads"]["universal"]
    if any(universal[key] != latest["apk"][key] for key in ("size", "sha256", "assetId")):
        raise ValueError("stored website universal APK disagrees with latest update")
    return cleaned


def write_public(output, value):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    descriptor = output / "release.json"
    fd, temporary = tempfile.mkstemp(prefix=".release-", dir=output)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(json_bytes(value))
        os.replace(temporary, descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish(directory, release, bucket, endpoint_url, website_output, client=None, dry_run=False,
            download_origin=DOWNLOAD_ORIGIN, prefix=PREFIX):
    validate_configuration(bucket, endpoint_url, download_origin, prefix)
    candidate = prepare_release(directory, release, download_origin, prefix)
    website_pointer = f"{prefix}/releases/latest.json"
    app_pointer = f"{prefix}/updates/latest.json"
    if dry_run:
        # Deliberately offline: no credentials, boto3 or R2 writes are necessary.
        return {"dryRun": True, "releaseTag": candidate["latest"]["releaseTag"],
                "objects": [item["key"] for item in candidate["objects"]],
                "manifest": app_pointer, "websiteManifest": website_pointer,
                "websiteOutput": str(Path(website_output) / "release.json")}
    if client is None:
        raise ValueError("an authenticated R2 S3 client is required")
    # Check all preexisting immutable keys before writing anything, then upload
    # every APK and descriptor. Existing versioned APKs are never deleted.
    missing = [item for item in candidate["objects"] if not verify_object(client, bucket, item)]
    for item in missing:
        upload_immutable(client, bucket, item)
    # Different release tags may publish concurrently. Conditional writes prevent
    # another publisher or retried job from overwriting a newer manifest.
    for _ in range(8):
        body, etag = get_object(client, bucket, app_pointer, MAX_METADATA)
        current = validate_latest(json.loads(body), download_origin, prefix) if body is not None else None
        if current is not None and rank(current) >= rank(candidate["latest"]):
            public = selected_public(client, bucket, current, candidate, download_origin, prefix)
            # Both public pointers select the same promoted release, including
            # when a delayed older Release event is processed after a new one.
            website_body, website_etag = get_object(client, bucket, website_pointer, MAX_METADATA)
            existing_public = json.loads(website_body) if website_body is not None else None
            if existing_public is not None:
                validate_core(existing_public)
                validate_release_fields(existing_public)
                if rank(existing_public) > rank(public):
                    continue
                if rank(existing_public) == rank(public) and existing_public != public:
                    raise ValueError("website release identity has conflicting metadata")
            if existing_public != public:
                headers = {"Bucket": bucket, "Key": website_pointer, "Body": json_bytes(public),
                           "ContentType": "application/json; charset=utf-8", "CacheControl": LATEST_CACHE}
                if website_body is None:
                    headers["IfNoneMatch"] = "*"
                elif website_etag:
                    headers["IfMatch"] = website_etag
                else:
                    raise ValueError("R2 did not return an ETag for conditional website publication")
                try:
                    client.put_object(**headers)
                except Exception as error:
                    if precondition_failed(error):
                        continue
                    raise
            confirmed_body, _ = get_object(client, bucket, app_pointer, MAX_METADATA)
            if confirmed_body is None or validate_latest(json.loads(confirmed_body), download_origin, prefix) != current:
                continue
            write_public(website_output, public)
            return public
        headers = {"Bucket": bucket, "Key": app_pointer, "Body": json_bytes(candidate["latest"]),
                   "ContentType": "application/json; charset=utf-8", "CacheControl": LATEST_CACHE}
        if body is None:
            headers["IfNoneMatch"] = "*"
        elif etag:
            headers["IfMatch"] = etag
        else:
            raise ValueError("R2 did not return an ETag for conditional manifest publication")
        try:
            client.put_object(**headers)
        except Exception as error:
            if precondition_failed(error):
                continue
            raise
        # Confirm the selection before generating the Pages release data.
    raise RuntimeError("update manifest changed repeatedly; retry the publishing job")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--release-json", type=Path, required=True, help="GitHub REST release response")
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--endpoint-url", required=True)
    parser.add_argument("--website-output", type=Path, required=True)
    parser.add_argument("--download-origin", default=DOWNLOAD_ORIGIN)
    parser.add_argument("--prefix", default=PREFIX)
    parser.add_argument("--dry-run", action="store_true", help="Validate and print the plan without R2 access")
    args = parser.parse_args()
    release = read_json(args.release_json)
    client = None
    if not args.dry_run:
        # boto3's normal environment credential provider reads AWS_ACCESS_KEY_ID
        # and AWS_SECRET_ACCESS_KEY; neither is accepted as a command-line arg.
        import boto3
        from botocore.config import Config
        validate_configuration(args.bucket, args.endpoint_url, args.download_origin, args.prefix)
        client = boto3.client("s3", endpoint_url=args.endpoint_url, region_name="auto",
                              config=Config(signature_version="s3v4", retries={"max_attempts": 4, "mode": "standard"},
                                            request_checksum_calculation="when_required", response_checksum_validation="when_required"))
    result = publish(args.directory, release, args.bucket, args.endpoint_url, args.website_output,
                     client=client, dry_run=args.dry_run,
                     download_origin=args.download_origin, prefix=args.prefix)
    if args.dry_run:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"Published R2 downloads and website release data for {result['releaseTag']} (versionCode {result['versionCode']}).")


if __name__ == "__main__":
    main()
