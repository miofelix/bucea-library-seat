import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from tools.update_mirror import (BASE_URL, GitHub, GitHubRedirects, IntegrityError,
                                 Mirror, RETENTION, UpstreamError, atomic_json,
                                 validate_manifest)
from urllib.request import Request


def digest(data):
    return hashlib.sha256(data).hexdigest()


class FakeGitHub:
    def __init__(self):
        self.releases = []
        self.assets = {}
        self.content = {}
        self.failure = False
        self.downloads = []

    def release(self, code, prerelease=False, identity=None, data=None, name=None, version=None):
        identity = identity or code
        data = data or f"APK-{identity}".encode()
        manifest = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionCode": code,
                    "versionName": version if version is not None else f"0.{code}", "minSdk": 26, "notes": "更新说明",
                    "apk": {"name": name if name is not None else f"app-{identity}.apk",
                            "size": len(data), "sha256": digest(data)}}
        assets = []
        payloads = {manifest["apk"]["name"]: data, "update.json": json.dumps(manifest, ensure_ascii=False).encode("utf-8"),
                    "SHA256SUMS": f"{digest(data)}  {manifest['apk']['name']}\n".encode("utf-8")}
        for number, (name, payload) in enumerate(payloads.items(), 1):
            asset_id = identity * 10 + number
            url = f"https://api.github.com/repos/miofelix/bucea-library-seat/releases/assets/{asset_id}"
            assets.append({"id": asset_id, "name": name, "size": len(payload), "state": "uploaded", "url": url})
            self.content[url] = payload
        release = {"id": identity, "draft": False, "prerelease": prerelease, "tag_name": f"v0.{code}"}
        self.releases.append(release)
        self.assets[identity] = assets
        return release, manifest, assets

    def listing(self, resource):
        if self.failure:
            raise UpstreamError("network failure")
        if resource == "releases":
            return self.releases
        return self.assets[int(resource.split("/")[1])]

    def asset_bytes(self, asset):
        return self.content[asset["url"]]

    def release_exists(self, identity):
        return any(r["id"] == identity and not r["draft"] for r in self.releases)

    def download(self, asset, destination, expected_size):
        self.downloads.append(asset["id"])
        destination.write_bytes(self.content[asset["url"]])


class MirrorTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.github = FakeGitHub()
        self.now = 1000000.0
        self.mirror = Mirror(self.root, self.github, clock=lambda: self.now)

    def test_highest_version_code_includes_beta_and_ignores_release_order(self):
        self.github.release(15, True)
        self.github.release(2)
        self.github.release(11)
        latest = self.mirror.sync()
        self.assertEqual(15, latest["versionCode"])
        self.assertTrue(latest["prerelease"])
        self.assertEqual(f"{BASE_URL}/updates/apks/151.apk", latest["apk"]["url"])
        self.assertEqual(0o644, (self.mirror.public / "latest.json").stat().st_mode & 0o777)
        self.assertEqual(0o644, (self.mirror.apks / "151.apk").stat().st_mode & 0o777)
        self.assertEqual(0o600, (self.mirror.state / "catalog.json").stat().st_mode & 0o777)

    def test_chinese_attachment_uses_original_asset_with_legacy_compatible_public_name(self):
        name = "北建大图书馆选座-v0.3.8.apk"
        _, source, assets = self.github.release(38, name=name, version="0.3.8")
        # A matching ASCII alias is a separate attachment and must never replace
        # the Chinese attachment selected and verified by the source metadata.
        alias = dict(assets[0], id=3899, name="BUCEA-Library-Seat-v0.3.8.apk",
                     url=assets[0]["url"] + "-alias")
        assets.append(alias)
        self.github.content[alias["url"]] = self.github.content[assets[0]["url"]]
        latest = self.mirror.sync()

        self.assertEqual("BUCEA-Library-Seat-v0.3.8.apk", latest["apk"]["name"])
        self.assertEqual(name, latest["apk"]["displayName"])
        self.assertEqual(assets[0]["id"], latest["apk"]["assetId"])
        self.assertEqual(f"{BASE_URL}/updates/apks/{assets[0]['id']}.apk", latest["apk"]["url"])
        self.assertEqual(source["apk"]["sha256"], latest["apk"]["sha256"])
        self.assertEqual(source["apk"]["size"], latest["apk"]["size"])
        self.assertEqual([assets[0]["id"]], self.github.downloads)
        self.assertEqual(self.github.content[assets[0]["url"]],
                         (self.mirror.apks / f"{assets[0]['id']}.apk").read_bytes())

        # This is the unchanged Android client's strict filename contract. Extra
        # displayName is ignored while the name/asset URL/size/hash remain usable.
        self.assertRegex(latest["apk"]["name"], r"\A[A-Za-z0-9][A-Za-z0-9._-]{0,180}\.apk\Z")
        self.assertEqual(latest["apk"]["name"], validate_manifest(latest)["apk"]["name"])
        cache = json.loads((self.mirror.state / "release-38.json").read_text(encoding="utf-8"))
        self.assertEqual(source, cache["manifest"])
        self.assertNotIn("displayName", cache["manifest"]["apk"])

        restarted = Mirror(self.root, self.github, clock=lambda: self.now)
        with patch.object(self.github, "asset_bytes", side_effect=AssertionError("unchanged source must use cache")):
            self.assertEqual(latest, restarted.sync())
        self.assertEqual([assets[0]["id"]], self.github.downloads)
        self.assertEqual(source, json.loads((self.mirror.state / "release-38.json").read_text())["manifest"])

    def test_historical_ascii_attachment_and_manifest_remain_unchanged(self):
        _, source, _ = self.github.release(8)
        latest = self.mirror.sync()
        self.assertEqual(source["apk"]["name"], latest["apk"]["name"])
        self.assertNotIn("displayName", latest["apk"])
        self.assertEqual(source, validate_manifest(latest))
        self.assertEqual(latest, self.mirror.sync())

    def test_future_official_filename_matches_its_version(self):
        self.github.release(1234, name="北建大图书馆选座-v12.34.56.apk", version="12.34.56")
        latest = self.mirror.sync()
        self.assertEqual("BUCEA-Library-Seat-v12.34.56.apk", latest["apk"]["name"])
        self.assertEqual("北建大图书馆选座-v12.34.56.apk", latest["apk"]["displayName"])

    def test_official_filename_rejects_unsafe_names_and_version_mismatches(self):
        names = ("北建大图书馆选座-v0.3.9.apk", "北建大图书馆选座-v0.03.8.apk",
                 "北建大图书馆选座-v0.3.8-beta.apk", "北建大图书馆选座-v0.3.8.apk/other.apk",
                 "../北建大图书馆选座-v0.3.8.apk", "北建大图书馆选座\\v0.3.8.apk",
                 "北建大图书馆选座-v0.3.8.apk\n", "北建大图书馆选座-v0.3.8.apk\x00",
                 "北建大图书馆选座-v0.3.8.APK", "北建大图书馆选座-v０.3.8.apk",
                 "北建大图书馆选座-v0.3.8%2f.apk", "北建大图书馆选座-v0.3.8\u200b.apk",
                 "其他图书馆-v0.3.8.apk", "../app.apk", "dir/app.apk", "dir\\app.apk")
        for code, name in enumerate(names, 100):
            with self.subTest(name=name):
                release, _, _ = self.github.release(code, name=name, version="0.3.8")
                with self.assertRaises(IntegrityError):
                    self.mirror.candidate(release)

    def test_chinese_attachment_requires_exact_original_name_in_checksums_and_assets(self):
        release, _, assets = self.github.release(38, name="北建大图书馆选座-v0.3.8.apk", version="0.3.8")
        self.github.content[assets[2]["url"]] = (
            f"{digest(self.github.content[assets[0]['url']])}  BUCEA-Library-Seat-v0.3.8.apk\n".encode("utf-8"))
        with self.assertRaises(IntegrityError):
            self.mirror.candidate(release)
        self.github.content[assets[2]["url"]] = (
            f"{digest(self.github.content[assets[0]['url']])}  北建大图书馆选座-v0.3.8.apk\n".encode("utf-8"))
        assets[0]["name"] = "BUCEA-Library-Seat-v0.3.8.apk"
        with self.assertRaises(IntegrityError):
            self.mirror.candidate(release)

    def test_stable_wins_equal_version_code_deterministically(self):
        self.github.release(9, identity=91)
        self.github.release(9, True, identity=92)
        self.assertEqual(91, self.mirror.sync()["releaseId"])

    def test_drafts_legacy_incomplete_and_uploading_attachments_are_excluded(self):
        draft, _, _ = self.github.release(50)
        draft["draft"] = True
        _, _, legacy = self.github.release(49)
        legacy.pop()
        _, _, uploading = self.github.release(48)
        uploading[0]["state"] = "new"
        self.github.release(8)
        self.assertEqual(8, self.mirror.sync()["versionCode"])

    def test_metadata_package_checksum_and_asset_size_must_agree(self):
        _, _, assets = self.github.release(50)
        self.github.content[assets[2]["url"]] = b"bad sum"
        _, _, assets = self.github.release(49)
        assets[0]["size"] += 1
        _, _, assets = self.github.release(48)
        value = json.loads(self.github.content[assets[1]["url"]])
        value["packageName"] = "another.package"
        self.github.content[assets[1]["url"]] = json.dumps(value).encode()
        self.github.release(8)
        self.assertEqual(8, self.mirror.sync()["versionCode"])

    def test_checksum_failure_falls_back_without_publishing_or_leaking_partial_apk(self):
        self.github.release(8)
        initial = self.mirror.sync()
        _, _, assets = self.github.release(9)
        self.github.content[assets[0]["url"]] = b"broken"
        self.assertEqual(initial, self.mirror.sync())
        self.assertFalse((self.mirror.apks / "91.apk").exists())
        self.assertEqual([], list(self.mirror.scratch.glob("*.part")))

    def test_network_failure_preserves_manifest_and_offline_cached_download(self):
        self.github.release(8)
        self.mirror.sync()
        original = (self.mirror.public / "latest.json").read_bytes()
        self.github.failure = True
        with self.assertRaises(UpstreamError):
            self.mirror.sync()
        self.assertEqual(original, (self.mirror.public / "latest.json").read_bytes())
        self.assertEqual(b"APK-8", (self.mirror.apks / "81.apk").read_bytes())

    def test_withdrawal_reselects_and_empty_confirmed_list_removes_manifest(self):
        older, _, _ = self.github.release(7)
        self.github.release(8)
        self.mirror.sync()
        self.github.releases = [older]
        self.assertEqual(7, self.mirror.sync()["versionCode"])
        self.github.releases = []
        self.assertIsNone(self.mirror.sync())
        self.assertFalse((self.mirror.public / "latest.json").exists())
        self.assertTrue((self.mirror.apks / "81.apk").exists())

    def test_publication_is_final_atomic_step_and_restart_reuses_verified_file(self):
        self.github.release(7)
        self.mirror.sync()
        previous = (self.mirror.public / "latest.json").read_bytes()
        self.github.release(8)
        real_write = atomic_json

        def crash(path, value, **kwargs):
            if path == self.mirror.public / "latest.json":
                self.assertEqual(previous, path.read_bytes())
                self.assertEqual(value["apk"]["sha256"], digest((self.mirror.apks / "81.apk").read_bytes()))
                raise OSError("power loss before manifest switch")
            real_write(path, value, **kwargs)

        with patch("tools.update_mirror.atomic_json", side_effect=crash):
            with self.assertRaises(OSError):
                self.mirror.sync()
        self.assertEqual(previous, (self.mirror.public / "latest.json").read_bytes())
        restarted = Mirror(self.root, self.github, clock=lambda: self.now)
        self.assertEqual(8, restarted.sync()["versionCode"])
        self.assertEqual([71, 81], self.github.downloads)
        self.assertEqual(b"APK-7", (self.mirror.apks / "71.apk").read_bytes())

    def test_asset_identity_is_never_overwritten_when_metadata_changes(self):
        release, _, assets = self.github.release(8)
        self.mirror.sync()
        original = (self.mirror.apks / "81.apk").read_bytes()
        replacement = b"NEW-8"
        value = json.loads(self.github.content[assets[1]["url"]])
        value["apk"].update(size=len(replacement), sha256=digest(replacement))
        self.github.content[assets[0]["url"]] = replacement
        self.github.content[assets[1]["url"]] = json.dumps(value).encode()
        self.github.content[assets[2]["url"]] = f"{digest(replacement)}  app-8.apk\n".encode()
        for asset in assets:
            asset["updated_at"] = "changed"
            asset["size"] = len(self.github.content[asset["url"]])
        self.assertIsNone(self.mirror.sync())
        self.assertEqual(original, (self.mirror.apks / "81.apk").read_bytes())

    def test_retention_keeps_current_previous_two_and_seven_day_grace(self):
        for code in range(1, 6):
            self.github.release(code)
            self.mirror.sync()
            self.now += 1
        self.assertEqual(5, len(list(self.mirror.apks.glob("*.apk"))))
        self.now += RETENTION + 1
        self.mirror.sync()
        self.assertEqual({"31.apk", "41.apk", "51.apk"}, {p.name for p in self.mirror.apks.glob("*.apk")})
        # A confirmed reversion remains protected even though its original cache is old.
        self.github.releases = [r for r in self.github.releases if r["id"] == 1]
        self.assertEqual(1, self.mirror.sync()["versionCode"])
        self.assertTrue((self.mirror.apks / "11.apk").exists())

    def test_unreachable_asset_listing_cannot_confirm_withdrawal(self):
        self.github.release(8)
        self.mirror.sync()
        prior = (self.mirror.public / "latest.json").read_bytes()
        with patch.object(self.github, "listing", side_effect=lambda resource:
                          self.github.releases if resource == "releases" else (_ for _ in ()).throw(UpstreamError("503"))):
            with self.assertRaises(UpstreamError):
                self.mirror.sync()
        self.assertEqual(prior, (self.mirror.public / "latest.json").read_bytes())

    def test_pagination_movement_cannot_withdraw_a_still_existing_release(self):
        self.github.release(8)
        self.mirror.sync()
        prior = (self.mirror.public / "latest.json").read_bytes()
        self.github.releases = []
        with patch.object(self.github, "release_exists", return_value=True):
            with self.assertRaises(UpstreamError):
                self.mirror.sync()
        self.assertEqual(prior, (self.mirror.public / "latest.json").read_bytes())

    def test_malformed_upstream_list_preserves_last_success(self):
        self.github.release(8)
        self.mirror.sync()
        self.github.releases = [{"draft": False, "prerelease": False}]
        with self.assertRaises(UpstreamError):
            self.mirror.sync()
        self.assertEqual(8, json.loads((self.mirror.public / "latest.json").read_text())["versionCode"])


