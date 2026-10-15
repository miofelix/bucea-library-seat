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
            self.assertEqual("北建大图书馆选座-v0.3.4.apk", metadata["apk"]["name"])
            self.assertEqual(8, metadata["versionCode"])
            self.assertEqual(26, metadata["minSdk"])
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(), metadata["apk"]["sha256"])
            self.assertEqual(metadata, json.loads((root / "release/update.json").read_text()))
            self.assertEqual(f"{metadata['apk']['sha256']}  {metadata['apk']['name']}\n",
                             (root / "release/SHA256SUMS").read_text())

    def test_stable_and_beta_tags_share_the_versioned_chinese_filename(self):
        for tag in ("v0.3.8", "v0.3.8-beta.1", "v0.3.8-beta.2"):
            with self.subTest(tag=tag), tempfile.TemporaryDirectory() as tmp, patch(
                    "tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                    "tools.stage_release.subprocess.check_output", return_value=BADGING.replace("0.3.4", "0.3.8")):
                root = Path(tmp)
                source = root / "input.apk"
                source.write_bytes(b"signed APK fixture")
                output = root / "release"
                output.mkdir()
                (output / "BUCEA-Library-Seat-v0.3.7-beta.1.apk").write_bytes(b"old staged APK")
                metadata = stage(source, "aapt", "apksigner", output, tag, "说明")
                self.assertEqual("北建大图书馆选座-v0.3.8.apk", metadata["apk"]["name"])
                self.assertEqual({"北建大图书馆选座-v0.3.8.apk", "SHA256SUMS", "update.json"},
                                 {item.name for item in output.iterdir()})
                self.assertEqual(source.read_bytes(), (output / metadata["apk"]["name"]).read_bytes())

    def test_non_semantic_or_path_like_version_names_are_rejected_before_staging(self):
        for version in ("0.3", "0.3.8-beta.1", "00.3.8", "0.3.8/other", "../0.3.8"):
            with self.subTest(version=version), tempfile.TemporaryDirectory() as tmp, patch(
                    "tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                    "tools.stage_release.subprocess.check_output", return_value=BADGING.replace("0.3.4", version)):
                output = Path(tmp) / "release"
                with self.assertRaises(ValueError):
                    stage(Path(tmp) / "input.apk", "aapt", "apksigner", output, "v0.3.8-beta.1", "说明")
                self.assertFalse(output.exists())

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
