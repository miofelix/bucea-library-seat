import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from build_website import (DOWNLOAD_ORIGIN, DOWNLOAD_PREFIX, MAX_METADATA, RELEASE_FIELDS,
                           SOURCE, Slots, build, metadata_url, read_metadata, render_html,
                           validate_metadata)
from verify_downloads import verify


def fixture(version="1.2.3", prerelease=False, arm64=True):
    tag = "v" + version + ("-beta.1" if prerelease else "")
    value = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionCode": 19,
             "versionName": version, "minSdk": 26, "notes": "更新说明。\n<script>alert(1)</script>",
             "prerelease": prerelease, "releaseTag": tag, "releaseId": 100,
             "downloads": {}}
    variants = ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"] if arm64 else []
    for index, abi in enumerate(["universal"] + variants, 1):
        suffix = "" if abi == "universal" else "-" + abi
        name = f"BUCEA-Library-Seat-v{version}{suffix}.apk"
        value["downloads"][abi] = {"name": name, "assetId": index,
            "size": 55_500_000 if abi == "universal" else 33_800_000, "sha256": "a" * 64,
            "url": f"{DOWNLOAD_ORIGIN}/{DOWNLOAD_PREFIX}/releases/{tag}/{name}"}
    return value


def slot_content(html, marker, value=None):
    item = next(item for item in Slots(html).items if marker in item["attrs"]
                and (value is None or item["attrs"][marker] == value))
    return html[item["content_start"]:item["content_end"]]


class Response(io.BytesIO):
    def __init__(self, status, headers=None, body=b""):
        super().__init__(body)
        self.status = status
        self.headers = headers or {}


class PublicR2:
    def __init__(self, metadata):
        self.metadata = metadata
        self.requests = []
        self.mutate = lambda request, response: response
        self.latest = {key: metadata[key] for key in RELEASE_FIELDS}
        self.latest.update(packageName="cn.edu.bucea.library",
                           apk=copy.deepcopy(metadata["downloads"]["universal"]))

    def open(self, request, timeout):
        self.requests.append(request)
        if request.full_url.endswith("/updates/latest.json"):
            response = Response(200, body=json.dumps(self.latest).encode())
        else:
            apk = next(apk for apk in self.metadata["downloads"].values() if apk["url"] == request.full_url)
            if request.get_method() == "HEAD":
                response = Response(200, {"Content-Length": str(apk["size"])})
            else:
                end = min(1023, apk["size"] - 1)
                response = Response(206, {"Content-Length": str(end + 1),
                    "Content-Range": f"bytes 0-{end}/{apk['size']}"}, b"x" * (end + 1))
        return self.mutate(request, response)


class WebsiteBuildTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.source_html = (SOURCE / "index.html").read_text(encoding="utf-8")
        self.metadata = fixture()

    def assert_source_unchanged(self):
        self.assertEqual(self.source_html, (SOURCE / "index.html").read_text(encoding="utf-8"))

    def test_actual_site_build_updates_release_downloads_and_leaves_illustrations_exact(self):
        output = build(self.root / "site", self.metadata)
        html = (output / "index.html").read_text(encoding="utf-8")
        self.assertIn("v1.2.3 现已发布", html)
        self.assertEqual("v1.2.3", slot_content(html, "data-release-version"))
        self.assertEqual(2, html.count("https://github.com/miofelix/bucea-library-seat/releases/tag/v1.2.3"))
        self.assertEqual(5, len(self.metadata["downloads"]))
        for apk in self.metadata["downloads"].values():
            self.assertIn('href="' + apk["url"] + '"', html)
        self.assertNotIn("github.com/miofelix/bucea-library-seat/releases/download/", html)
        variant_links = [item for item in Slots(html).items if "data-download-variant" in item["attrs"]]
        self.assertEqual(3, len(variant_links))
        self.assertTrue(all("hidden" not in item["attrs"] for item in variant_links))
        self.assertIn("ARM64 · 约 33.8 MB", html)
        self.assertIn("约 55.5 MB · 不确定手机架构时选择", html)
        self.assertIn("Cloudflare R2", html)
        self.assertIn("GitHub 备用下载", html)
        self.assertNotIn("<script>alert(1)</script>", html)
        self.assertEqual(self.metadata, json.loads((output / "release.json").read_text(encoding="utf-8")))
        for marker in ('<div class="hero-visual">', 'class="section container screenshots-section"'):
            start = self.source_html.index(marker)
            original = self.source_html[start:self.source_html.index("</section>", start)]
            self.assertIn(original, html)
        for name in ("script.js", "style.css", "preview.css", "assets/icon.svg"):
            self.assertEqual((SOURCE / name).read_bytes(), (output / name).read_bytes())
        self.assert_source_unchanged()

    def test_historical_universal_only_release_has_honest_recommended_labels(self):
        html = render_html(self.source_html, fixture("0.3.7", arm64=False))
        url = fixture("0.3.7", arm64=False)["downloads"]["universal"]["url"]
        self.assertEqual(2, html.count('href="' + url + '"'))
        self.assertEqual("下载通用版 <em>推荐</em>", slot_content(html, "data-download-title"))
        self.assertIn("通用 APK · 约 55.5 MB · 兼容多种手机架构", html)
        self.assertNotIn("ARM64 · 约", html)
        self.assertNotIn("github.com/miofelix/bucea-library-seat/releases/download/", html)
        for item in Slots(html).items:
            if "data-download-variant" in item["attrs"]:
                self.assertIn("hidden", item["attrs"])
                self.assertNotIn("href", item["attrs"])
            elif "data-download-architectures" in item["attrs"]:
                self.assertIn("hidden", item["attrs"])

    def test_only_missing_architecture_links_are_hidden_for_partial_metadata(self):
        metadata = fixture()
        del metadata["downloads"]["armeabi-v7a"]
        del metadata["downloads"]["x86_64"]
        html = render_html(self.source_html, metadata)
        items = Slots(html).items
        wrapper = next(item for item in items if "data-download-architectures" in item["attrs"])
        self.assertNotIn("hidden", wrapper["attrs"])
        for item in items:
            if "data-download-variant" not in item["attrs"]:
                continue
            abi = item["attrs"]["data-download-variant"]
            if abi == "x86":
                self.assertNotIn("hidden", item["attrs"])
                self.assertEqual(metadata["downloads"][abi]["url"], item["attrs"]["href"])
            else:
                self.assertIn("hidden", item["attrs"])
                self.assertNotIn("href", item["attrs"])

    def test_prerelease_and_minimum_android_version_are_updated_together(self):
        metadata = fixture(prerelease=True)
        metadata["minSdk"] = 31
        html = render_html(self.source_html, metadata)
        self.assertIn("v1.2.3 测试版现已发布", html)
        self.assertIn("v1.2.3 · 测试版", html)
        self.assertEqual(2, html.count("releases/tag/v1.2.3-beta.1"))
        self.assertEqual("Android 12+", slot_content(html, "data-min-sdk", "short"))
        self.assertEqual("免费使用 · Android 12 及以上", slot_content(html, "data-min-sdk", "long"))
        self.assertEqual("Android 12", slot_content(html, "data-min-sdk", "inline"))

    def test_cli_without_metadata_preserves_checked_in_github_fallback(self):
        output = self.root / "fallback"
        subprocess.run([sys.executable, str(Path(__file__).with_name("build_website.py")),
                        "--output", str(output)], check=True, capture_output=True)
        self.assertEqual((SOURCE / "index.html").read_bytes(), (output / "index.html").read_bytes())
        self.assert_source_unchanged()

    def test_invalid_metadata_cannot_replace_an_existing_build(self):
        output = self.root / "site"
        output.mkdir()
        (output / "sentinel").write_text("last good deployment")
        bad = copy.deepcopy(self.metadata)
        bad["downloads"]["universal"]["url"] = "https://evil.example/app.apk"
        with self.assertRaises(ValueError):
            build(output, bad)
        self.assertEqual("last good deployment", (output / "sentinel").read_text())
        self.assert_source_unchanged()

    def test_source_tree_and_ancestors_cannot_be_used_as_output(self):
        for output in (SOURCE, SOURCE / "dist", SOURCE.parent):
            with self.subTest(output=output), self.assertRaises(ValueError):
                build(output, self.metadata)
        self.assert_source_unchanged()

    def test_missing_or_duplicate_markers_fail_instead_of_publishing_stale_versions(self):
        for html in (self.source_html.replace("data-release-version", "data-old-version"),
                     self.source_html + "<span data-release-version>old</span>"):
            with self.assertRaises(ValueError):
                render_html(html, self.metadata)

    def test_download_urls_reject_other_hosts_redirect_targets_queries_or_path_mismatches(self):
        good = self.metadata["downloads"]["universal"]["url"]
        for url in (good.replace("https:", "http:"), good.replace("download.miofelix.com", "evil.example"),
                    good.replace("download.miofelix.com", "download.miofelix.com:443"),
                    good.replace("download.miofelix.com", "download.miofelix.com.evil.example"),
                    good + "?token=123", good + "#fragment", good.replace("v1.2.3/", "v1.2.2/"),
                    good.replace("/bucea-library-seat/", "/other/"), good.replace(".apk", "-other.apk"),
                    "https://bucea-library-seat.miofelix.com/updates/apks/1.apk"):
            metadata = copy.deepcopy(self.metadata)
            metadata["downloads"]["universal"]["url"] = url
            with self.subTest(url=url), self.assertRaises(ValueError):
                validate_metadata(metadata)

    def test_metadata_types_sizes_hashes_and_release_identity_are_checked(self):
        for key, value in (("schemaVersion", True), ("versionCode", "19"), ("releaseId", 0),
                           ("prerelease", 1), ("versionName", '<img src=x onerror="x">'),
                           ("releaseTag", "v1.2.4"), ("notes", ""), ("minSdk", 0)):
            metadata = copy.deepcopy(self.metadata)
            metadata[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_metadata(metadata)
        for key, value in (("size", True), ("size", 0), ("size", 512 * 1024 * 1024 + 1),
                           ("assetId", "1"), ("sha256", "A" * 64), ("sha256", "a" * 63),
                           ("name", "../app.apk"), ("name", 'bad\" onclick=\"x.apk'),
                           ("name", "北建大图书馆选座-v1.2.3.apk")):
            metadata = copy.deepcopy(self.metadata)
            metadata["downloads"]["universal"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_metadata(metadata)

    def test_metadata_reads_are_bounded_and_untrusted_remote_urls_fail_before_network(self):
        path = self.root / "release.json"
        path.write_text(json.dumps(self.metadata))
        self.assertEqual(self.metadata, read_metadata(path))
        path.write_bytes(b" " * (MAX_METADATA + 1))
        with self.assertRaises(ValueError):
            read_metadata(path)
        good = f"{DOWNLOAD_ORIGIN}/{DOWNLOAD_PREFIX}/releases/latest.json"
        self.assertEqual(good, metadata_url(good))
        with patch("build_website.build_opener", side_effect=AssertionError("unexpected network")):
            for url in ("https://evil.example/latest.json", good + "?x=1",
                        good.replace("/latest.json", "/../latest.json"),
                        good.replace("https:", "http:")):
                with self.subTest(url=url), self.assertRaises(ValueError):
                    read_metadata(url=url)


class PublicDownloadVerificationTest(unittest.TestCase):
    def setUp(self):
        self.metadata = fixture()
        self.r2 = PublicR2(self.metadata)

    def test_all_head_lengths_ranges_and_new_app_manifest_are_verified(self):
        self.assertEqual(5, verify(self.metadata, self.r2))
        self.assertEqual(11, len(self.r2.requests))
        self.assertEqual(["HEAD", "GET"] * 5 + ["GET"],
                         [request.get_method() for request in self.r2.requests])
        self.assertEqual("bytes=0-1023", self.r2.requests[1].get_header("Range"))
        self.assertEqual(f"{DOWNLOAD_ORIGIN}/{DOWNLOAD_PREFIX}/updates/latest.json",
                         self.r2.requests[-1].full_url)

    def test_redirect_wrong_length_compression_and_range_failures_block_deployment(self):
        def mutated(field, value):
            def change(request, response):
                if field == "status":
                    response.status = value
                else:
                    response.headers[field] = value
                return response
            return change
        for field, value in (("status", 302), ("Content-Length", "999"),
                             ("Content-Encoding", "gzip"), ("Content-Range", "bytes 1-1024/55500000")):
            self.r2 = PublicR2(self.metadata)
            self.r2.mutate = mutated(field, value)
            with self.subTest(field=field), self.assertRaises(ValueError):
                verify(self.metadata, self.r2)

    def test_manifest_must_identify_the_same_release_and_universal_bytes(self):
        for key, value in (("versionCode", 18), ("schemaVersion", True), ("prerelease", True),
                           ("releaseTag", "v1.2.2"), ("packageName", "another.package")):
            self.r2 = PublicR2(self.metadata)
            self.r2.latest[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(self.metadata, self.r2)
        for key, value in (("url", "https://evil.example/app.apk"), ("size", 1), ("sha256", "b" * 64)):
            self.r2 = PublicR2(self.metadata)
            self.r2.latest["apk"][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify(self.metadata, self.r2)


if __name__ == "__main__":
    unittest.main()
