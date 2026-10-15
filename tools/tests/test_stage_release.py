import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import subprocess
from unittest.mock import patch

from tools.stage_release import EXPECTED_CERTIFICATE_SHA256, SUPPORTED_ABIS, stage


BADGING = "package: name='cn.edu.bucea.library' versionCode='8' versionName='0.3.4' platformBuildVersionName='15'\nsdkVersion:'26'\n"
VERIFIED = subprocess.CompletedProcess([], 0, stdout=f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}\n")


class StageTest(unittest.TestCase):
    def variant_inputs(self, root):
        universal = root / "universal.apk"
        universal.write_bytes(b"signed universal APK fixture")
        badging = BADGING.replace("0.3.4", "1.0.0")
        badges = {str(universal): badging + "native-code: " + " ".join(f"'{abi}'" for abi in SUPPORTED_ABIS) + "\n"}
        variants = {}
        for abi in SUPPORTED_ABIS:
            variants[abi] = root / f"{abi}.apk"
            variants[abi].write_bytes(f"signed {abi} APK fixture".encode())
            badges[str(variants[abi])] = badging + f"native-code: '{abi}'\n"
        return universal, variants, badges

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
            self.assertNotIn("apkVariants", metadata)
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

    def test_all_architecture_packages_are_verified_and_listed_with_exact_checksums(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            universal, variants, badges = self.variant_inputs(root)
            output = root / "release"
            output.mkdir()
            (output / "北建大图书馆选座-v0.3.8.apk").write_bytes(b"obsolete")
            with patch("tools.stage_release.subprocess.run", return_value=VERIFIED) as verification, patch(
                    "tools.stage_release.subprocess.check_output", side_effect=lambda command, **_: badges[command[-1]]):
                metadata = stage(universal, "aapt", "apksigner", output, "v1.0.0", "正式版", abi_apks=variants)
            self.assertEqual(5, verification.call_count)
            self.assertEqual(list(SUPPORTED_ABIS), [item["abi"] for item in metadata["apkVariants"]])
            self.assertNotIn("nativeAbis", metadata)
            expected_names = ["北建大图书馆选座-v1.0.0.apk"] + [
                f"北建大图书馆选座-v1.0.0-{abi}.apk" for abi in SUPPORTED_ABIS]
            assets = [metadata["apk"], *metadata["apkVariants"]]
            self.assertEqual(expected_names, [item["name"] for item in assets])
            for item, source in zip(assets, [universal, *(variants[abi] for abi in SUPPORTED_ABIS)]):
                data = (output / item["name"]).read_bytes()
                self.assertEqual(source.read_bytes(), data)
                self.assertEqual(len(data), item["size"])
                self.assertEqual(hashlib.sha256(data).hexdigest(), item["sha256"])
            self.assertTrue(all(item["minSdk"] == metadata["minSdk"] for item in metadata["apkVariants"]))
            self.assertEqual(set(expected_names + ["SHA256SUMS", "update.json"]), {item.name for item in output.iterdir()})
            self.assertEqual(metadata, json.loads((output / "update.json").read_text(encoding="utf-8")))
            self.assertEqual("".join(f"{item['sha256']}  {item['name']}\n" for item in assets),
                             (output / "SHA256SUMS").read_text(encoding="utf-8"))

    def test_partial_unknown_or_duplicate_abis_are_rejected_before_inspecting_or_staging(self):
        incomplete = [("arm64-v8a", "arm64.apk")]
        unknown = [(abi if abi != "x86" else "mips", f"{abi}.apk") for abi in SUPPORTED_ABIS]
        duplicate = [(abi, f"{abi}.apk") for abi in SUPPORTED_ABIS] + [("x86", "other.apk")]
        for inputs in (incomplete, unknown, duplicate):
            with self.subTest(inputs=inputs), tempfile.TemporaryDirectory() as tmp, patch(
                    "tools.stage_release.inspect_apk") as inspect:
                output = Path(tmp) / "release"
                with self.assertRaisesRegex(ValueError, "exactly one"):
                    stage(Path(tmp) / "input.apk", "aapt", "apksigner", output, "v1.0.0", "说明", abi_apks=inputs)
                inspect.assert_not_called()
                self.assertFalse(output.exists())

    def test_mismatched_variant_versions_or_min_sdk_preserve_previous_staged_output(self):
        for before, after in (("versionCode='8'", "versionCode='9'"), ("versionName='1.0.0'", "versionName='1.0.1'"),
                              ("sdkVersion:'26'", "sdkVersion:'28'"), ("cn.edu.bucea.library", "com.other.app")):
            with self.subTest(field=before), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                universal, variants, badges = self.variant_inputs(root)
                badges[str(variants["x86"])] = badges[str(variants["x86"])].replace(before, after)
                output = root / "release"
                output.mkdir()
                previous = output / "previous.apk"
                previous.write_bytes(b"previous valid release")
                with patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                        "tools.stage_release.subprocess.check_output", side_effect=lambda command, **_: badges[command[-1]]):
                    with self.assertRaises(ValueError):
                        stage(universal, "aapt", "apksigner", output, "v1.0.0", "说明", abi_apks=variants)
                self.assertEqual(b"previous valid release", previous.read_bytes())
                self.assertEqual([previous], list(output.iterdir()))

    def test_wrong_native_abis_and_debuggable_variants_are_rejected(self):
        mutations = (("universal", "native-code: 'arm64-v8a'\n"), ("arm64-v8a", "native-code: 'x86'\n"),
                     ("x86", "native-code: 'x86' 'x86_64'\n"), ("x86_64", ""),
                     ("armeabi-v7a", "native-code: 'armeabi-v7a'\napplication-debuggable\n"))
        for target, native_badging in mutations:
            with self.subTest(target=target, native_badging=native_badging), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                universal, variants, badges = self.variant_inputs(root)
                path = universal if target == "universal" else variants[target]
                badges[str(path)] = BADGING.replace("0.3.4", "1.0.0") + native_badging
                with patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                        "tools.stage_release.subprocess.check_output", side_effect=lambda command, **_: badges[command[-1]]):
                    with self.assertRaises(ValueError):
                        stage(universal, "aapt", "apksigner", root / "release", "v1.0.0", "说明", abi_apks=variants)
                self.assertFalse((root / "release").exists())

    def test_variant_with_a_different_signing_certificate_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            universal, variants, badges = self.variant_inputs(root)
            changed = subprocess.CompletedProcess([], 0, stdout="Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n")
            with patch("tools.stage_release.subprocess.run", side_effect=[VERIFIED, VERIFIED, changed]), patch(
                    "tools.stage_release.subprocess.check_output", side_effect=lambda command, **_: badges[command[-1]]):
                with self.assertRaisesRegex(ValueError, "signing certificate"):
                    stage(universal, "aapt", "apksigner", root / "release", "v1.0.0", "说明", abi_apks=variants)
            self.assertFalse((root / "release").exists())

    def test_sources_inside_staging_directory_are_not_deleted(self):
        with tempfile.TemporaryDirectory() as tmp, patch("tools.stage_release.subprocess.run", return_value=VERIFIED), patch(
                "tools.stage_release.subprocess.check_output", return_value=BADGING):
            output = Path(tmp)
            source = output / "input.apk"
            source.write_bytes(b"signed APK fixture")
            with self.assertRaisesRegex(ValueError, "outside"):
                stage(source, "aapt", "apksigner", output, "v0.3.4", "说明")
            self.assertEqual(b"signed APK fixture", source.read_bytes())


if __name__ == "__main__":
    unittest.main()
