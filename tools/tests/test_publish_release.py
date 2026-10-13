import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from tools.publish_release import validate_upload


class PublishTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        content = b"verified signed APK fixture"
        (self.root / "app.apk").write_bytes(content)
        self.manifest = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionName": "0.3.4",
                         "apk": {"name": "app.apk", "size": len(content), "sha256": hashlib.sha256(content).hexdigest()}}
        (self.root / "update.json").write_text(json.dumps(self.manifest))
        (self.root / "SHA256SUMS").write_text(f"{self.manifest['apk']['sha256']}  app.apk\n")

    def test_reviewed_commit_and_matching_assets_are_required(self):
        self.assertEqual(self.manifest, validate_upload(self.root, "v0.3.4-beta.1", "a" * 40))
        for tag, target in (("v9.0", "a" * 40), ("v0.3.4-beta.1", "main")):
            with self.assertRaises(ValueError):
                validate_upload(self.root, tag, target)

    def test_modified_apk_or_checksum_is_rejected_before_remote_operations(self):
        (self.root / "SHA256SUMS").write_text("incorrect checksum")
        with self.assertRaises(ValueError):
            validate_upload(self.root, "v0.3.4-beta.1", "a" * 40)
        (self.root / "SHA256SUMS").write_text(f"{self.manifest['apk']['sha256']}  app.apk\n")
        (self.root / "app.apk").write_bytes(b"modified APK")
        with self.assertRaises(ValueError):
            validate_upload(self.root, "v0.3.4-beta.1", "a" * 40)
