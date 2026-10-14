#!/usr/bin/env python3
"""Build tiny signed APKs for real Android PackageManager updater checks.

These APKs are verification fixtures, never application distributions. Generated
assets and the disposable alternate signing key stay outside version control.
"""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile


def run(*args: str) -> None:
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--keystore", type=Path,
                        default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--output", type=Path,
                        default=Path("app/src/androidTest/assets/update-fixtures"))
    args = parser.parse_args()
    if not args.keystore.is_file():
        raise SystemExit("Existing application signing keystore is required")
    build_tools = args.sdk / "build-tools/35.0.0"
    platform = args.sdk / "platforms/android-35/android.jar"
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="bucea-update-fixtures-") as directory:
        work = Path(directory)
        alternate = work / "alternate.keystore"
        run("keytool", "-genkeypair", "-keystore", str(alternate), "-alias", "androiddebugkey",
            "-storepass", "android", "-keypass", "android", "-keyalg", "RSA",
            "-validity", "365", "-dname", "CN=Updater Test Fixture", "-noprompt")
        for name, package, debuggable, key in [
            ("valid", "cn.edu.bucea.library", False, args.keystore),
            ("wrong-package", "cn.edu.bucea.update.fixture", False, args.keystore),
            ("wrong-signature", "cn.edu.bucea.library", False, alternate),
            ("debuggable", "cn.edu.bucea.library", True, args.keystore),
        ]:
            source = work / name
            source.mkdir()
            manifest = source / "AndroidManifest.xml"
            manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="{package}" android:versionCode="1008" android:versionName="0.3.4-test">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:hasCode="false" android:debuggable="{str(debuggable).lower()}"
        android:label="Updater verification fixture" />
</manifest>''', encoding="utf-8")
            unsigned = source / "unsigned.apk"
            aligned = source / "aligned.apk"
            run(str(build_tools / "aapt"), "package", "-f", "-M", str(manifest),
                "-I", str(platform), "-F", str(unsigned))
            run(str(build_tools / "zipalign"), "-f", "4", str(unsigned), str(aligned))
            output = args.output / f"{name}.apk"
            run(str(build_tools / "apksigner"), "sign", "--ks", str(key),
                "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
                "--key-pass", "pass:android", "--out", str(output), str(aligned))
            run(str(build_tools / "apksigner"), "verify", str(output))
            output.with_suffix(".apk.idsig").unlink(missing_ok=True)
    print("Generated four signed updater verification APK fixtures")


if __name__ == "__main__":
    main()
