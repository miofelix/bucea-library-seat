import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest

from tools.publish_cloudflare import (IMMUTABLE_CACHE, LATEST_CACHE, DOWNLOAD_ORIGIN, SUPPORTED_ABIS,
                                      json_bytes, prepare_release, publish)


ENDPOINT = "https://" + "a" * 32 + ".r2.cloudflarestorage.com"


def digest(body):
    return hashlib.sha256(body).hexdigest()


class S3Error(Exception):
    def __init__(self, code):
        self.response = {"Error": {"Code": code}}


class FakeS3:
    """An in-memory S3 implementation with conditional writes and byte readback."""
    def __init__(self):
        self.objects = {}
        self.puts = []
        self.corrupt_key = None
        self.fail_key = None
        self.before_put = None

    def get_object(self, Bucket, Key):
        if Key not in self.objects:
            raise S3Error("NoSuchKey")
        body = self.objects[Key]
        return {"Body": io.BytesIO(body), "ETag": '"' + digest(body) + '"'}

    def put_object(self, **kwargs):
        key = kwargs["Key"]
        if self.before_put:
            self.before_put(key)
        if key == self.fail_key:
            raise S3Error("ServiceUnavailable")
        old = self.objects.get(key)
        if kwargs.get("IfNoneMatch") == "*" and old is not None:
            raise S3Error("PreconditionFailed")
        if "IfMatch" in kwargs and (old is None or kwargs["IfMatch"] != '"' + digest(old) + '"'):
            raise S3Error("PreconditionFailed")
        body = kwargs["Body"]
        if hasattr(body, "read"):
            body = body.read()
        self.puts.append({**kwargs, "Body": body})
        self.objects[key] = b"corrupted upload" if key == self.corrupt_key else body
        return {"ETag": '"' + digest(body) + '"'}


class CloudflarePublishTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.output = self.root / "site"
        self.s3 = FakeS3()
        self.directory, self.release, self.source = self.fixture()

    def fixture(self, version="1.0.0", code=14, identity=100, prerelease=False,
                variants=True, prefix="BUCEA-Library-Seat", tag=None):
        tag = tag or "v" + version + ("-beta.1" if prerelease else "")
        directory = self.root / (str(identity) + "-assets")
        directory.mkdir()
        value = {"schemaVersion": 1, "packageName": "cn.edu.bucea.library", "versionName": version,
                 "versionCode": code, "minSdk": 26, "notes": "Release notes.\n中文说明。"}
        apks = []
        for abi in [None] + (list(SUPPORTED_ABIS) if variants else []):
            suffix = "" if abi is None else "-" + abi
            name = f"{prefix}-v{version}{suffix}.apk"
            content = f"signed fixture {version} {code} {abi}".encode()
            (directory / name).write_bytes(content)
            item = {"name": name, "size": len(content), "sha256": digest(content)}
            if abi:
                item.update(abi=abi, minSdk=26)
            apks.append(item)
        value["apk"] = apks[0]
        if variants:
            value["apkVariants"] = apks[1:]
        self.write_source(directory, value)
        release = {"id": identity, "tag_name": tag, "draft": False, "prerelease": prerelease}
        release["assets"] = [{"id": identity * 10 + index, "name": path.name, "size": path.stat().st_size,
                              "state": "uploaded", "digest": "sha256:" + digest(path.read_bytes())}
                             for index, path in enumerate(sorted(directory.iterdir()), 1)]
        return directory, release, value

    def write_source(self, directory, value):
        (directory / "update.json").write_bytes(json_bytes(value))
        apks = [value["apk"]] + value.get("apkVariants", [])
        (directory / "SHA256SUMS").write_text("".join(f"{apk['sha256']}  {apk['name']}\n" for apk in apks), encoding="utf-8")

    def run_publish(self, directory=None, release=None, **kwargs):
        return publish(directory or self.directory, release or self.release, "library-downloads", ENDPOINT,
                       self.output, client=self.s3, **kwargs)

    def test_publishes_all_packages_before_both_public_pointers_and_static_data(self):
        public = self.run_publish()
        self.assertEqual({"universal", *SUPPORTED_ABIS}, set(public["downloads"]))
        puts = self.s3.puts
        self.assertEqual(8, len(puts))  # Five APKs, immutable descriptor, two pointers.
        self.assertEqual(["bucea-library-seat/updates/latest.json", "bucea-library-seat/releases/latest.json"], [put["Key"] for put in puts[-2:]])
        latest = json.loads(self.s3.objects["bucea-library-seat/updates/latest.json"])
        universal = public["downloads"]["universal"]
        self.assertEqual(universal["url"], latest["apk"]["url"])
        self.assertEqual(DOWNLOAD_ORIGIN + "/bucea-library-seat/releases/v1.0.0/" + universal["name"], universal["url"])
        self.assertEqual(public, json.loads((self.output / "release.json").read_text()))
        self.assertEqual(public, json.loads(self.s3.objects["bucea-library-seat/releases/latest.json"]))
        for put in puts[:-2]:
            self.assertEqual(IMMUTABLE_CACHE, put["CacheControl"])
            self.assertEqual("*", put["IfNoneMatch"])
        for put in puts[-2:]:
            self.assertEqual(LATEST_CACHE, put["CacheControl"])
        for put in puts[:5]:
            self.assertEqual("application/vnd.android.package-archive", put["ContentType"])
            self.assertIn("attachment;", put["ContentDisposition"])
        self.assertEqual(list(SUPPORTED_ABIS), [item["abi"] for item in latest["apkVariants"]])
        self.assertNotIn("api.github.com", json.dumps(public))

    def test_retry_is_idempotent_without_rewriting_immutable_files_or_pointers(self):
        original = self.run_publish()
        writes = len(self.s3.puts)
        self.assertEqual(original, self.run_publish())
        self.assertEqual(writes, len(self.s3.puts))

    def test_dry_run_validates_every_asset_without_remote_or_local_writes(self):
        plan = self.run_publish(dry_run=True)
        self.assertTrue(plan["dryRun"])
        self.assertEqual(6, len(plan["objects"]))
        self.assertEqual([], self.s3.puts)
        self.assertFalse(self.output.exists())

    def test_malformed_release_is_rejected_before_any_remote_writes(self):
        for key, value in (("draft", True), ("draft", 0), ("prerelease", 0), ("id", True),
                           ("tag_name", "v0.9.0"), ("tag_name", "v1.0.0/../evil")):
            release = copy.deepcopy(self.release)
            release[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.run_publish(release=release)
        self.assertEqual([], self.s3.puts)

    def test_github_asset_metadata_must_match_every_local_attachment(self):
        for index in range(len(self.release["assets"])):
            for key, value in (("size", True), ("size", 1), ("state", "new"), ("id", 0),
                               ("name", "../evil.apk"), ("digest", "sha256:" + "0" * 64)):
                release = copy.deepcopy(self.release)
                release["assets"][index][key] = value
                with self.subTest(index=index, key=key), self.assertRaises(ValueError):
                    self.run_publish(release=release)
        for assets in (self.release["assets"][:-1], self.release["assets"] + [self.release["assets"][0]]):
            with self.assertRaises(ValueError):
                self.run_publish(release=self.release | {"assets": assets})
        self.assertEqual([], self.s3.puts)

    def test_github_omitting_optional_digest_still_checks_all_local_hashes(self):
        release = copy.deepcopy(self.release)
        for asset in release["assets"]:
            asset.pop("digest")
        self.run_publish(release=release)
        (self.directory / self.source["apkVariants"][0]["name"]).write_bytes(b"wrong bytes")
        with self.assertRaises(ValueError):
            self.run_publish(release=release)

    def test_incomplete_or_mismatched_abi_metadata_never_uploads(self):
        original = copy.deepcopy(self.source)
        mutations = (lambda value: value["apkVariants"].pop(),
                     lambda value: value["apkVariants"].reverse(),
                     lambda value: value["apkVariants"][0].update(minSdk=True),
                     lambda value: value["apkVariants"][0].update(sha256="0" * 64),
                     lambda value: value["apkVariants"][0].update(name="evil.apk"),
                     lambda value: value.update(versionCode=True),
                     lambda value: value.update(notes=""))
        for mutation in mutations:
            value = copy.deepcopy(original)
            mutation(value)
            self.write_source(self.directory, value)
            with self.assertRaises(ValueError):
                self.run_publish()
        self.assertEqual([], self.s3.puts)

    def test_malformed_or_partial_checksums_never_upload(self):
        sums = (self.directory / "SHA256SUMS").read_text().splitlines(keepends=True)
        for lines in (sums[:-1], list(reversed(sums)), sums + [sums[0]], [sums[0]] * 5):
            (self.directory / "SHA256SUMS").write_text("".join(lines))
            with self.assertRaises(ValueError):
                self.run_publish()
        self.assertEqual([], self.s3.puts)

    def test_unknown_local_files_or_symlinks_cannot_be_published(self):
        extra = self.directory / "credentials.txt"
        extra.write_text("private fixture")
        with self.assertRaises(ValueError):
            self.run_publish()
        extra.unlink()
        path = self.directory / self.source["apk"]["name"]
        moved = self.root / "outside.apk"
        path.rename(moved)
        path.symlink_to(moved)
        with self.assertRaises(ValueError):
            self.run_publish()
        self.assertEqual([], self.s3.puts)

    def test_conflicting_immutable_object_is_detected_before_any_upload(self):
        candidate = prepare_release(self.directory, self.release)
        self.s3.objects[candidate["objects"][-1]["key"]] = b"conflicting descriptor"
        with self.assertRaises(ValueError):
            self.run_publish()
        self.assertEqual([], self.s3.puts)

    def test_corrupted_upload_never_changes_latest_manifest(self):
        candidate = prepare_release(self.directory, self.release)
        self.s3.corrupt_key = candidate["objects"][1]["key"]
        with self.assertRaises(ValueError):
            self.run_publish()
        self.assertNotIn("bucea-library-seat/updates/latest.json", self.s3.objects)
        self.assertNotIn("bucea-library-seat/releases/latest.json", self.s3.objects)
        self.assertFalse(self.output.exists())

    def test_failed_new_upload_preserves_previous_manifest_and_historical_apks(self):
        self.run_publish()
        previous = self.s3.objects["bucea-library-seat/updates/latest.json"]
        directory, release, _ = self.fixture("1.0.1", 15, 101)
        self.s3.fail_key = prepare_release(directory, release)["objects"][2]["key"]
        with self.assertRaises(S3Error):
            self.run_publish(directory, release)
        self.assertEqual(previous, self.s3.objects["bucea-library-seat/updates/latest.json"])
        self.assertIn("bucea-library-seat/releases/v1.0.0/BUCEA-Library-Seat-v1.0.0.apk", self.s3.objects)

    def test_old_events_cannot_downgrade_app_or_website(self):
        newest = self.run_publish()
        directory, release, _ = self.fixture("0.3.8", 13, 99)
        selected = self.run_publish(directory, release)
        self.assertEqual(newest, selected)
        self.assertEqual(14, json.loads(self.s3.objects["bucea-library-seat/updates/latest.json"])["versionCode"])
        self.assertEqual(newest, json.loads(self.s3.objects["bucea-library-seat/releases/latest.json"]))
        self.assertEqual(newest, json.loads((self.output / "release.json").read_text()))
        self.assertIn("bucea-library-seat/releases/v0.3.8/BUCEA-Library-Seat-v0.3.8.apk", self.s3.objects)

    def test_stable_wins_equal_version_even_with_lower_release_id(self):
        directory, release, _ = self.fixture("1.0.0", 14, 999, prerelease=True)
        self.run_publish(directory, release)
        selected = self.run_publish()
        self.assertFalse(selected["prerelease"])
        self.assertEqual(100, selected["releaseId"])
        self.assertEqual(selected, self.run_publish(directory, release))

    def test_same_version_and_channel_prefers_greater_release_id(self):
        self.run_publish()
        directory, release, _ = self.fixture("1.0.0", 14, 101, tag="v1.0.0-rebuild")
        selected = self.run_publish(directory, release)
        self.assertEqual(101, selected["releaseId"])
        self.assertEqual(selected, self.run_publish())

    def test_manifest_compare_and_swap_recovers_from_another_publisher(self):
        directory, release, _ = self.fixture("1.0.1", 15, 101)
        newer = prepare_release(directory, release)
        other = FakeS3()
        publish(directory, release, "library-downloads", ENDPOINT, self.root / "other", client=other)
        interrupted = False

        def race(key):
            nonlocal interrupted
            if key == "bucea-library-seat/updates/latest.json" and not interrupted:
                interrupted = True
                self.s3.objects.update(other.objects)

        self.s3.before_put = race
        result = self.run_publish()
        self.assertEqual(newer["public"], result)
        self.assertEqual(15, json.loads(self.s3.objects["bucea-library-seat/updates/latest.json"])["versionCode"])

    def test_partial_pointer_publication_is_repaired_idempotently(self):
        self.s3.fail_key = "bucea-library-seat/releases/latest.json"
        with self.assertRaises(S3Error):
            self.run_publish()
        self.assertIn("bucea-library-seat/updates/latest.json", self.s3.objects)
        self.assertFalse(self.output.exists())
        count = len(self.s3.puts)
        self.s3.fail_key = None
        public = self.run_publish()
        self.assertEqual(count + 1, len(self.s3.puts))
        self.assertEqual(public, json.loads(self.s3.objects["bucea-library-seat/releases/latest.json"]))

    def test_download_origin_prefix_and_r2_endpoint_are_explicitly_validated(self):
        for changes in ({"download_origin": "https://user:secret@evil.example"}, {"prefix": "../escape"},
                        {"download_origin": "https://download.miofelix.com/path"}, {"endpoint_url": "http://evil.example"},
                        {"bucket": "../private"}):
            args = dict(directory=self.directory, release=self.release, bucket="library-downloads",
                        endpoint_url=ENDPOINT, website_output=self.output, client=self.s3)
            args.update(changes)
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                publish(**args)
        self.assertEqual([], self.s3.puts)

    def test_configurable_project_prefix_and_domain_are_applied_consistently(self):
        selected = self.run_publish(download_origin="https://files.example.com", prefix="library-app")
        self.assertIn("library-app/updates/latest.json", self.s3.objects)
        self.assertIn("library-app/releases/latest.json", self.s3.objects)
        for item in selected["downloads"].values():
            self.assertTrue(item["url"].startswith("https://files.example.com/library-app/releases/v1.0.0/"))
        self.assertEqual(selected, self.run_publish(download_origin="https://files.example.com", prefix="library-app"))


if __name__ == "__main__":
    unittest.main()
