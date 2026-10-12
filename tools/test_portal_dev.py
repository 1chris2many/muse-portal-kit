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
    def test_camera_config_not_returned(self):
        with patch.object(self.dev,"run",return_value='{"port":12,"token":"invalid"}'):
            with self.assertRaisesRegex(ValueError,"Invalid Portal bridge configuration"):
                self.dev.camera()
    def test_pre_privacy_apk_cannot_install_or_rollback(self):
        with patch.object(self.dev,"run",return_value="package: name='com.muse.gadget.portal' versionCode='1'"):
            for backup in (True,False):
                with self.assertRaisesRegex(ValueError,"predates Portal display privacy repair"):
                    self.dev.install(self.root/"old.apk",backup)
    def test_build_is_offline_and_has_no_host_home_mount(self):
        job="b"*32;(self.dev.state/job/"source").mkdir(parents=True)
        with patch("portal_dev.subprocess.run") as run:
            run.return_value.returncode=1
            self.dev.build(job)
            argv=run.call_args_list[0].args[0]
            self.assertIn("--network",argv)
            self.assertEqual(argv[argv.index("--network")+1],"none")
            self.assertIn("--cap-drop=ALL",argv)
            self.assertNotIn("/var/run/docker.sock"," ".join(argv))
            self.assertNotIn("unused-keystore"," ".join(argv))

if __name__=="__main__":unittest.main()
