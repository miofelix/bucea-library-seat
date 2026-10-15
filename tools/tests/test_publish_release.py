import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.publish_release import validate_upload, validate_remote_assets


class PublishTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        content = b"verified signed APK fixture"
        self.apk_name = "北建大图书馆选座-v0.3.8.apk"
        (self.root / self.apk_name).write_bytes(content)
        self.manifest = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionName": "0.3.8",
                         "apk": {"name": self.apk_name, "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}}
        self.write_manifest()
        self.write_checksum()

    def write_manifest(self):
        (self.root / "update.json").write_text(json.dumps(self.manifest, ensure_ascii=False), encoding="utf-8")

    def write_checksum(self):
        (self.root / "SHA256SUMS").write_text(
            f"{self.manifest['apk']['sha256']}  {self.manifest['apk']['name']}\n", encoding="utf-8")

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
        for name in ("app.apk", "BUCEA-Library-Seat-v0.3.8-beta.1.apk", "北建大图书馆选座-v0.3.7.apk",
                     "北建大图书馆选座-v0.3.8-beta.1.apk", "北建大图书馆选座-v0.3.8.apk/../app.apk",
                     "../北建大图书馆选座-v0.3.8.apk", "北建大图书馆选座-v０.３.８.apk"):
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
                self.manifest["apk"]["name"] = f"北建大图书馆选座-v{version}.apk"
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
        assets = []
        for name in (self.apk_name, "SHA256SUMS", "update.json"):
            data = (self.root / name).read_bytes()
            assets.append({"name": name, "size": len(data), "state": "uploaded",
                           "digest": "sha256:" + hashlib.sha256(data).hexdigest()})
        validate_remote_assets(assets, self.root, self.manifest)
        for key, value in (("name", "BUCEA-Library-Seat-v0.3.8.apk"), ("size", 1),
                           ("state", "new"), ("digest", "sha256:" + "0" * 64)):
            changed = [dict(asset) for asset in assets]
            changed[0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_remote_assets(changed, self.root, self.manifest)
        for changed in (assets[:-1], assets + [assets[0]], [assets[0], assets[0], assets[2]]):
            with self.assertRaises(ValueError):
                validate_remote_assets(changed, self.root, self.manifest)
