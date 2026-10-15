#!/usr/bin/env python3
"""Build the static website with verified, versioned R2 download metadata."""
from __future__ import annotations

import argparse
from html import escape
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
from urllib.parse import quote, urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "website"
DOWNLOAD_ORIGIN = "https://download.miofelix.com"
DOWNLOAD_PREFIX = "bucea-library-seat"
GITHUB_RELEASES = "https://github.com/miofelix/bucea-library-seat/releases"
PACKAGE = "cn.edu.bucea.library"
MAX_METADATA = 128 * 1024
MAX_APK = 512 * 1024 * 1024
ABIS = {"universal", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
RELEASE_FIELDS = ("schemaVersion", "versionCode", "versionName", "minSdk", "notes",
                  "prerelease", "releaseTag", "releaseId")


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def positive_integer(value, maximum):
    return type(value) is int and 0 < value <= maximum


def validate_metadata(value):
    """Reject untrusted URLs before they can become production download links."""
    if not isinstance(value, dict):
        raise ValueError("release metadata must be an object")
    version = value.get("versionName")
    tag = value.get("releaseTag")
    if (type(value.get("schemaVersion")) is not int or value["schemaVersion"] != 1
            or value.get("packageName", PACKAGE) != PACKAGE
            or not positive_integer(value.get("versionCode"), 2_100_000_000)
            or not positive_integer(value.get("minSdk"), 1000)
            or not isinstance(version, str)
            or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", version)
            or not isinstance(value.get("notes"), str) or not value["notes"].strip()
            or len(value["notes"]) > 16000
            or type(value.get("prerelease")) is not bool
            or not positive_integer(value.get("releaseId"), 2**63 - 1)
            or not isinstance(tag, str) or len(tag) > 200
            or not re.fullmatch(r"v[0-9][A-Za-z0-9._-]*", tag)
            or tag != "v" + version and not tag.startswith("v" + version + "-")):
        raise ValueError("invalid release schema, version, notes, SDK or identity")
    downloads = value.get("downloads")
    if (not isinstance(downloads, dict) or "universal" not in downloads
            or not set(downloads) <= ABIS):
        raise ValueError("downloads must include a universal APK and known architectures")
    for abi, apk in downloads.items():
        if not isinstance(apk, dict):
            raise ValueError("APK metadata must be an object")
        name = apk.get("name")
        portable = (isinstance(name, str)
                    and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk", name))
        if (not portable
                or not positive_integer(apk.get("assetId"), 2**63 - 1)
                or not positive_integer(apk.get("size"), MAX_APK)
                or not isinstance(apk.get("sha256"), str)
                or not re.fullmatch(r"[0-9a-f]{64}", apk["sha256"])):
            raise ValueError("invalid APK filename, identity, size or SHA-256")
        key = f"{DOWNLOAD_PREFIX}/releases/{tag}/{name}"
        if apk.get("url") != DOWNLOAD_ORIGIN + "/" + quote(key, safe="/"):
            raise ValueError("APK URL must match its exact R2 origin, release tag and filename")
    return value


def metadata_url(url):
    parsed = urlsplit(url)
    prefix = f"/{DOWNLOAD_PREFIX}/releases/"
    if (parsed.scheme != "https" or parsed.netloc != "download.miofelix.com"
            or parsed.query or parsed.fragment or not parsed.path.startswith(prefix)
            or not re.fullmatch(r"[A-Za-z0-9._/-]+\.json", parsed.path[len(prefix):])
            or any(part in ("", ".", "..") for part in parsed.path[len(prefix):].split("/"))):
        raise ValueError("metadata URL must use the project's HTTPS R2 release prefix")
    return url


def read_metadata(path=None, url=None):
    if path is not None:
        with Path(path).open("rb") as stream:
            body = stream.read(MAX_METADATA + 1)
    elif url is not None:
        request = Request(metadata_url(url), headers={"Accept": "application/json",
                          "Cache-Control": "no-cache", "User-Agent": "bucea-website-build/1"})
        with build_opener(NoRedirect()).open(request, timeout=30) as response:
            if response.status != 200:
                raise ValueError("release metadata must return HTTP 200 without redirects")
            body = response.read(MAX_METADATA + 1)
    else:
        return None
    if len(body) > MAX_METADATA:
        raise ValueError("release metadata exceeds 128 KiB")
    return validate_metadata(json.loads(body.decode("utf-8")))


class Slots(HTMLParser):
    """Record annotated source ranges so unrelated app illustrations stay exact."""
    VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link",
            "meta", "param", "source", "track", "wbr"}

    def __init__(self, source):
        super().__init__(convert_charrefs=False)
        self.source = source
        self.offsets = [0]
        for line in source.splitlines(keepends=True):
            self.offsets.append(self.offsets[-1] + len(line))
        self.stack = []
        self.items = []
        self.feed(source)
        self.close()

    def source_offset(self):
        line, column = self.getpos()
        return self.offsets[line - 1] + column

    def handle_starttag(self, tag, attrs):
        raw = self.get_starttag_text()
        item = {"tag": tag, "attrs": dict(attrs), "start": self.source_offset(),
                "content_start": self.source_offset() + len(raw), "raw": raw}
        if any(key.startswith("data-release-") or key.startswith("data-download-")
               or key == "data-min-sdk" for key, _ in attrs):
            self.items.append(item)
        if tag not in self.VOID:
            self.stack.append(item)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.stack.pop()

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index]["tag"] == tag:
                self.stack[index]["content_end"] = self.source_offset()
                del self.stack[index:]
                return


