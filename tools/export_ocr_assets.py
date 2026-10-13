#!/usr/bin/env python3
"""Export the default ddddocr model and ordered charset without importing it.

Usage: python3 tools/export_ocr_assets.py --source /path/to/ddddocr
Only literal assignments from charsets.py are evaluated. No source code runs.
"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import shutil
from pathlib import Path


def export(source: Path, destination: Path) -> dict:
    package = source / "ddddocr"
    model = package / "common_old.onnx"
    charset = None
    for node in ast.parse((package / "charsets.py").read_text(encoding="utf-8")).body:
        if isinstance(node, ast.Assign) and any(
            isinstance(target, ast.Name) and target.id == "CHARSET_OLD"
            for target in node.targets
        ):
            charset = ast.literal_eval(node.value)
            break
    if not isinstance(charset, list) or not charset or charset[0] != "":
        raise ValueError("CHARSET_OLD must be an ordered list with CTC blank at index 0")
    if any(not isinstance(character, str) for character in charset):
        raise ValueError("Invalid charset")
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, destination / model.name)
    (destination / "charset.json").write_text(
        json.dumps(charset, ensure_ascii=False, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
    shutil.copyfile(source / "LICENSE", destination / "LICENSE.ddddocr.txt")
    metadata = {
        "source": "https://github.com/sml2h3/ddddocr",
        "model": model.name,
        "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
        "charset_size": len(charset),
        "height": 64,
        "channels": 1,
        "resize": "Pillow Lanczos3, preserve aspect ratio, floor target width",
        "normalization": "pixel / 255.0",
        "ctc_blank_index": 0,
    }
    (destination / "model.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument(
        "--destination",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app/src/main/assets/ocr",
    )
    arguments = parser.parse_args()
    print(json.dumps(export(arguments.source.resolve(), arguments.destination.resolve()), indent=2))


if __name__ == "__main__":
    main()
