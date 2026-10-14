#!/usr/bin/env python3
"""Build an SDK-only instrumentation APK for acceptance against the exact release.

The runner contains no application, Kotlin, AndroidX or JUnit dependencies. It
reads only explicitly recorded private acceptance fixtures. The existing signing
keystore is required and is never generated or modified. No device operations
are performed by this builder.
"""

import argparse
from pathlib import Path
import subprocess
import tempfile
import zipfile


EXPECTED_CERTIFICATE_SHA256 = "211ec3718b18fe7bfd4d955be4a035c1ec778cf7728fdfa996f6b476adf14801"


def run(*args: object) -> str:
    result = subprocess.run([str(argument) for argument in args], check=False,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{Path(str(args[0])).name} failed: {result.stderr.strip()}")
    return result.stdout


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--keystore", type=Path,
                        default=Path.home() / ".android/debug.keystore")
    parser.add_argument("--output", type=Path,
                        default=root / ".private/qa-0.3.4/platform-harness")
    parser.add_argument("--build-tools", default="35.0.0")
    parser.add_argument("--javac", default="javac")
    args = parser.parse_args()
    source = root / "tools/android_release_acceptance/FrameworkAcceptanceInstrumentation.java"
    build_tools = args.sdk / "build-tools" / args.build_tools
    android_jar = args.sdk / "platforms/android-35/android.jar"
    if not args.keystore.is_file():
        raise SystemExit("The existing original application signing keystore is required")
    for required in (source, android_jar, build_tools / "aapt", build_tools / "d8",
                     build_tools / "zipalign", build_tools / "apksigner"):
        if not required.is_file():
            raise SystemExit(f"Required build input is missing: {required}")
    args.output.mkdir(parents=True, exist_ok=True)
    apk = args.output / "release-acceptance.apk"
    with tempfile.TemporaryDirectory(prefix="build-", dir=args.output) as directory:
        work = Path(directory)
        classes = work / "classes"
        dex = work / "dex"
        classes.mkdir()
        dex.mkdir()
        manifest = work / "AndroidManifest.xml"
        manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="cn.edu.bucea.library.releaseacceptance" android:versionCode="1" android:versionName="1">
    <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
    <application android:hasCode="true" android:debuggable="true" android:label="Release acceptance harness" />
    <instrumentation android:name="cn.edu.bucea.library.releaseacceptance.FrameworkAcceptanceInstrumentation"
        android:targetPackage="cn.edu.bucea.library" android:functionalTest="true" />
</manifest>''', encoding="utf-8")
        run(args.javac, "--release", "8", "-classpath", android_jar, "-d", classes, source)
        class_files = sorted(classes.rglob("*.class"))
        if not class_files:
            raise RuntimeError("Java compilation produced no harness classes")
        run(build_tools / "d8", "--min-api", "26", "--lib", android_jar,
            "--output", dex, *class_files)
        unsigned = work / "unsigned.apk"
        aligned = work / "aligned.apk"
        run(build_tools / "aapt", "package", "-f", "-M", manifest,
            "-I", android_jar, "-F", unsigned)
        with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_STORED) as archive:
            for dex_file in sorted(dex.glob("classes*.dex")):
                archive.write(dex_file, dex_file.name)
        run(build_tools / "zipalign", "-f", "4", unsigned, aligned)
        run(build_tools / "apksigner", "sign", "--ks", args.keystore,
            "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android",
            "--key-pass", "pass:android", "--out", apk, aligned)
        verified = run(build_tools / "apksigner", "verify", "--print-certs", apk)
        certificate = f"Signer #1 certificate SHA-256 digest: {EXPECTED_CERTIFICATE_SHA256}"
        if certificate not in verified:
            apk.unlink(missing_ok=True)
            raise RuntimeError("Harness signer differs from the original application certificate")
        apk.with_suffix(".apk.idsig").unlink(missing_ok=True)
    print(f"Built SDK-only release acceptance harness: {apk}")


if __name__ == "__main__":
    main()