def android_version(sdk):
    versions = {26: "8.0", 27: "8.1", 28: "9", 29: "10", 30: "11", 31: "12",
                32: "12L", 33: "13", 34: "14", 35: "15", 36: "16"}
    return "Android " + versions[sdk] if sdk in versions else f"Android API {sdk}"


def render_html(source, metadata):
    metadata = validate_metadata(metadata)
    downloads = metadata["downloads"]
    universal = downloads["universal"]
    arm64 = downloads.get("arm64-v8a")
    recommended = arm64 or universal
    version = "v" + metadata["versionName"]
    beta = " · 测试版" if metadata["prerelease"] else ""
    minimum = android_version(metadata["minSdk"])
    release_url = GITHUB_RELEASES + "/tag/" + quote(metadata["releaseTag"], safe="")
    patches = []
    counts = {}

    def content(item, text, suffix=""):
        if "content_end" not in item:
            raise ValueError("website release marker has no closing tag")
        patches.append((item["content_start"], item["content_end"], escape(text) + suffix))

    def href(item, url):
        replacement, count = re.subn(r"\bhref\s*=\s*([\"'])(.*?)\1",
                                     lambda match: 'href="' + escape(url, quote=True) + '"',
                                     item["raw"], count=1, flags=re.S)
        if count != 1:
            raise ValueError("website link marker needs an href")
        patches.append((item["start"], item["content_start"], replacement))

    def hide(item, remove_href=False):
        raw = item["raw"]
        if remove_href:
            raw = re.sub(r"\s+href\s*=\s*([\"'])(.*?)\1", "", raw, count=1, flags=re.S)
        patches.append((item["start"], item["content_start"], raw[:-1] + " hidden>"))

    for item in Slots(source).items:
        for key, value in item["attrs"].items():
            if key not in {"data-release-link", "data-release-badge", "data-release-version",
                           "data-download-url", "data-download-title", "data-download-size",
                           "data-download-hosting", "data-download-architectures",
                           "data-download-variant", "data-min-sdk"}:
                continue
            counts[key] = counts.get(key, 0) + 1
            if key == "data-release-link":
                href(item, release_url)
            elif key == "data-release-badge":
                content(item, version + (" 测试版现已发布" if metadata["prerelease"] else " 现已发布"))
            elif key == "data-release-version":
                content(item, version + beta)
            elif key == "data-download-url":
                if value not in ("recommended", "universal"):
                    raise ValueError("unknown download link marker")
                href(item, (recommended if value == "recommended" else universal)["url"])
            elif key == "data-download-title":
                if value != "recommended":
                    raise ValueError("unknown download title marker")
                content(item, "下载 Android 版" if arm64 else "下载通用版", " <em>推荐</em>")
            elif key == "data-download-size":
                if value not in ("recommended", "universal"):
                    raise ValueError("unknown download size marker")
                apk = recommended if value == "recommended" else universal
                size = f"约 {apk['size'] / 1_000_000:.1f} MB"
                detail = (("ARM64 · " + size + " · 适合多数现代手机") if arm64
                          else "通用 APK · " + size + " · 兼容多种手机架构")
                content(item, detail if value == "recommended" else size + " · 不确定手机架构时选择")
            elif key == "data-download-hosting":
                content(item, "安装包由 Cloudflare R2 提供 · GitHub 保留备用下载")
            elif key == "data-download-architectures":
                if not any(abi in downloads for abi in ("armeabi-v7a", "x86", "x86_64")):
                    hide(item)
            elif key == "data-download-variant":
                if value not in ("armeabi-v7a", "x86", "x86_64"):
                    raise ValueError("unknown architecture download marker")
                if value in downloads:
                    href(item, downloads[value]["url"])
                else:
                    hide(item, remove_href=True)
            elif key == "data-min-sdk":
                labels = {"short": minimum + "+", "long": "免费使用 · " + minimum + " 及以上",
                          "inline": minimum}
                if value not in labels:
                    raise ValueError("unknown Android version marker")
                content(item, labels[value])
    expected = {"data-release-link": 2, "data-release-badge": 1, "data-release-version": 1,
                "data-download-url": 2, "data-download-title": 1, "data-download-size": 2,
                "data-download-hosting": 1, "data-download-architectures": 1,
                "data-download-variant": 3, "data-min-sdk": 3}
    if counts != expected:
        raise ValueError("website release markers are missing or duplicated")
    patches.sort()
    for previous, following in zip(patches, patches[1:]):
        if previous[1] > following[0]:
            raise ValueError("website release markers overlap")
    for start, end, replacement in reversed(patches):
        source = source[:start] + replacement + source[end:]
    return source


def build(output, metadata=None, source=SOURCE):
    source = Path(source).resolve()
    output = Path(output).resolve()
    if source == output or source in output.parents or output in source.parents:
        raise ValueError("build output must be separate from the website source")
    html = (source / "index.html").read_text(encoding="utf-8")
    if metadata is not None:
        html = render_html(html, metadata)
    if output.exists():
        if not output.is_dir():
            raise ValueError("build output must be a directory")
        shutil.rmtree(output)
    shutil.copytree(source, output)
    (output / "index.html").write_text(html, encoding="utf-8")
    if metadata is not None:
        (output / "release.json").write_text(
            json.dumps(metadata, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--metadata", type=Path)
    group.add_argument("--metadata-url")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/website")
    args = parser.parse_args()
    try:
        metadata = read_metadata(args.metadata, args.metadata_url)
        output = build(args.output, metadata)
    except (ValueError, OSError) as error:
        parser.exit(1, f"Website build failed: {error}\n")
    release = metadata["releaseTag"] if metadata is not None else "the checked-in GitHub download fallback"
    print(f"Built {output} with {release}.")


if __name__ == "__main__":
    main()
