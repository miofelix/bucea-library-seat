import copy
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from tools.publish_release import main, validate_upload, validate_remote_assets


class PublishTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        content = b"verified signed APK fixture"
        self.apk_name = "BUCEA-Library-Seat-v0.3.8.apk"
        (self.root / self.apk_name).write_bytes(content)
        self.manifest = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionName": "0.3.8",
                         "apk": {"name": self.apk_name, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}}
        self.write_manifest()
        self.write_checksum()

    def write_manifest(self):
        (self.root / "update.json").write_text(json.dumps(self.manifest, ensure_ascii=False), encoding="utf-8")

    def write_checksum(self):
        (self.root / "SHA256SUMS").write_text(
            "".join(f"{apk['sha256']}  {apk['name']}\n"
                    for apk in [self.manifest["apk"]] + self.manifest.get("apkVariants", [])), encoding="utf-8")

    def add_variants(self):
        self.manifest["minSdk"] = 26
        self.manifest["apkVariants"] = []
        for abi in ("arm64-v8a", "armeabi-v7a", "x86", "x86_64"):
            content = ("verified " + abi + " signed APK").encode()
            name = f"BUCEA-Library-Seat-v0.3.8-{abi}.apk"
            (self.root / name).write_bytes(content)
            self.manifest["apkVariants"].append({"abi": abi, "name": name, "minSdk": 26,
                                                  "size": len(content), "sha256": hashlib.sha256(content).hexdigest()})
        self.write_manifest()
        self.write_checksum()

    def remote_assets(self):
        assets = []
        for name in [self.apk_name] + [apk["name"] for apk in self.manifest.get("apkVariants", [])] + ["SHA256SUMS", "update.json"]:
            data = (self.root / name).read_bytes()
            assets.append({"name": name, "size": len(data), "state": "uploaded",
                           "digest": "sha256:" + hashlib.sha256(data).hexdigest()})
        return assets

    def test_reviewed_commit_and_matching_assets_are_required(self):
        for tag in ("v0.3.8", "v0.3.8-beta.1", "v0.3.8-beta.2"):
            self.assertEqual(self.manifest, validate_upload(self.root, tag, "a" * 40))
        for tag, target in (("v9.0", "a" * 40), ("v0.3.8-beta.1", "main"), ("v0.3.8-../bad", "a" * 40)):
            with self.assertRaises(ValueError):
                validate_upload(self.root, tag, target)

    def test_modified_apk_or_checksum_is_rejected_before_remote_operations(self):
        (self.root / "SHA256SUMS").write_text("incorrect checksum")
        with self.assertRaises(ValueError):
            validate_upload(self.root, "v0.3.8-beta.1", "a" * 40)
        self.write_checksum()
        (self.root / self.apk_name).write_bytes(b"modified APK")
        with self.assertRaises(ValueError):
            validate_upload(self.root, "v0.3.8-beta.1", "a" * 40)

    def test_only_the_exact_official_versioned_filename_can_be_published(self):
        for name in ("app.apk", "BUCEA-Library-Seat-v0.3.8-beta.1.apk", "BUCEA-Library-Seat-v0.3.7.apk",
                     "bucea-library-seat-v0.3.8.apk", "BUCEA-Library-Seat-v0.3.8.apk/../app.apk",
                     "../BUCEA-Library-Seat-v0.3.8.apk", "BUCEA-Library-Seat-v０.３.８.apk",
                     "北建大图书馆选座-v0.3.8.apk", "-v0.3.8.apk"):
            with self.subTest(name=name):
                self.manifest["apk"]["name"] = name
                self.write_manifest()
                self.write_checksum()
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8-beta.1", "a" * 40)

    def test_invalid_version_and_hash_metadata_are_rejected_before_upload(self):
        for version in ("0.3", "0.3.8-beta.1", "00.3.8", "0.3.8/other", "../0.3.8"):
            with self.subTest(version=version):
                self.manifest["versionName"] = version
                self.manifest["apk"]["name"] = f"BUCEA-Library-Seat-v{version}.apk"
                self.write_manifest()
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8-beta.1", "a" * 40)
        self.manifest["versionName"] = "0.3.8"
        self.manifest["apk"]["name"] = self.apk_name
        for key, value in (("sha256", "a" * 63), ("sha256", "A" * 64), ("size", True), ("size", 0)):
            with self.subTest(key=key, value=value):
                previous = self.manifest["apk"][key]
                self.manifest["apk"][key] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8-beta.1", "a" * 40)
                self.manifest["apk"][key] = previous

    def test_draft_asset_readback_rejects_renamed_incomplete_or_changed_uploads(self):
        assets = self.remote_assets()
        validate_remote_assets(assets, self.root, self.manifest)
        for key, value in (("name", "北建大图书馆选座-v0.3.8.apk"), ("name", "-v0.3.8.apk"), ("size", 1),
                           ("state", "new"), ("digest", "sha256:" + "0" * 64)):
            changed = [dict(asset) for asset in assets]
            changed[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_remote_assets(changed, self.root, self.manifest)
        for changed in (assets[:-1], assets + [assets[0]], [assets[0], assets[0], assets[2]]):
            with self.assertRaises(ValueError):
                validate_remote_assets(changed, self.root, self.manifest)

    def test_universal_and_four_abi_release_has_seven_verified_assets(self):
        self.add_variants()
        self.assertEqual(self.manifest, validate_upload(self.root, "v0.3.8", "a" * 40))
        assets = self.remote_assets()
        self.assertEqual(7, len(assets))
        validate_remote_assets(assets, self.root, self.manifest)
        # GitHub may omit digests; size and uploaded state remain mandatory.
        for asset in assets:
            asset.pop("digest")
        validate_remote_assets(assets, self.root, self.manifest)

    def test_variant_list_requires_every_supported_abi_once_in_canonical_order(self):
        self.add_variants()
        variants = self.manifest["apkVariants"]
        invalid = (None, {}, [], variants[:-1], variants + [variants[0]],
                   list(reversed(variants)), [variants[0]] * 4)
        for value in invalid:
            with self.subTest(value=value):
                self.manifest["apkVariants"] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8", "a" * 40)

    def test_variant_filenames_sdk_sizes_and_hashes_are_checked_before_upload(self):
        self.add_variants()
        original = copy.deepcopy(self.manifest)
        for key, value in (("abi", "arm64"), ("name", "BUCEA-Library-Seat-v0.3.7-arm64-v8a.apk"),
                           ("name", "../BUCEA-Library-Seat-v0.3.8-arm64-v8a.apk"),
                           ("name", "BUCEA-Library-Seat-v0.3.8-ARM64.apk"),
                           ("name", "北建大图书馆选座-v0.3.8-arm64-v8a.apk"), ("minSdk", 27),
                           ("minSdk", True), ("size", True), ("size", 0), ("size", 999),
                           ("sha256", "A" * 64), ("sha256", "a" * 63), ("sha256", "0" * 64)):
            with self.subTest(key=key, value=value):
                self.manifest = copy.deepcopy(original)
                self.manifest["apkVariants"][0][key] = value
                self.write_manifest()
                self.write_checksum()
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8", "a" * 40)

    def test_modified_variant_bytes_or_missing_file_cannot_be_published(self):
        self.add_variants()
        variant = self.root / self.manifest["apkVariants"][0]["name"]
        variant.write_bytes(b"unexpected APK contents")
        with self.assertRaises(ValueError):
            validate_upload(self.root, "v0.3.8", "a" * 40)
        variant.unlink()
        with self.assertRaises(FileNotFoundError):
            validate_upload(self.root, "v0.3.8", "a" * 40)

    def test_checksum_file_requires_all_five_entries_in_canonical_order(self):
        self.add_variants()
        sums = (self.root / "SHA256SUMS").read_text().splitlines(keepends=True)
        for lines in (sums[:-1], list(reversed(sums)), sums + [sums[0]], [sums[0]] * 5):
            with self.subTest(lines=lines):
                (self.root / "SHA256SUMS").write_text("".join(lines), encoding="utf-8")
                with self.assertRaises(ValueError):
                    validate_upload(self.root, "v0.3.8", "a" * 40)

    def test_every_remote_variant_and_metadata_asset_must_match(self):
        self.add_variants()
        assets = self.remote_assets()
        for index in range(len(assets)):
            for key, value in (("name", "unexpected.apk"), ("size", 1), ("state", "new"),
                               ("digest", "sha256:" + "0" * 64)):
                changed = copy.deepcopy(assets)
                changed[index][key] = value
                with self.subTest(index=index, key=key), self.assertRaises(ValueError):
                    validate_remote_assets(changed, self.root, self.manifest)
        for changed in (assets[:3], assets[:-1], assets + [assets[0]], assets[:-1] + [assets[0]]):
            with self.assertRaises(ValueError):
                validate_remote_assets(changed, self.root, self.manifest)

    def run_publication(self, assets, existing=False):
        arguments = ["publish_release.py", "--directory", str(self.root), "--tag", "v0.3.8",
                     "--target", "a" * 40, "--notes", str(self.root / "notes.md")]
        with patch("sys.argv", arguments), patch("tools.publish_release.subprocess.run",
                                                return_value=SimpleNamespace(returncode=0 if existing else 1)), \
                patch("tools.publish_release.gh", return_value=SimpleNamespace(stdout=json.dumps({"assets": assets}))) as remote:
            try:
                main()
            finally:
                self.remote_calls = remote.call_args_list

    def test_publication_uploads_all_seven_assets_and_verifies_draft_before_publish(self):
        self.add_variants()
        self.run_publication(self.remote_assets())
        calls = self.remote_calls
        self.assertEqual(["create", "upload", "view", "edit"], [call.args[1] for call in calls])
        upload = calls[1].args
        self.assertEqual([str(self.root / asset["name"]) for asset in self.remote_assets()], list(upload[5:]))
        self.assertIn("--draft", calls[0].args)
        self.assertIn("--draft=false", calls[-1].args)
        self.assertIn("--prerelease=false", calls[-1].args)
        self.assertFalse(any("--clobber" in call.args for call in calls))

    def test_incomplete_remote_readback_leaves_release_as_draft(self):
        self.add_variants()
        with self.assertRaises(ValueError):
            self.run_publication(self.remote_assets()[:-1])
        self.assertEqual(["create", "upload", "view"], [call.args[1] for call in self.remote_calls])

    def test_existing_release_is_never_overwritten(self):
        self.add_variants()
        with self.assertRaises(SystemExit):
            self.run_publication(self.remote_assets(), existing=True)
        self.assertEqual([], self.remote_calls)
