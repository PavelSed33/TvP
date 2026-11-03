#!/usr/bin/env python3
"""Assemble platform-ready source directories for Samsung Tizen and LG webOS."""

from __future__ import annotations

import json
from pathlib import Path
import re
import shutil
import struct
import xml.etree.ElementTree as ET
import zlib


ROOT = Path(__file__).resolve().parent
COMMON = ROOT / "common"
OUTPUT = ROOT / "dist"
ASSETS = ("index.html", "app.css", "client-core.js", "app.js")


def _png_chunk(kind: bytes, data: bytes) -> bytes:
    body = kind + data
    return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)


def _icon_png(size: int) -> bytes:
    background = (7, 17, 31)
    accent = (102, 223, 202)
    rows = [
        ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
        ("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
    ]
    scale = max(2, size // 20)
    glyph_width = 11 * scale
    glyph_height = 7 * scale
    start_x = (size - glyph_width) // 2
    start_y = (size - glyph_height) // 2
    scanlines = bytearray()
    for y in range(size):
        scanlines.append(0)
        for x in range(size):
            color = background
            gx = (x - start_x) // scale
            gy = (y - start_y) // scale
            if 0 <= gx < 11 and 0 <= gy < 7:
                if gx < 5 and rows[0][gy][gx] == "1":
                    color = accent
                elif gx >= 6 and rows[1][gy][gx - 6] == "1":
                    color = accent
            scanlines.extend(color)
    header = struct.pack(">2I5B", size, size, 8, 2, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + _png_chunk(b"IHDR", header) + _png_chunk(b"IDAT", zlib.compress(bytes(scanlines), 9)) + _png_chunk(b"IEND", b"")


def assemble(name: str, platform: str, manifest: str) -> Path:
    destination = OUTPUT / name
    destination.mkdir(parents=True, exist_ok=True)
    for asset in ASSETS:
        shutil.copyfile(COMMON / asset, destination / asset)
    (destination / "platform.js").write_text(
        "window.REMOTEFIX_PLATFORM = " + json.dumps(platform, ensure_ascii=False) + ";\n",
        encoding="utf-8",
    )
    shutil.copyfile(ROOT / name / manifest, destination / manifest)
    (destination / "icon.png").write_bytes(_icon_png(80))
    if name == "webos":
        (destination / "largeIcon.png").write_bytes(_icon_png(130))
        metadata = json.loads((destination / manifest).read_text(encoding="utf-8"))
        for icon_key in ("icon", "largeIcon"):
            icon_path = destination / metadata[icon_key]
            if not icon_path.is_file():
                raise ValueError(f"Missing webOS {icon_key}: {icon_path}")
    else:
        widget_ns = "http://www.w3.org/ns/widgets"
        tizen_ns = "http://tizen.org/ns/widgets"
        tree = ET.parse(destination / manifest)
        widget = tree.getroot()
        application = widget.find(f"{{{tizen_ns}}}application")
        content = widget.find(f"{{{widget_ns}}}content")
        icon = widget.find(f"{{{widget_ns}}}icon")
        if application is None or content is None or icon is None:
            raise ValueError("Tizen config.xml must define application, content, and icon")
        package_id = application.attrib.get("package", "")
        app_id = application.attrib.get("id", "")
        if not re.fullmatch(r"[A-Za-z0-9]{10}", package_id) or not app_id.startswith(package_id + "."):
            raise ValueError("Tizen application ID must be <10-character package ID>.<project name>")
        for key, entry in (("content", content), ("icon", icon)):
            asset = destination / entry.attrib.get("src", "")
            if not asset.is_file():
                raise ValueError(f"Missing Tizen {key} asset: {asset}")
    return destination


def main() -> None:
    tizen = assemble("tizen", "Samsung Tizen TV", "config.xml")
    webos = assemble("webos", "LG webOS TV", "appinfo.json")
    print("Tizen app source:", tizen)
    print("webOS app source:", webos)
    print("Package with Samsung Tizen Studio and webOS TV CLI after installing their SDKs.")


if __name__ == "__main__":
    main()
