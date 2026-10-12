#!/usr/bin/env python3
"""Fetch a hash-pinned public Vosk model for an opt-in offline Nova prototype.

No user audio, credentials, device access or installation. Output is ignored build input.
Model license: Apache-2.0 per https://alphacephei.com/vosk/models .
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import tempfile
import urllib.request
import zipfile

URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
SHA256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
ROOT = "vosk-model-small-en-us-0.15/"
DESTINATION = Path(__file__).resolve().parents[1] / "app/app/src/main/assets/wake-vosk"


def prepare(archive: Path, destination: Path = DESTINATION):
    if archive.stat().st_size > 50_000_000:
        raise ValueError("Archive exceeded model size bound")
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA256:
        raise ValueError("Vosk archive checksum mismatch; refusing to extract")
    if destination.exists():
        raise FileExistsError(f"Refusing to overwrite existing model: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="wake-model-", dir=destination.parent) as temp:
        staged = Path(temp) / "model"
        staged.mkdir()
        with zipfile.ZipFile(archive) as z:
            if sum(e.file_size for e in z.infolist()) > 100_000_000:
                raise ValueError("Unexpected model size")
            for entry in z.infolist():
                if not entry.filename.startswith(ROOT):
                    raise ValueError("Unexpected archive root")
                relative = Path(entry.filename[len(ROOT):])
                if relative.is_absolute() or ".." in relative.parts or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError("Unsafe archive path")
                if entry.is_dir():
                    continue
                target = staged / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(entry) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        if not (staged / "am/final.mdl").is_file():
            raise ValueError("Acoustic model missing")
        # Preserve the model README/copyright and include Apache's license text in the APK.
        (staged / "LICENSE").write_text((Path(__file__).resolve().parents[1] / "LICENSE").read_text())
        (staged / "MODEL-NOTICE.txt").write_text(
            "Vosk small English US model 0.15\nCopyright 2020 Alpha Cephei Inc\n"
            "Apache License 2.0. Model files are unmodified.\n"
            "Source and license listing: https://alphacephei.com/vosk/models\n"
        )
        (staged / "archive-sha256.txt").write_text(SHA256 + "\n")
        staged.rename(destination)
    print(f"Prepared verified Vosk model at {destination}; no device was changed")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already-downloaded archive; still verify its checksum")
    args = parser.parse_args()
    if args.archive:
        prepare(args.archive)
    else:
        with tempfile.TemporaryDirectory(prefix="portal-wake-download-") as temp:
            archive = Path(temp) / "model.zip"
            with urllib.request.urlopen(URL, timeout=60) as source, archive.open("wb") as target:
                remaining = 50_000_000
                while chunk := source.read(min(1024 * 1024, remaining + 1)):
                    remaining -= len(chunk)
                    if remaining < 0:
                        raise ValueError("Download exceeded model size bound")
                    target.write(chunk)
            prepare(archive)
