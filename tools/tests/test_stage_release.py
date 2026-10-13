import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
from unittest.mock import patch

from tools.stage_release import EXPECTED_CERTIFICATE_SHA256, stage


BADGING = "package: name='cn.edu.bucea.library' versionCode='8' versionName='0.3.4' platformBuildVersionName='15'\nsdkVersion:'26'\n"
VERIFIED = subprocess.CompletedProcess([], 0, stdout=f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}\n")


class StageTest(unittest.TestCase):
    def test_metadata_is_read_from_signed_apk_and_agrees_with_staged_bytes(self):
        with tempfile.TemporaryDirectory() as tmp, patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                "tools.stage_release.subprocess.check_output", return_value=BADGING):
            root = Path(tmp)
            source = root / "input.apk"
            source.write_bytes(b"signed APK fixture")
            metadata = stage(source, "aapt", "apksigner", root / "release", "v0.3.4-beta.1", "说明")
            self.assertEqual(8, metadata["versionCode"])
            self.assertEqual(26, metadata["minSdk"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), metadata["apk"]["sha256"])
            self.assertEqual(metadata, json.loads((root / "release/update.json").read_text()))
            self.assertEqual(f"{metadata['apk']['sha256']}  {metadata['apk']['name']}\n",
                             (root / "release/SHA256SUMS").read_text())

    def test_debuggable_apk_and_mismatched_tag_are_rejected_before_staging(self):
        with tempfile.TemporaryDirectory() as tmp, patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                "tools.stage_release.subprocess.check_output", return_value=BADGING + "application-debuggable\n"):
            with self.assertRaises(ValueError):
                stage(Path(tmp) / "input.apk", "aapt", "apksigner", Path(tmp) / "release", "v0.3.4", "说明")
        with patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch("tools.stage_release.subprocess.check_output", return_value=BADGING):
            with self.assertRaises(ValueError):
                stage(Path("input.apk"), "aapt", "apksigner", Path("release"), "v9.0", "说明")

    def test_changed_signing_key_is_rejected_before_distribution(self):
        changed = subprocess.CompletedProcess([], 0, stdout="Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n")
        with patch("tools.stage_release.subprocess.run", return_value=changed):
            with self.assertRaises(ValueError):
                stage(Path("input.apk"), "aapt", "apksigner", Path("release"), "v0.3.4", "说明")


if __name__ == "__main__":
    unittest.main()
