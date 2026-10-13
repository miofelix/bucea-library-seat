"""Real HTTP checks for the supplied Caddy config; set CADDY_BINARY to run on macOS."""
import http.client
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest


CADDY = os.environ.get("CADDY_BINARY") or shutil.which("caddy")


@unittest.skipUnless(CADDY, "set CADDY_BINARY or install Caddy for HTTP integration checks")
class CaddyTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            self.port = sock.getsockname()[1]
        self.updates = self.root / "public/updates"
        (self.updates / "apks").mkdir(parents=True)
        self.data = bytes(range(256)) * 1000
        (self.updates / "apks/123.apk").write_bytes(self.data)
        (self.updates / "latest.json").write_text('{"versionCode":8,"assetId":123}')
        # Keep private state outside the root, as in the service deployment.
        (self.root / "state").mkdir()
        (self.root / "state/token").write_text("private fixture")
        config = Path("deploy/updates/Caddyfile").read_text()
        config = "{\n admin off\n persist_config off\n}\n" + config.replace(
            ":9080", f":{self.port}").replace("/var/lib/bucea-updates/public", str(self.root / "public"))
        filename = self.root / "Caddyfile"
        filename.write_text(config)
        self.log = (self.root / "caddy.log").open("wb")
        self.addCleanup(self.log.close)
        self.process = subprocess.Popen([CADDY, "run", "--config", str(filename), "--adapter", "caddyfile"], stdout=self.log, stderr=self.log)
        self.addCleanup(self.stop)
        for _ in range(100):
            try:
                with socket.create_connection(("127.0.0.1", self.port), timeout=.1):
                    return
            except OSError:
                if self.process.poll() is not None:
                    self.fail((self.root / "caddy.log").read_text())
                time.sleep(.02)
        self.fail("Caddy did not start")

    def stop(self):
        self.process.terminate()
        self.process.wait(timeout=5)

    def get(self, path, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        # cloudflared preserves the public Host header while connecting to loopback.
        request_headers = {"Host": "bucea-library-seat.miofelix.com"} | (headers or {})
        connection.request("GET", path, headers=request_headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_manifest_revalidates_and_missing_manifest_returns_503(self):
        code, headers, data = self.get("/updates/latest.json")
        self.assertEqual(200, code)
        self.assertIn("must-revalidate", headers["Cache-Control"])
        self.assertEqual(304, self.get("/updates/latest.json", {"If-None-Match": headers["Etag"]})[0])
        staged = self.updates / ".next.json"
        staged.write_text('{"versionCode":9,"assetId":124}')
        staged.replace(self.updates / "latest.json")
        code, new_headers, _ = self.get("/updates/latest.json", {"If-None-Match": headers["Etag"]})
        self.assertEqual(200, code)
        self.assertNotEqual(headers["Etag"], new_headers["Etag"])
        (self.updates / "latest.json").unlink()
        self.assertEqual(503, self.get("/updates/latest.json")[0])

    def test_ranges_resume_same_content_across_version_switch_and_service_restart(self):
        code, headers, first = self.get("/updates/apks/123.apk", {"Range": "bytes=0-999"})
        self.assertEqual(206, code)
        self.assertEqual("bytes 0-999/256000", headers["Content-Range"])
        self.assertIn("immutable", headers["Cache-Control"])
        (self.updates / "apks/124.apk").write_bytes(b"different APK")
        (self.updates / "latest.json").write_text('{"versionCode":9,"assetId":124}')
        self.process.terminate()
        self.process.wait(timeout=5)
        filename = self.root / "Caddyfile"
        self.process = subprocess.Popen([CADDY, "run", "--config", str(filename), "--adapter", "caddyfile"], stdout=self.log, stderr=self.log)
        for _ in range(100):
            try:
                code, _, rest = self.get("/updates/apks/123.apk", {"Range": "bytes=1000-"})
                break
            except OSError:
                time.sleep(.02)
        self.assertEqual(206, code)
        self.assertEqual(self.data, first + rest)

    def test_unlisted_paths_and_private_state_are_not_served(self):
        for path in ("/state/token", "/updates/task.json", "/updates/apks/.secret", "/updates/apks/0.apk", "/updates/latest.json/other"):
            self.assertEqual(404, self.get(path)[0])
        code, headers, _ = self.get("/updates/apks/124.apk")
        self.assertEqual(404, code)
        self.assertEqual("no-store", headers["Cache-Control"])


if __name__ == "__main__":
    unittest.main()
