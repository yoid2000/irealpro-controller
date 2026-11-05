"""Check bundled assets and Android 16-compatible native ELF load alignment."""
from pathlib import Path
import argparse
import struct
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    checked = 0
    with zipfile.ZipFile(args.apk) as apk:
        names = set(apk.namelist())
        for model in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
            assert f"assets/kws/{model}" in names, f"Missing offline asset: {model}"
        for name in names:
            if not name.startswith("lib/") or not name.endswith(".so"):
                continue
            elf = apk.read(name)
            assert elf[:6] == b"\x7fELF\x02\x01", f"Expected little-endian ELF64: {name}"
            phoff = struct.unpack_from("<Q", elf, 32)[0]
            phsize, count = struct.unpack_from("<HH", elf, 54)
            for index in range(count):
                offset = phoff + index * phsize
                segment_type = struct.unpack_from("<I", elf, offset)[0]
                if segment_type == 1:
                    alignment = struct.unpack_from("<Q", elf, offset + 48)[0]
                    assert alignment >= 16384, f"4 KB-only native load segment: {name}"
            checked += 1
    assert checked > 0
    print(f"PASS: offline assets bundled; {checked} native libraries support 16 KB ELF load alignment.")


if __name__ == "__main__":
    main()
