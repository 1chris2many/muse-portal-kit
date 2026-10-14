"""Offline packaging tests: no model download, credentials, device or network."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import prepare_wake_model as model


class ModelPackagingTest(unittest.TestCase):
    def archive(self, root, entries):
        archive = root / "model.zip"
        with zipfile.ZipFile(archive, "w") as z:
            for name, value in entries:
                z.writestr(name, value)
        return archive

    def run_prepare(self, archive, destination):
        with patch.object(model, "SHA256", hashlib.sha256(archive.read_bytes()).hexdigest()):
            model.prepare(archive, destination)

    def test_verified_atomic_output_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, [(model.ROOT + "am/final.mdl", b"test model")])
            dest = root / "out"
            self.run_prepare(archive, dest)
            self.assertEqual((dest / "am/final.mdl").read_bytes(), b"test model")
            self.assertIn("Apache License", (dest / "LICENSE").read_text())
            self.assertIn("Alpha Cephei", (dest / "MODEL-NOTICE.txt").read_text())
            self.assertEqual((dest / "archive-sha256.txt").read_text().strip(), hashlib.sha256(archive.read_bytes()).hexdigest())
            with self.assertRaises(FileExistsError):
                self.run_prepare(archive, dest)

    def test_bad_checksum_creates_no_output(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            archive = self.archive(root, [(model.ROOT + "am/final.mdl", b"untrusted")])
            with self.assertRaisesRegex(ValueError, "checksum"):
                model.prepare(archive, root / "out")
            self.assertFalse((root / "out").exists())

    def test_unsafe_paths_and_wrong_root_are_rejected_without_partial_output(self):
        for name in [model.ROOT + "../escaped", model.ROOT + "/absolute", "other/model"]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive = self.archive(root, [(name, b"bad")])
                with self.assertRaises(ValueError):
                    self.run_prepare(archive, root / "out")
                self.assertFalse((root / "out").exists())
                self.assertFalse((root / "escaped").exists())

    def test_symlinks_and_missing_acoustic_model_are_rejected(self):
        link = zipfile.ZipInfo(model.ROOT + "link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        for entry in [(link, b"../secret"), (model.ROOT + "README", b"no acoustic model")]:
            with self.subTest(entry=str(entry[0])), tempfile.TemporaryDirectory() as temp:
                root = Path(temp)
                archive = self.archive(root, [entry])
                with self.assertRaises(ValueError):
                    self.run_prepare(archive, root / "out")
                self.assertFalse((root / "out").exists())


if __name__ == "__main__":
    unittest.main()
