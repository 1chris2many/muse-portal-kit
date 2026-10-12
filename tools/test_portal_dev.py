import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
from portal_dev import PortalDevelopment

class PortalDevelopmentTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.work=self.root/"workspace";self.work.mkdir()
        self.dev=PortalDevelopment(self.work,self.root/"state","test-image","adb","test-device","sdk","unused-keystore")
    def tearDown(self):self.tmp.cleanup()
    def test_path_fence(self):
        for path in ("../secret","/etc/passwd",".env","app/build.gradle.kts","tools/portal_dev.py","app/app/src/main/../../secret.kt"):
            with self.assertRaises(ValueError):self.dev.source(path)
    def test_symlink_fence(self):
        parent=self.work/"app/app/src/main";parent.mkdir(parents=True)
        (parent/"escape").symlink_to(self.root,target_is_directory=True)
        with self.assertRaises(ValueError):self.dev.source("app/app/src/main/escape/file.kt")
    def test_optimistic_write(self):
        path="app/app/src/main/Test.kt"
        r=self.dev.dispatch("portal.write",{"path":path,"content":"first","expected_sha256":None})
        with self.assertRaises(ValueError):self.dev.dispatch("portal.write",{"path":path,"content":"stale","expected_sha256":None})
        r2=self.dev.dispatch("portal.write",{"path":path,"content":"second","expected_sha256":r["sha256"]})
        self.assertEqual(self.dev.dispatch("portal.read",{"path":path})["sha256"],r2["sha256"])
    def test_extra_args_rejected(self):
        with self.assertRaises(ValueError):self.dev.dispatch("portal.build",{"command":"arbitrary"})
    def test_install_requires_passed_build(self):
        job="a"*32;(self.dev.state/job).mkdir()
        self.dev.record(job,{"status":"failed"})
        with self.assertRaises(ValueError):self.dev.dispatch("portal.install",{"job_id":job})
    def test_arbitrary_job_path_rejected(self):
        with self.assertRaises(ValueError):self.dev.job("../../etc")
    def test_changed_artifact_rejected(self):
        job="a"*32;(self.dev.state/job).mkdir()
        (self.dev.state/job/"portal.apk").write_bytes(b"changed")
        self.dev.record(job,{"status":"passed","sha256":"wrong"})
        with self.assertRaises(ValueError):self.dev.dispatch("portal.install",{"job_id":job})

if __name__=="__main__":unittest.main()