class Response(io.BytesIO):
    def __init__(self, body, headers=None, code=200):
        super().__init__(json.dumps(body).encode())
        self.headers = headers or {}
        self.code = code


class GitHubTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.now = 1000.0
        self.github = GitHub("miofelix/bucea-library-seat", Path(temporary.name), clock=lambda: self.now)

    def test_full_pagination_and_conditional_304_reuse_next_link(self):
        prefix = self.github.prefix + "releases?per_page=100&page="
        calls = []
        responses = [Response(list(range(100)), {"ETag": '"one"', "Link": f'<{prefix}2>; rel="next"'}),
                     Response(list(range(100, 145)), {"ETag": '"two"'}),
                     Response(None, code=304), Response(None, code=304)]

        def open_request(request, timeout):
            calls.append(request)
            return responses.pop(0)

        with patch.object(self.github.opener, "open", side_effect=open_request):
            self.assertEqual(list(range(145)), self.github.listing("releases"))
            self.assertEqual(list(range(145)), self.github.listing("releases"))
        self.assertEqual('"one"', calls[2].get_header("If-none-match"))
        self.assertEqual('"two"', calls[3].get_header("If-none-match"))

    def test_page_two_failure_is_not_an_empty_release_list(self):
        prefix = self.github.prefix + "releases?per_page=100&page="
        with patch.object(self.github.opener, "open", side_effect=[
            Response([1], {"Link": f'<{prefix}2>; rel="next"'}),
            HTTPError(prefix + "2", 503, "unavailable", {}, None),
        ]):
            with self.assertRaises(UpstreamError):
                self.github.listing("releases")

    def test_rate_limit_persists_and_defers_without_sleeping(self):
        error = HTTPError(self.github.prefix, 429, "limited", {"Retry-After": "120"}, None)
        with patch.object(self.github.opener, "open", side_effect=error) as opener:
            with self.assertRaises(UpstreamError):
                self.github.listing("releases")
            with self.assertRaises(UpstreamError):
                self.github.listing("releases")
            self.assertEqual(1, opener.call_count)
        self.assertEqual(1120, json.loads((self.github.state / "rate-limit.json").read_text())["until"])

    def test_primary_limit_reset_is_honored(self):
        error = HTTPError(self.github.prefix, 403, "limited",
                          {"X-RateLimit-Remaining": "0", "X-RateLimit-Reset": "2000"}, None)
        with patch.object(self.github.opener, "open", side_effect=error):
            with self.assertRaises(UpstreamError):
                self.github.listing("releases")
        self.assertEqual(2005, json.loads((self.github.state / "rate-limit.json").read_text())["until"])

    def test_redirect_drops_token_and_rejects_untrusted_host(self):
        policy = GitHubRedirects()
        request = Request(self.github.prefix + "releases/assets/1", headers={"Authorization": "Bearer synthetic-token"})
        redirected = policy.redirect_request(request, None, 302, "", {}, "https://release-assets.githubusercontent.com/file")
        self.assertIsNone(redirected.get_header("Authorization"))
        for url in ("http://github.com/file", "https://evil.example/file", "https://github.com@evil.example/file"):
            with self.assertRaises(UpstreamError):
                policy.redirect_request(request, None, 302, "", {}, url)

    def test_cyclic_and_off_host_pagination_are_rejected(self):
        for url in (self.github.prefix + "releases?per_page=100&page=1", "https://evil.example/page=2"):
            with patch.object(self.github.opener, "open", return_value=Response([], {"Link": f'<{url}>; rel="next"'})):
                with self.assertRaises(UpstreamError):
                    self.github.listing("releases")

    def test_withdrawal_requires_not_found_or_confirmed_draft(self):
        url = self.github.prefix + "releases/8"
        with patch.object(self.github.opener, "open", side_effect=HTTPError(url, 404, "not found", {}, None)):
            self.assertFalse(self.github.release_exists(8))
        with patch.object(self.github.opener, "open", return_value=Response({"id": 8, "draft": True})):
            self.assertFalse(self.github.release_exists(8))
        with patch.object(self.github.opener, "open", side_effect=HTTPError(url, 503, "unavailable", {}, None)):
            with self.assertRaises(UpstreamError):
                self.github.release_exists(8)


if __name__ == "__main__":
    unittest.main()
