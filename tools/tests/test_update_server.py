"""Real HTTP tests using only Python's standard library, on macOS and Raspberry Pi."""
from concurrent.futures import ThreadPoolExecutor
import http.client
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import unittest

from tools.update_server import UpdateServer


class UpdateServerTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.updates = self.root / "public/updates"
        (self.updates / "apks").mkdir(parents=True)
        self.data = bytes(range(256)) * 1000
        (self.updates / "apks/123.apk").write_bytes(self.data)
        (self.updates / "latest.json").write_text('{"versionCode":8,"assetId":123}')
        (self.root / "state").mkdir()
        (self.root / "state/token").write_text("private fixture")
        self.start()
        self.addCleanup(self.stop)

    def start(self, port=0, max_connections=32):
        self.server = UpdateServer(("127.0.0.1", port), self.root / "public", max_connections)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": .01}, daemon=True)
        self.thread.start()

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)

    def get(self, path, headers=None, method="GET"):
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=5)
        request_headers = {"Host": "bucea-library-seat.miofelix.com"} | (headers or {})
        connection.request(method, path, headers=request_headers)
        response = connection.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        connection.close()
        return result

    def test_manifest_revalidates_and_atomic_switch_changes_etag(self):
        code, headers, _ = self.get("/updates/latest.json")
        self.assertEqual(200, code)
        self.assertIn("must-revalidate", headers["Cache-Control"])
        self.assertEqual(304, self.get("/updates/latest.json", {"If-None-Match": headers["ETag"]})[0])
        staged = self.updates / ".next.json"
        staged.write_text('{"versionCode":9,"assetId":124}')
        staged.replace(self.updates / "latest.json")
        code, new_headers, data = self.get("/updates/latest.json", {"If-None-Match": headers["ETag"]})
        self.assertEqual(200, code)
        self.assertEqual(9, json.loads(data)["versionCode"])
        self.assertNotEqual(headers["ETag"], new_headers["ETag"])

    def test_withdrawn_manifest_returns_non_cached_503_and_head(self):
        (self.updates / "latest.json").unlink()
        code, headers, data = self.get("/updates/latest.json")
        self.assertEqual(503, code)
        self.assertEqual("no-store", headers["Cache-Control"])
        self.assertEqual(len(data), int(headers["Content-Length"]))
        self.assertEqual("600", headers["Retry-After"])
        code, _, data = self.get("/updates/latest.json", method="HEAD")
        self.assertEqual(503, code)
        self.assertEqual(b"", data)

    def test_ranges_resume_same_content_across_version_switch_and_restart(self):
        code, headers, first = self.get("/updates/apks/123.apk", {"Range": "bytes=0-999"})
        self.assertEqual(206, code)
        self.assertEqual("bytes 0-999/256000", headers["Content-Range"])
        self.assertIn("immutable", headers["Cache-Control"])
        (self.updates / "apks/124.apk").write_bytes(b"different APK")
        (self.updates / "latest.json").write_text('{"versionCode":9,"assetId":124}')
        port = self.port
        self.stop()
        self.start(port)
        code, new_headers, rest = self.get("/updates/apks/123.apk", {"Range": "bytes=1000-", "If-Range": headers["ETag"]})
        self.assertEqual(206, code)
        self.assertEqual(headers["ETag"], new_headers["ETag"])
        self.assertEqual(self.data, first + rest)

    def test_suffix_open_and_clamped_ranges(self):
        for value, expected in (("bytes=-25", self.data[-25:]), ("bytes=255990-", self.data[255990:]),
                                ("bytes=255990-999999", self.data[255990:]), ("bytes=0-0", self.data[:1])):
            code, headers, body = self.get("/updates/apks/123.apk", {"Range": value})
            self.assertEqual(206, code)
            self.assertEqual(expected, body)
            self.assertEqual(len(body), int(headers["Content-Length"]))

    def test_unsatisfiable_or_invalid_ranges(self):
        for value in ("bytes=256000-", "bytes=9-2", "bytes=-0", "bytes=", "bytes=abc"):
            code, headers, _ = self.get("/updates/apks/123.apk", {"Range": value})
            self.assertEqual(416, code)
            self.assertEqual("bytes */256000", headers["Content-Range"])
        for value in ("items=0-1", "bytes=0-1,4-5"):
            code, _, body = self.get("/updates/apks/123.apk", {"Range": value})
            self.assertEqual(200, code)
            self.assertEqual(self.data, body)

    def test_head_reports_metadata_without_body_and_ignores_range(self):
        code, headers, body = self.get("/updates/apks/123.apk", {"Range": "bytes=0-9"}, "HEAD")
        self.assertEqual(200, code)
        self.assertEqual(len(self.data), int(headers["Content-Length"]))
        self.assertEqual("bytes", headers["Accept-Ranges"])
        self.assertEqual(b"", body)

    def test_conditional_requests_and_if_range(self):
        _, headers, _ = self.get("/updates/apks/123.apk")
        etag = headers["ETag"]
        for condition in (etag, "W/" + etag, '"unrelated", ' + etag, "*"):
            self.assertEqual(304, self.get("/updates/apks/123.apk", {"If-None-Match": condition})[0])
        self.assertEqual(304, self.get("/updates/apks/123.apk", {"If-Modified-Since": headers["Last-Modified"]})[0])
        self.assertEqual(200, self.get("/updates/apks/123.apk", {
            "If-None-Match": '"other"', "If-Modified-Since": headers["Last-Modified"]})[0])
        for condition in ('"outdated"', "W/" + etag, "Thu, 01 Jan 1970 00:00:00 GMT"):
            code, _, data = self.get("/updates/apks/123.apk", {"Range": "bytes=0-9", "If-Range": condition})
            self.assertEqual(200, code)
            self.assertEqual(self.data, data)
        self.assertEqual(206, self.get("/updates/apks/123.apk", {
            "Range": "bytes=0-9", "If-Range": headers["Last-Modified"]})[0])

    def test_unknown_paths_and_missing_assets_are_not_cached(self):
        for path in ("/", "/state/token", "/updates/task.json", "/updates/apks/.secret", "/updates/apks/0.apk",
                     "/updates/latest.json/other", "/updates/apks/../latest.json", "/updates/apks/%2e%2e/latest.json",
                     "/updates/apks/124.apk"):
            code, headers, _ = self.get(path)
            self.assertEqual(404, code)
            self.assertEqual("no-store", headers["Cache-Control"])

    def test_symlinks_cannot_expose_private_files(self):
        (self.updates / "apks/999.apk").symlink_to(self.root / "state/token")
        self.assertEqual(404, self.get("/updates/apks/999.apk")[0])

    def test_unsupported_methods_do_not_write_files(self):
        original = (self.updates / "latest.json").read_bytes()
        for method in ("POST", "PUT", "PATCH", "DELETE", "OPTIONS"):
            code, headers, _ = self.get("/updates/latest.json", method=method)
            self.assertEqual(405, code)
            self.assertEqual("GET, HEAD", headers["Allow"])
        self.assertEqual(original, (self.updates / "latest.json").read_bytes())

    def test_concurrent_downloads_keep_separate_range_positions(self):
        def fetch(index):
            start = index * 500
            code, _, data = self.get("/updates/apks/123.apk", {"Range": f"bytes={start}-{start + 499}"})
            self.assertEqual(206, code)
            self.assertEqual(self.data[start:start + 500], data)
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(fetch, range(32)))

    def test_connection_limit_returns_503_and_recovers(self):
        self.stop()
        self.start(max_connections=1)
        started = threading.Event()
        finished = threading.Event()
        original = self.server.process_request_thread

        def tracked_request(request, address):
            started.set()
            try:
                original(request, address)
            finally:
                finished.set()

        self.server.process_request_thread = tracked_request
        connection = socket.create_connection(("127.0.0.1", self.port), timeout=5)
        self.addCleanup(connection.close)
        connection.sendall(b"GET /updates/latest.json HTTP/1.1\r\nHost: example\r\n")
        # This request cannot finish until the test sends its final header terminator.
        # The server acquires its only slot before starting to read request headers.
        self.assertTrue(started.wait(timeout=2))
        self.assertEqual(503, self.get("/updates/latest.json")[0])
        connection.sendall(b"\r\n")
        response = http.client.HTTPResponse(connection)
        response.begin()
        self.assertEqual(200, response.status)
        response.read()
        connection.close()
        self.assertTrue(finished.wait(timeout=2))
        self.assertEqual(200, self.get("/updates/latest.json")[0])

    def test_command_line_starts_without_existing_cache_and_stops(self):
        with socket.socket() as reservation:
            reservation.bind(("127.0.0.1", 0))
            port = reservation.getsockname()[1]
        process = subprocess.Popen([sys.executable, "tools/update_server.py", "--root", str(self.root / "absent"),
                                    "--listen", "127.0.0.1", "--port", str(port)],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            line = process.stderr.readline()
            self.assertIn(f"127.0.0.1:{port}", line)
            previous = self.port
            self.port = port
            self.assertEqual(503, self.get("/updates/latest.json")[0])
            self.port = previous
            process.terminate()
            self.assertEqual(0, process.wait(timeout=5))
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            process.stdout.close()
            process.stderr.close()


if __name__ == "__main__":
    unittest.main()
