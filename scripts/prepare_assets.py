"""Fetch pinned upstream binaries. Python 3.10+, standard library only."""
from pathlib import Path
import hashlib
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT / ".downloads"
MODEL = "sherpa-onnx-x-asr-160ms-streaming-zipformer-transducer-zh-en-int8-2026-06-05"
PACKAGES = [
    ("sherpa.aar", "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar",
     "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"),
    ("streaming-160ms.tar.bz2", f"https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/{MODEL}.tar.bz2",
     "bdf2c0f2474d34bf479871b0f298c74a6f6bf15f24a492dc5401aa3041811915"),
]


def prepare():
    CACHE.mkdir(exist_ok=True)
    for name, url, expected in PACKAGES:
        target = CACHE / name
        if not target.exists():
            print(f"Downloading {name}...", flush=True)
            temporary = target.with_suffix(".part")
            with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as out:
                shutil.copyfileobj(response, out)
            temporary.replace(target)
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        if actual != expected:
            raise RuntimeError(f"SHA256 mismatch for {target}; remove this cached file and retry.")
        print(f"Verified {name}", flush=True)
    libs = ROOT / "app/libs"
    libs.mkdir(parents=True, exist_ok=True)
    shutil.copy2(CACHE / "sherpa.aar", libs / "sherpa-onnx-2.0.1.aar")
    assets = ROOT / "app/src/main/assets/kws"
    assets.mkdir(parents=True, exist_ok=True)
    files = {"tokens.txt": "tokens.txt", "encoder.int8.onnx": "encoder.onnx",
             "decoder.onnx": "decoder.onnx", "joiner.int8.onnx": "joiner.onnx"}
    with tarfile.open(CACHE / "streaming-160ms.tar.bz2", "r:bz2") as archive:
        for source, destination in files.items():
            # Extract explicit known files only; never trust archive paths.
            member = archive.getmember(f"{MODEL}/{source}")
            if not member.isfile():
                raise RuntimeError(f"Expected a regular file: {source}")
            with archive.extractfile(member) as incoming, (assets / destination).open("wb") as out:
                shutil.copyfileobj(incoming, out)
    print("Offline recognition assets ready.")


if __name__ == "__main__":
    prepare()
