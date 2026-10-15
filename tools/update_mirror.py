#!/usr/bin/env python3
"""Cache completed GitHub Releases. Python 3.9+, standard library only.

The public directory is deliberately separate from API caches, tokens and scratch files.
No build/signing tools or campus credentials are used on the mirror.
"""
from __future__ import annotations

import argparse
import contextlib
import email.utils
import fcntl
import hashlib
from http.client import HTTPException
import json
import logging
import os
from pathlib import Path
import re
import shutil
import tempfile
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

LOG = logging.getLogger("update-mirror")
PACKAGE = "cn.edu.bucea.library"
BASE_URL = "https://bucea-library-seat.miofelix.com"
MAX_APK = 512 * 1024 * 1024
MAX_METADATA = 128 * 1024
RETENTION = 7 * 24 * 3600
ASCII_APK_NAME = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk")
RELEASE_VERSION = r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)"
RELEASE_APK_NAME = re.compile(rf"北建大图书馆选座-v({RELEASE_VERSION})\.apk")
SUPPORTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")


class UpstreamError(Exception):
    """An inconclusive upstream failure must not withdraw the last good manifest."""


class IntegrityError(ValueError):
    """A conclusively invalid release/file can be excluded from selection."""


def atomic_json(path: Path, value, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".json-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), mode)
            json.dump(value, stream, ensure_ascii=False, sort_keys=True)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(temporary)


def fsync_directory(path: Path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def read_json(path: Path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return default


def sha256_file(path: Path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def positive_int(value, maximum):
    return type(value) is int and 0 < value <= maximum


def valid_apk_name(name, version_name):
    if not isinstance(name, str):
        return False
    if ASCII_APK_NAME.fullmatch(name):
        return True  # Historical releases retain their original portable filenames.
    release_name = RELEASE_APK_NAME.fullmatch(name)
    return release_name is not None and release_name.group(1) == version_name


def validate_manifest(value):
    if not isinstance(value, dict) or type(value.get("schemaVersion")) is not int or value.get("schemaVersion") != 1:
        raise IntegrityError("unsupported update schema")
    apk = value.get("apk")
    if (value.get("packageName") != PACKAGE
            or not positive_int(value.get("versionCode"), 2_100_000_000)
            or not positive_int(value.get("minSdk"), 1000)
            or not isinstance(value.get("versionName"), str)
            or not 0 < len(value["versionName"]) <= 100
            or not isinstance(value.get("notes"), str)
            or len(value["notes"]) > 16000
            or not isinstance(apk, dict)
            or not valid_apk_name(apk.get("name"), value["versionName"])
            or not positive_int(apk.get("size"), MAX_APK)
            or not isinstance(apk.get("sha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", apk["sha256"])):
        raise IntegrityError("invalid update metadata")
    # Only fields from this contract are published; never expose upstream URLs.
    return {key: value[key] for key in
            ("schemaVersion", "packageName", "versionCode", "versionName", "minSdk", "notes")} | {
                "apk": {key: apk[key] for key in ("name", "size", "sha256")}}


def validate_release_checksums(value, manifest, sums, assets):
    """Verify optional split packages while selecting only the universal APK."""
    apks = [manifest["apk"]]
    if "apkVariants" in value:
        variants = value["apkVariants"]
        if not isinstance(variants, list) or len(variants) != len(SUPPORTED_ABIS):
            raise IntegrityError("invalid ABI variant metadata")
        for abi, variant in zip(SUPPORTED_ABIS, variants):
            name = f"北建大图书馆选座-v{manifest['versionName']}-{abi}.apk"
            if (not isinstance(variant, dict) or variant.get("abi") != abi
                    or variant.get("name") != name
                    or type(variant.get("minSdk")) is not int or variant["minSdk"] != manifest["minSdk"]
                    or not positive_int(variant.get("size"), MAX_APK)
                    or not isinstance(variant.get("sha256"), str)
                    or not re.fullmatch(r"[0-9a-f]{64}", variant["sha256"])
                    or name not in assets or assets[name]["size"] != variant["size"]):
                raise IntegrityError("invalid or incomplete ABI attachment")
            apks.append(variant)
    expected = [f"{apk['sha256']}  {apk['name']}" for apk in apks]
    if sums.strip().splitlines() != expected:
        raise IntegrityError("SHA256SUMS disagrees with update.json")


class GitHubRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urlparse(newurl)
        host = parsed.hostname or ""
        if (parsed.scheme != "https" or parsed.username or parsed.password
                or parsed.port not in (None, 443)
                or not (host in ("api.github.com", "github.com")
                        or host.endswith(".githubusercontent.com"))):
            raise UpstreamError("unexpected GitHub download redirect")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if redirected is not None:
            redirected.remove_header("Authorization")
        return redirected


class GitHub:
    def __init__(self, repository: str, state: Path, token=None, clock=time.time):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("invalid repository")
        self.prefix = f"https://api.github.com/repos/{repository}/"
        self.state = state
        self.token = token
        self.clock = clock
        self.cache = read_json(state / "http-cache.json", {})
        self.opener = build_opener(GitHubRedirects())

    def _url(self, url):
        if not url.startswith(self.prefix):
            raise UpstreamError("unexpected API pagination or asset URL")
        return url

    def _open(self, url, accept="application/vnd.github+json", conditional=None):
        cooldown = read_json(self.state / "rate-limit.json", {}).get("until", 0)
        if self.clock() < cooldown:
            raise UpstreamError("GitHub rate limit cooldown is active")
        headers = {"Accept": accept, "X-GitHub-Api-Version": "2022-11-28",
                   "User-Agent": "bucea-library-update-mirror/1"}
        if self.token:
            headers["Authorization"] = f"Bearer {self.token}"
        if conditional:
            if conditional.get("etag"):
                headers["If-None-Match"] = conditional["etag"]
            elif conditional.get("modified"):
                headers["If-Modified-Since"] = conditional["modified"]
        try:
            return self.opener.open(Request(self._url(url), headers=headers), timeout=60)
        except HTTPError as error:
            if error.code == 304 and conditional:
                return error
            if error.code in (403, 429):
                until = self.clock() + 600
                retry = error.headers.get("Retry-After")
                if retry:
                    try:
                        until = self.clock() + float(retry)
                    except ValueError:
                        with contextlib.suppress(ValueError, TypeError):
                            until = email.utils.parsedate_to_datetime(retry).timestamp()
                if error.headers.get("X-RateLimit-Remaining") == "0":
                    with contextlib.suppress(ValueError):
                        until = max(until, float(error.headers.get("X-RateLimit-Reset", "0")) + 5)
                atomic_json(self.state / "rate-limit.json", {"until": until})
            # Listing 404 can mean an outage, changed visibility or credentials; it is not
            # evidence of individual release withdrawal. Asset 404 is handled separately.
            error.close()
            raise
        except (URLError, TimeoutError, OSError, HTTPException) as error:
            raise UpstreamError("GitHub request failed") from error

    def page(self, url):
        cached = self.cache.get(url)
        try:
            with self._open(url, conditional=cached) as response:
                if response.code == 304:
                    return cached["body"], cached.get("link", "")
                raw = response.read(8 * 1024 * 1024 + 1)
                if len(raw) > 8 * 1024 * 1024:
                    raise UpstreamError("oversized API response")
                value = json.loads(raw)
                if not isinstance(value, list):
                    raise UpstreamError("invalid API list response")
                entry = {"body": value, "etag": response.headers.get("ETag"),
                         "modified": response.headers.get("Last-Modified"),
                         "link": response.headers.get("Link", "")}
                self.cache[url] = entry
                atomic_json(self.state / "http-cache.json", self.cache)
                return value, entry["link"]
        except (HTTPError, ValueError, OSError, HTTPException) as error:
            raise UpstreamError("GitHub list could not be confirmed") from error

    def listing(self, resource):
        url = self.prefix + resource + "?per_page=100&page=1"
        seen = set()
        values = []
        while url:
            if url in seen:
                raise UpstreamError("cyclic GitHub pagination")
            seen.add(url)
            page, link = self.page(self._url(url))
            values.extend(page)
            next_links = re.findall(r'<([^>]+)>;\s*rel="next"', link)
            if len(next_links) > 1:
                raise UpstreamError("ambiguous GitHub pagination")
            url = next_links[0] if next_links else None
        return values

    def release_exists(self, identity):
        """Confirm an absent current release independently of moving list pagination."""
        try:
            with self._open(self.prefix + f"releases/{identity}") as response:
                raw = response.read(1024 * 1024 + 1)
                if len(raw) > 1024 * 1024:
                    raise UpstreamError("oversized release response")
                value = json.loads(raw)
                if not isinstance(value, dict) or value.get("id") != identity or type(value.get("draft")) is not bool:
                    raise UpstreamError("invalid release confirmation response")
                return not value["draft"]
        except HTTPError as error:
            if error.code == 404:
                return False
            raise UpstreamError("could not confirm release withdrawal") from error
        except (ValueError, OSError, HTTPException) as error:
            raise UpstreamError("could not confirm release withdrawal") from error

    def asset_bytes(self, asset):
        try:
            with self._open(asset["url"], "application/octet-stream") as response:
                value = response.read(MAX_METADATA + 1)
                if len(value) > MAX_METADATA or len(value) != asset["size"]:
                    raise IntegrityError("invalid metadata asset size")
                return value
        except HTTPError as error:
            if error.code == 404:
                raise IntegrityError("metadata asset was removed") from error
            raise UpstreamError("GitHub metadata download failed") from error
        except (OSError, URLError, HTTPException) as error:
            raise UpstreamError("GitHub metadata download failed") from error

    def download(self, asset, destination: Path, expected_size):
        try:
            with self._open(asset["url"], "application/octet-stream") as response, destination.open("wb") as stream:
                count = 0
                for chunk in iter(lambda: response.read(1024 * 1024), b""):
                    count += len(chunk)
                    if count > expected_size:
                        raise IntegrityError("APK exceeds declared size")
                    stream.write(chunk)
                stream.flush()
                os.fsync(stream.fileno())
                if count != expected_size:
                    raise IntegrityError("incomplete APK download")
        except HTTPError as error:
            if error.code == 404:
                raise IntegrityError("APK asset was removed") from error
            raise UpstreamError("GitHub APK download failed") from error
        except (URLError, OSError, HTTPException) as error:
            raise UpstreamError("GitHub APK download failed") from error


class Mirror:
    def __init__(self, root: Path, github, base_url=BASE_URL, clock=time.time):
        if base_url != BASE_URL:
            raise ValueError("the public update origin is fixed")
        self.root = root
        self.state = root / "state"
        self.public = root / "public" / "updates"
        self.apks = self.public / "apks"
        self.scratch = root / "scratch"
        for path in (self.state, self.apks, self.scratch):
            path.mkdir(parents=True, exist_ok=True)
        self.github = github
        self.base_url = base_url
        self.clock = clock

    def candidate(self, release):
        if release.get("draft") is not False or type(release.get("prerelease")) is not bool:
            return None
        if not positive_int(release.get("id"), 2**63 - 1) or not isinstance(release.get("tag_name"), str):
            raise IntegrityError("invalid release identity")
        assets = self.github.listing(f"releases/{release['id']}/assets")
        by_name = {}
        for asset in assets:
            if (asset.get("state") != "uploaded" or not positive_int(asset.get("id"), 2**63 - 1)
                    or not positive_int(asset.get("size"), MAX_APK)):
                continue
            name = asset.get("name")
            if not isinstance(name, str) or name in by_name:
                raise IntegrityError("invalid or duplicate asset name")
            by_name[name] = asset
        if not {"update.json", "SHA256SUMS"} <= by_name.keys():
            return None  # Legacy, incomplete, or still being uploaded.
        metadata = by_name["update.json"]
        checksums = by_name["SHA256SUMS"]
        if max(metadata["size"], checksums["size"]) > MAX_METADATA:
            raise IntegrityError("oversized release metadata")
        fingerprint = hashlib.sha256(json.dumps(
            [(a["id"], a["name"], a["size"], a.get("updated_at")) for a in assets],
            sort_keys=True).encode()).hexdigest()
        cache_path = self.state / f"release-{release['id']}.json"
        cached = read_json(cache_path, {})
        if cached.get("fingerprint") == fingerprint:
            source_manifest = validate_manifest(cached["manifest"])
        else:
            try:
                source_value = json.loads(self.github.asset_bytes(metadata))
                source_manifest = validate_manifest(source_value)
                sums = self.github.asset_bytes(checksums).decode("utf-8")
            except (ValueError, UnicodeError) as error:
                raise IntegrityError("invalid release metadata") from error
            validate_release_checksums(source_value, source_manifest, sums, by_name)
            atomic_json(cache_path, {"fingerprint": fingerprint, "manifest": source_manifest})
        asset = by_name.get(source_manifest["apk"]["name"])
        if asset is None or asset["size"] != source_manifest["apk"]["size"]:
            raise IntegrityError("missing or mismatched APK attachment")
        # Select/checksum the original GitHub attachment before deriving the public
        # filename. Existing Android clients require apk.name to remain portable ASCII.
        manifest = validate_manifest(source_manifest)
        if not ASCII_APK_NAME.fullmatch(manifest["apk"]["name"]):
            manifest["apk"]["displayName"] = manifest["apk"]["name"]
            manifest["apk"]["name"] = f"BUCEA-Library-Seat-v{manifest['versionName']}.apk"
        manifest["apk"].update(assetId=asset["id"], url=f"{self.base_url}/updates/apks/{asset['id']}.apk")
        manifest.update(releaseId=release["id"], releaseTag=release["tag_name"], prerelease=release["prerelease"])
        return manifest, asset

    def cache_apk(self, manifest, asset):
        target = self.apks / f"{asset['id']}.apk"
        expected = manifest["apk"]
        if target.exists():
            if target.stat().st_size != expected["size"] or sha256_file(target) != expected["sha256"]:
                # Asset URLs are immutable, including after a release metadata edit.
                raise IntegrityError("cached asset identity conflicts with release metadata")
            target.chmod(0o644)
            return target
        if shutil.disk_usage(self.root).free < expected["size"] + 32 * 1024 * 1024:
            raise UpstreamError("not enough disk space to cache APK")
        fd, temporary = tempfile.mkstemp(prefix=f"{asset['id']}-", suffix=".part", dir=self.scratch)
        os.close(fd)
        temporary = Path(temporary)
        try:
            self.github.download(asset, temporary, expected["size"])
            if temporary.stat().st_size != expected["size"] or sha256_file(temporary) != expected["sha256"]:
                raise IntegrityError("APK checksum/size mismatch")
            # mkstemp is 0600; the HTTP server uses a different service account.
            temporary.chmod(0o644)
            os.replace(temporary, target)
            fsync_directory(target.parent)
            return target
        finally:
            temporary.unlink(missing_ok=True)

    def cleanup(self, catalog):
        current = read_json(self.public / "latest.json", {}).get("apk", {}).get("assetId")
        ordered = sorted(catalog, key=lambda key: catalog[key]["lastSelectedAt"], reverse=True)
        protected = set(ordered[:3]) | {str(current)}
        for apk in self.apks.glob("*.apk"):
            identity = apk.stem
            entry = catalog.get(identity, {})
            last_used = max(entry.get("firstCachedAt", apk.stat().st_mtime), entry.get("lastSelectedAt", 0))
            if identity not in protected and self.clock() - last_used >= RETENTION:
                apk.unlink()
                catalog.pop(identity, None)
        atomic_json(self.state / "catalog.json", catalog)

    def sync(self):
        # One lock covers selection, publication and cleanup. A crashed run leaves only
        # private scratch files; the HTTP server keeps serving the last public state.
        with (self.state / "sync.lock").open("w") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                LOG.info("another sync is already running")
                return
            for stale in self.scratch.glob("*.part"):
                stale.unlink()
            candidates = []
            releases = self.github.listing("releases")
            seen = set()
            for release in releases:
                if not isinstance(release, dict):
                    raise UpstreamError("invalid release listing")
                identity = release.get("id")
                if (not positive_int(identity, 2**63 - 1) or type(release.get("draft")) is not bool
                        or type(release.get("prerelease")) is not bool):
                    raise UpstreamError("malformed release list")
                if identity in seen:
                    # Concurrent upstream changes across pages require another full scan.
                    raise UpstreamError("release list changed during pagination")
                seen.add(identity)
                try:
                    candidate = self.candidate(release)
                    if candidate:
                        candidates.append(candidate)
                except IntegrityError as error:
                    LOG.warning("skipping release %s: %s", identity, error)
            previous = read_json(self.public / "latest.json", {})
            previous_release = previous.get("releaseId")
            if previous_release and previous_release not in seen and self.github.release_exists(previous_release):
                raise UpstreamError("current release still exists; pagination moved during scan")
            candidates.sort(key=lambda item: (item[0]["versionCode"], not item[0]["prerelease"], item[0]["releaseId"]), reverse=True)
            catalog = read_json(self.state / "catalog.json", {})
            for manifest, asset in candidates:
                try:
                    self.cache_apk(manifest, asset)
                except IntegrityError as error:
                    LOG.warning("skipping APK asset %s: %s", asset["id"], error)
                    continue
                identity = str(asset["id"])
                previous = read_json(self.public / "latest.json", {})
                entry = catalog.setdefault(identity, {"firstCachedAt": self.clock(), "lastSelectedAt": 0})
                if previous != manifest or entry["lastSelectedAt"] == 0:
                    entry["lastSelectedAt"] = self.clock()
                # Save retention bookkeeping first. Publication is the final commit.
                atomic_json(self.state / "catalog.json", catalog)
                atomic_json(self.public / "latest.json", manifest, mode=0o644)
                self.cleanup(catalog)
                LOG.info("published versionCode=%s asset=%s prerelease=%s", manifest["versionCode"], asset["id"], manifest["prerelease"])
                return manifest
            # Only a fully successful upstream scan reaches withdrawal. No candidates
            # means the HTTP server's missing-manifest route returns HTTP 503.
            (self.public / "latest.json").unlink(missing_ok=True)
            fsync_directory(self.public)
            self.cleanup(catalog)
            LOG.info("no complete releases: update manifest unavailable")
            return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/var/lib/bucea-updates"))
    parser.add_argument("--repository", default="miofelix/bucea-library-seat")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    os.umask(0o022)
    github = GitHub(args.repository, args.root / "state", os.environ.get("GITHUB_TOKEN"))
    try:
        Mirror(args.root, github).sync()
    except (UpstreamError, OSError, ValueError) as error:
        LOG.error("sync failed; keeping last public state: %s", error)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
