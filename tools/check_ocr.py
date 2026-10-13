#!/usr/bin/env python3
"""Verify bundled OCR against local ddddocr and optionally Kotlin preprocessing.

Install the local reference in an isolated environment:
    uv venv .venv
    uv pip install --python .venv/bin/python -e /path/to/ddddocr
    .venv/bin/python tools/check_ocr.py --source /path/to/ddddocr --sample captcha.png

Use --verify-kotlin to compare LanczosPreprocessor with Pillow on RGB, grayscale,
RGBA and captcha images. This needs Java and Kotlin compiler jars in Gradle's
local cache, typically available after the first Android build.
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import struct
import subprocess
import tempfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets/ocr"


def tensor(image: Image.Image) -> np.ndarray:
    width = int(image.width * (64 / image.height))
    resized = image.resize((width, 64), Image.Resampling.LANCZOS).convert("L")
    return np.asarray(resized).astype(np.float32)[None, None] / np.float32(255)


def decode(logits: np.ndarray, charset: list[str]) -> str:
    if logits.ndim == 3:
        logits = logits[:, 0, :] if logits.shape[1] == 1 else logits[0, :, :]
    indices = logits.argmax(axis=-1).flatten()
    result = []
    previous = -1
    for index in indices:
        if index != previous and index != 0:
            result.append(charset[int(index)])
        previous = index
    return "".join(result)


def verify_kotlin(samples: list[Path]) -> None:
    cache = Path(os.environ.get("GRADLE_USER_HOME", str(Path.home() / ".gradle"))) / "caches/modules-2/files-2.1"
    compilers = sorted((cache / "org.jetbrains.kotlin/kotlin-compiler-embeddable").glob("*/*/*.jar"))
    if not compilers:
        raise RuntimeError("Kotlin compiler missing from Gradle cache; build the Android app first")
    compiler = compilers[-1]
    version = compiler.parents[1].name

    def jar(group: str, artifact: str, preferred_version: str = "*") -> Path:
        matches = sorted((cache / group / artifact).glob(f"{preferred_version}/*/*.jar"))
        if not matches:
            raise RuntimeError(f"Missing compiler dependency: {group}/{artifact}")
        return matches[-1]

    stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
    dependencies = [
        compiler,
        stdlib,
        jar("org.jetbrains.kotlin", "kotlin-script-runtime", version),
        jar("org.jetbrains", "annotations"),
        jar("org.jetbrains.intellij.deps", "trove4j"),
        jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm"),
    ]
    cli = """
package cn.edu.bucea.library.ocr
import java.io.*
import java.nio.FloatBuffer
object OcrParityCli {
    @JvmStatic fun main(args: Array<String>) {
        val input = DataInputStream(FileInputStream(args[0]))
        val width = input.readInt()
        val height = input.readInt()
        val pixels = IntArray(width * height) { input.readInt() }
        input.close()
        val result = LanczosPreprocessor.process(pixels, width, height)
        DataOutputStream(FileOutputStream(args[1])).use { output ->
            result.pixels.forEach { output.writeFloat(it) }
        }
        val sequence = intArrayOf(0, 1, 1, 0, 1, 2, 2, 0)
        val logits = FloatArray(sequence.size * 3) { if (it % 3 == sequence[it / 3]) 1f else 0f }
        check(CtcDecoder.decode(FloatBuffer.wrap(logits), listOf("", "a", "b")) == "aab")
    }
}
"""
    with tempfile.TemporaryDirectory(prefix="bucea-ocr-parity-") as temporary:
        directory = Path(temporary)
        (directory / "OcrParityCli.kt").write_text(cli, encoding="utf-8")
        source = ROOT / "app/src/main/java/cn/edu/bucea/library/ocr"
        subprocess.run([
            "java", "-cp", os.pathsep.join(map(str, dependencies)),
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect",
            "-jvm-target", "17", "-classpath", str(stdlib), "-d", str(directory / "classes"),
            str(source / "LanczosPreprocessor.kt"), str(source / "CtcDecoder.kt"),
            str(directory / "OcrParityCli.kt"),
        ], check=True)
        rng = np.random.default_rng(42)
        fixtures = [(str(sample.name), Image.open(sample)) for sample in samples]
        fixtures += [
            ("rgb-upsample", Image.fromarray(rng.integers(0, 256, (37, 109, 3), dtype=np.uint8))),
            ("rgb-downsample", Image.fromarray(rng.integers(0, 256, (137, 201, 3), dtype=np.uint8))),
            ("gray", Image.fromarray(rng.integers(0, 256, (49, 150), dtype=np.uint8))),
            ("rgba", Image.fromarray(rng.integers(0, 256, (39, 95, 4), dtype=np.uint8))),
            ("rgba-unchanged-size", Image.fromarray(rng.integers(0, 256, (64, 120, 4), dtype=np.uint8))),
        ]
        for label, image in fixtures:
            rgba = np.asarray(image.convert("RGBA")).astype(np.uint32)
            argb = (rgba[:, :, 3] << 24) | (rgba[:, :, 0] << 16) | (rgba[:, :, 1] << 8) | rgba[:, :, 2]
            input_file = directory / "input.bin"
            output_file = directory / "output.bin"
            input_file.write_bytes(struct.pack(">ii", image.width, image.height) + argb.astype(">u4").tobytes())
            subprocess.run([
                "java", "-cp", os.pathsep.join([str(directory / "classes"), str(stdlib)]),
                "cn.edu.bucea.library.ocr.OcrParityCli", str(input_file), str(output_file),
            ], check=True)
            actual = np.frombuffer(output_file.read_bytes(), dtype=">f4")
            expected = tensor(image).flatten()
            np.testing.assert_array_equal(actual, expected, err_msg=f"Pillow/Kotlin mismatch: {label}")
            print(f"Kotlin preprocessing and CTC parity: {label} ({len(actual)} pixels)")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True, help="Local reference ddddocr project")
    parser.add_argument("--sample", type=Path, action="append", default=[])
    parser.add_argument("--expected", help="Expected text for the first sample")
    parser.add_argument("--verify-kotlin", action="store_true")
    arguments = parser.parse_args()
    import ddddocr

    metadata = json.loads((ASSETS / "model.json").read_text())
    model = ASSETS / metadata["model"]
    assert hashlib.sha256(model.read_bytes()).hexdigest() == metadata["model_sha256"]
    assert model.read_bytes() == (arguments.source / "ddddocr" / model.name).read_bytes()
    charset = json.loads((ASSETS / "charset.json").read_text())
    reference = ddddocr.DdddOcr(show_ad=False)
    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    session.set_providers(["CPUExecutionProvider"])
    samples = arguments.sample or [arguments.source / "samples/yzm1.png"]
    for index, sample in enumerate(samples):
        contents = sample.read_bytes()
        output = session.run(None, {session.get_inputs()[0].name: tensor(Image.open(io.BytesIO(contents)))})[0]
        actual = decode(output, charset)
        expected = reference.classification(contents)
        assert actual == expected, (actual, expected)
        if index == 0 and arguments.expected is not None:
            assert actual == arguments.expected, (actual, arguments.expected)
        print(f"Reference/bundled ONNX parity: {sample.name} → {actual!r}; output shape={output.shape}")
    if arguments.verify_kotlin:
        verify_kotlin(samples)


if __name__ == "__main__":
    main()
