#!/usr/bin/env python3
"""Check public R2 download routes before deploying a website that links to them."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from urllib.request import Request, build_opener

from build_website import (DOWNLOAD_ORIGIN, DOWNLOAD_PREFIX, MAX_METADATA, NoRedirect,
                           PACKAGE, RELEASE_FIELDS, read_metadata, validate_metadata)


def open_response(opener, url, method="GET", headers=None):
    request = Request(url, method=method, headers={"Cache-Control": "no-cache",
                      "User-Agent": "bucea-download-verification/1", **(headers or {})})
    return opener.open(request, timeout=30)


def validate_response(response, expected_status, length=None):
    if response.status != expected_status:
        raise ValueError(f"public download returned HTTP {response.status}, expected {expected_status}")
    if response.headers.get("Content-Encoding", "identity").lower() != "identity":
        raise ValueError("APK responses must preserve the original byte representation")
    if length is not None and response.headers.get("Content-Length") != str(length):
        raise ValueError("public APK Content-Length differs from release metadata")


def verify(metadata, opener=None):
    metadata = validate_metadata(metadata)
    opener = opener or build_opener(NoRedirect())
    for apk in metadata["downloads"].values():
        with open_response(opener, apk["url"], "HEAD") as response:
            validate_response(response, 200, apk["size"])
        end = min(1023, apk["size"] - 1)
        length = end + 1
        with open_response(opener, apk["url"], headers={"Range": f"bytes=0-{end}"}) as response:
            validate_response(response, 206, length)
            if response.headers.get("Content-Range") != f"bytes 0-{end}/{apk['size']}":
                raise ValueError("public APK Content-Range does not describe the requested bytes")
            if len(response.read(length + 1)) != length:
                raise ValueError("public APK range body has an incorrect length")
    url = f"{DOWNLOAD_ORIGIN}/{DOWNLOAD_PREFIX}/updates/latest.json"
    with open_response(opener, url, headers={"Accept": "application/json"}) as response:
        if response.status != 200:
            raise ValueError("app update manifest must return HTTP 200 without redirects")
        body = response.read(MAX_METADATA + 1)
    if len(body) > MAX_METADATA:
        raise ValueError("app update manifest exceeds 128 KiB")
    latest = json.loads(body.decode("utf-8"))
    if not isinstance(latest, dict) or latest.get("packageName") != PACKAGE:
        raise ValueError("app update manifest has an unexpected package")
    for key in RELEASE_FIELDS:
        if type(latest.get(key)) is not type(metadata[key]) or latest[key] != metadata[key]:
            raise ValueError("app update manifest disagrees with website release metadata: " + key)
    if not isinstance(latest.get("apk"), dict):
        raise ValueError("app update manifest has no APK")
    for key, value in metadata["downloads"]["universal"].items():
        if type(latest["apk"].get(key)) is not type(value) or latest["apk"][key] != value:
            raise ValueError("app update manifest disagrees with the universal APK: " + key)
    return len(metadata["downloads"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", required=True, type=Path)
    args = parser.parse_args()
    try:
        count = verify(read_metadata(args.metadata))
    except (ValueError, OSError) as error:
        parser.exit(1, f"Public download verification failed: {error}\n")
    print(f"Verified {count} public APK lengths and first-byte Range responses, plus the app manifest. "
          "Full APK SHA-256 verification is performed by the publisher.")


if __name__ == "__main__":
    main()
