#!/usr/bin/env python3
"""Package server deployment files plus a reviewable source patch, without private data."""
import io
from pathlib import Path
import subprocess
import tarfile


def main():
    root = Path(__file__).resolve().parents[1]
    files = ["tools/update_mirror.py", "tools/update_server.py",
             "tools/tests/test_update_mirror.py", "tools/tests/test_update_server.py",
             "docs/UPDATES.md", "docs/RELEASE.md", "docs/UPDATE_VALIDATION.md", "docs/RASPBERRY_PI_HANDOFF.md"]
    files.extend("deploy/updates/" + name for name in ("bucea-update-mirror.service", "bucea-update-mirror.timer",
                 "bucea-updates-http.service", "bucea-updates.env.example", "bucea-updates-http.env.example"))
    upstream = subprocess.run(["git", "rev-parse", "--verify", "@{upstream}"],
                              cwd=root, capture_output=True, text=True)
    # Keep committed, unpushed work in the patch so a Pi at origin/main can apply it.
    base = subprocess.check_output(["git", "merge-base", "HEAD", upstream.stdout.strip()], cwd=root) if upstream.returncode == 0 else (
        subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root))
    patch = subprocess.check_output(["git", "diff", base.decode().strip(), "--binary"], cwd=root)
    added = subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "-z"], cwd=root)
    for raw in added.split(b"\0"):
        if not raw:
            continue
        name = raw.decode()
        if not name.startswith(("app/", "tools/", "docs/", "deploy/")):
            continue
        if any(part.startswith(".") or part in ("build", "__pycache__") for part in Path(name).parts):
            continue
        result = subprocess.run(["git", "diff", "--no-index", "--binary", "--", "/dev/null", name],
                                cwd=root, capture_output=True, check=False)
        if result.returncode not in (0, 1):
            raise RuntimeError("could not create source patch")
        patch += result.stdout
    output = root / "build/updates-handoff.tar.gz"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(output, "w:gz") as archive:
        for name in files:
            archive.add(root / name, arcname=name)
        for name, data in (("BASE_COMMIT.txt", base), ("updates-source.patch", patch)):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(data))
    print(f"Created {output} ({output.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
