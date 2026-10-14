#!/usr/bin/env python3
"""Serve verified update files directly on a port. Python 3.9+, no dependencies."""
from __future__ import annotations

import argparse
import contextlib
from datetime import timezone
from email.utils import formatdate, parsedate_to_datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import logging
import os
from pathlib import Path
import re
import signal
import stat
import threading
from urllib.parse import urlsplit

LOG = logging.getLogger("update-server")
REVALIDATE = "no-cache, max-age=0, must-revalidate"
IMMUTABLE = "public, max-age=604800, immutable"


def byte_range(value, size):
    """Return one inclusive byte range; ignore unsupported units/multipart ranges."""
    if not value.lower().startswith("bytes=") or "," in value:
        return None
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value, re.IGNORECASE)
    if not match or not any(match.groups()) or size == 0:
        raise ValueError("unsatisfiable range")
    first, last = match.groups()
    if not first:
        suffix = int(last)
        if suffix <= 0:
            raise ValueError("unsatisfiable range")
        return max(0, size - suffix), size - 1
    start = int(first)
    end = min(int(last), size - 1) if last else size - 1
    if start >= size or start > end:
        raise ValueError("unsatisfiable range")
    return start, end


def http_date(value):
    try:
        result = parsedate_to_datetime(value)
        if result.tzinfo is None:
            result = result.replace(tzinfo=timezone.utc)
        return int(result.timestamp())
    except (ValueError, TypeError, OverflowError):
        return None


class UpdateServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, root: Path, max_connections=32):
        self.root = root.resolve()
        self.slots = threading.BoundedSemaphore(max_connections)
        super().__init__(address, UpdateHandler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(30)
        return connection, address

    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n"
                                b"Cache-Control: no-store\r\nRetry-After: 5\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            finally:
                self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class UpdateHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "BUCEAUpdates/1"
    sys_version = ""

    def log_message(self, _format, *_args):
        # No request headers, cookies, tokens or query strings are logged.
        pass

    def handle(self):
        try:
            super().handle()
        except (ConnectionError, TimeoutError):
            pass

    def finish(self):
        with contextlib.suppress(ConnectionError, TimeoutError):
            super().finish()

    def headers_for(self, status, headers):
        self.response_started = True
        self.send_response(status)
        for key, value in headers.items():
            self.send_header(key, str(value))
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.close_connection = True
        self.end_headers()

    def error(self, status, message, extra=None):
        data = (json.dumps({"error": message}) + "\n").encode()
        headers = {"Content-Type": "application/json; charset=utf-8", "Content-Length": len(data),
                   "Cache-Control": "no-store"}
        headers.update(extra or {})
        self.headers_for(status, headers)
        if self.command != "HEAD":
            self.wfile.write(data)

    def do_GET(self):
        self.serve_file()

    def do_HEAD(self):
        self.serve_file()

    def method_not_allowed(self):
        self.error(405, "read-only service", {"Allow": "GET, HEAD"})

    do_POST = do_PUT = do_PATCH = do_DELETE = do_OPTIONS = method_not_allowed

    def serve_file(self):
        try:
            target = urlsplit(self.path)
        except ValueError:
            self.error(404, "not found")
            return
        if target.scheme or target.netloc or target.fragment:
            self.error(404, "not found")
            return
        manifest = target.path == "/updates/latest.json"
        if not manifest and not re.fullmatch(r"/updates/apks/[1-9][0-9]*\.apk", target.path):
            self.error(404, "not found")
            return
        try:
            path = (self.server.root / target.path.lstrip("/")).resolve(strict=True)
            path.relative_to(self.server.root)
            # Open once: atomic replacement or cleanup cannot change this response's bytes.
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            with os.fdopen(descriptor, "rb") as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode):
                    raise OSError("not a regular update file")
                self.send_file(stream, info, manifest)
        except (FileNotFoundError, NotADirectoryError, ValueError):
            if manifest:
                self.error(503, "updates temporarily unavailable", {"Retry-After": "600"})
            else:
                self.error(404, "not found")
        except (ConnectionError, TimeoutError):
            pass
        except OSError:
            if not getattr(self, "response_started", False):
                self.error(503, "updates temporarily unavailable", {"Retry-After": "60"})
            else:
                self.close_connection = True
                LOG.warning("update transfer interrupted")

    def send_file(self, stream, info, manifest):
        size = info.st_size
        etag = f'"{info.st_mtime_ns:x}-{size:x}-{info.st_ino:x}"'
        modified = int(info.st_mtime)
        headers = {
            "Cache-Control": REVALIDATE if manifest else IMMUTABLE,
            "Content-Type": "application/json; charset=utf-8" if manifest else "application/vnd.android.package-archive",
            "ETag": etag, "Last-Modified": formatdate(modified, usegmt=True),
        }
        if_none_match = self.headers.get("If-None-Match")
        not_modified = False
        if if_none_match is not None:
            values = [value.strip().removeprefix("W/") for value in if_none_match.split(",")]
            not_modified = "*" in values or etag in values
        else:
            since = http_date(self.headers.get("If-Modified-Since"))
            not_modified = since is not None and modified <= since
        if not_modified:
            self.headers_for(304, headers)
            return

        requested = None
        value = self.headers.get("Range")
        if value and not manifest and self.command == "GET":
            if_range = self.headers.get("If-Range")
            date = http_date(if_range) if if_range else None
            if not if_range or if_range == etag or date is not None and modified <= date:
                try:
                    requested = byte_range(value.strip(), size)
                except ValueError:
                    self.error(416, "unsatisfiable range", {"Content-Range": f"bytes */{size}"})
                    return
        start, end = requested if requested else (0, size - 1)
        count = end - start + 1
        headers["Content-Length"] = count
        if not manifest:
            headers["Accept-Ranges"] = "bytes"
        if requested:
            headers["Content-Range"] = f"bytes {start}-{end}/{size}"
        self.headers_for(206 if requested else 200, headers)
        if self.command == "HEAD":
            return
        stream.seek(start)
        while count > 0:
            chunk = stream.read(min(count, 256 * 1024))
            if not chunk:
                break
            self.wfile.write(chunk)
            count -= len(chunk)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/var/lib/bucea-updates/public"))
    parser.add_argument("--listen", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=9080)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    server = UpdateServer((args.listen, args.port), args.root)

    def stop(_signal, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    LOG.info("serving updates on %s:%s", args.listen, args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
