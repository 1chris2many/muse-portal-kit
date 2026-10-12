"""Named Portal development actions; no caller-supplied shell, host path or APK.

The authenticated owner bridge supplies configuration, never the remote caller.
Builds run offline in a container, with only a disposable source snapshot mounted.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError


class PortalDevelopment:
    ACTIONS = ("portal.files", "portal.read", "portal.write", "portal.diff",
               "portal.build", "portal.status", "portal.install", "portal.rollback", "portal.camera",
               "portal.requests.read", "portal.deliver", "portal.delivery.status")

    def __init__(self, workspace, state, image, adb, serial, sdk, keystore):
        self.workspace=Path(workspace).resolve()
        self.state=Path(state).resolve()
        self.image=image
        self.adb=adb
        self.serial=serial
        self.sdk=Path(sdk)
        self.keystore=keystore
        self.lock=threading.Lock()
        self.workers={}
        self.state.mkdir(parents=True,exist_ok=True,mode=0o700)

    @staticmethod
    def digest(data):
        return hashlib.sha256(data).hexdigest()

    def run(self,args,timeout=60,cwd=None):
        result=subprocess.run(args,cwd=cwd,capture_output=True,text=True,timeout=timeout,shell=False)
        if result.returncode:
            raise ValueError("Operation failed: "+result.stderr[-1500:])
        return result.stdout

    def source(self,name):
        if not isinstance(name,str) or len(name)>240 or "\\" in name:
            raise ValueError("Invalid source path")
        p=Path(name)
        if p.is_absolute() or any(x.startswith(".") or x in ("..","") for x in p.parts):
            raise ValueError("Only project source paths are allowed")
        allowed=(name=="README.md" or (name.startswith(("app/app/src/main/","app/protocol/src/"))
                 and p.suffix in (".kt",".xml")))
        if not allowed: raise ValueError("Editable scope is Kotlin, XML resources and README only")
        target=self.workspace/p
        if any(x.is_symlink() for x in (target,*target.parents)):
            raise ValueError("Symlinks are not source files")
        if not target.resolve().is_relative_to(self.workspace): raise ValueError("Outside workspace")
        return target

    def record(self,job,data):
        path=self.state/job/"result.json"
        tmp=path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data));tmp.replace(path)

    def job(self,value):
        if not isinstance(value,str) or not re.fullmatch(r"[a-f0-9]{32}",value):raise ValueError("Invalid job ID")
        path=self.state/value
        if not path.is_dir() or path.is_symlink():raise ValueError("Unknown job")
        return path

    def dispatch(self,action,args):
        if action not in self.ACTIONS or not isinstance(args,dict):raise ValueError("Unknown Portal action")
        allowed={"portal.files":set(),"portal.read":{"path"},"portal.write":{"path","content","expected_sha256"},
                 "portal.diff":set(),"portal.build":set(),"portal.status":{"job_id"},
                 "portal.install":{"job_id"},"portal.rollback":set(),"portal.camera":set(),
                 "portal.requests.read":{"exact_text","upload_sha256"},
                 "portal.deliver":{"version","delivery_id","device_id","conversation_id","generation","request_id","input_message_id","content"},
                 "portal.delivery.status":{"delivery_id"}}
        if set(args)-allowed[action]:raise ValueError("Unexpected arguments")
        if action=="portal.camera":return self.camera()
        if action in ("portal.requests.read","portal.deliver","portal.delivery.status"):
            path={"portal.requests.read":"/requests/read","portal.deliver":"/deliver","portal.delivery.status":"/delivery/status"}[action]
            body=json.dumps(args,ensure_ascii=False,separators=(",",":")).encode()
            if len(body)>65536:raise ValueError("Portal delivery payload exceeds 64 KiB")
            return self.bridge_request(path,body)
        if action=="portal.files":
            names=self.run(["/usr/bin/git","ls-files","--cached","--others","--exclude-standard"],cwd=self.workspace).splitlines()
            files=[]
            for name in names:
                try:self.source(name);files.append(name)
                except ValueError:pass
            return {"files":sorted(set(files)),"workflow":"read → write with expected_sha256 → build → status → install; rollback restores previous APK"}
        if action=="portal.read":
            p=self.source(args.get("path"));data=p.read_bytes()
            if len(data)>256*1024:raise ValueError("Source file too large")
            return {"path":args["path"],"content":data.decode(),"sha256":self.digest(data)}
        if action=="portal.write":
            with self.lock:
                p=self.source(args.get("path"));text=args.get("content")
                if not isinstance(text,str) or len(text.encode())>256*1024:raise ValueError("Source must be UTF-8 text at most 256 KiB")
                actual=self.digest(p.read_bytes()) if p.exists() else None
                if "expected_sha256" not in args or args["expected_sha256"]!=actual:raise ValueError("Source changed; read it again before writing")
                p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text)
                return {"path":args["path"],"sha256":self.digest(text.encode())}
        if action=="portal.diff":
            return {"diff":self.run(["/usr/bin/git","diff","--no-ext-diff","--","app","README.md"],cwd=self.workspace)[-100000:]}
        if action=="portal.status":
            if "job_id" not in args:return {"jobs":[p.parent.name for p in sorted(self.state.glob("*/result.json"),key=lambda p:p.stat().st_mtime,reverse=True)[:10]]}
            job=self.job(args["job_id"]);result=json.loads((job/"result.json").read_text())
            log=job/"build.log"
            if log.exists():
                with log.open("rb") as f:f.seek(max(0,log.stat().st_size-12000));result["log_tail"]=f.read().decode(errors="replace")
            return result
        if action=="portal.build":
            with self.lock:
                for p in self.state.glob("*/result.json"):
                    r=json.loads(p.read_text())
                    if r.get("status")=="building":
                        worker=self.workers.get(p.parent.name)
                        if worker and worker.is_alive():raise ValueError("A Portal build is already running")
                        if re.fullmatch(r"[a-f0-9]{32}",p.parent.name):
                            subprocess.run(["/usr/local/bin/docker","rm","-f","portal-build-"+p.parent.name],capture_output=True,timeout=20)
                        self.record(p.parent.name,{"job_id":p.parent.name,"status":"failed","error":"Build worker interrupted; start a new build."})
                job=uuid.uuid4().hex;root=self.state/job;root.mkdir(mode=0o700)
                source=root/"source";source.mkdir()
                names=self.run(["/usr/bin/git","ls-files","--cached","--others","--exclude-standard"],cwd=self.workspace).splitlines()
                total=0
                for name in sorted(set(names)):
                    p=self.workspace/name
                    if p.is_symlink() or any(x.is_symlink() for x in p.parents):raise ValueError("Snapshot contains a symlink")
                    if not p.resolve().is_relative_to(self.workspace) or not p.is_file():continue
                    if any(part in (".git",".gradle","build",".idea") for part in Path(name).parts):continue
                    if Path(name).name in ("local.properties",".env") or Path(name).suffix in (".key",".pem",".keystore"):continue
                    total+=p.stat().st_size
                    if total>64*1024*1024:raise ValueError("Source snapshot exceeds 64 MiB")
                    dest=source/name;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(p,dest)
                self.record(job,{"job_id":job,"status":"building","started_at":time.time()})
                worker=threading.Thread(target=self.build,args=(job,),daemon=True)
                self.workers[job]=worker;worker.start()
                return {"job_id":job,"status":"building"}
        if action=="portal.install":
            with self.lock:
                job=self.job(args.get("job_id"));r=json.loads((job/"result.json").read_text())
                if r.get("status")!="passed":raise ValueError("Only a passed Portal build can be installed")
                apk=job/"portal.apk"
                if apk.is_symlink() or self.digest(apk.read_bytes())!=r.get("sha256"):raise ValueError("Build artifact changed")
                return self.install(apk,backup=True)
        if action=="portal.rollback":
            with self.lock:
                apk=self.state/"previous.apk"
                if not apk.is_file():raise ValueError("No prior APK has been saved")
                return self.install(apk,backup=False)

    def build(self,job):
        root=self.state/job
        name="portal-build-"+job
        try:
            argv=["/usr/local/bin/docker","run","--rm","--name",name,"--platform","linux/amd64",
                  "--network","none","--cap-drop=ALL","--security-opt=no-new-privileges",
                  "--pids-limit","512","--memory","4g","--cpus","4",
                  "--mount",f"type=bind,src={root/'source'},dst=/workspace",
                  self.image,"gradle","-p","app",":protocol:test",":app:testDebugUnitTest",":app:lintDebug",":app:assembleDebug",
                  "--offline","--no-daemon","--console=plain"]
            with (root/"build.log").open("w") as log:
                p=subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT,timeout=900,shell=False)
            if p.returncode:raise ValueError("Build or tests failed; see log_tail")
            apk=root/"source/app/app/build/outputs/apk/debug/app-debug.apk"
            # Reviewed Vosk ABIs plus the bundled public model make the debug APK ~89 MiB.
            if apk.is_symlink() or not apk.resolve().is_relative_to(root/"source") or apk.stat().st_size>128*1024*1024:raise ValueError("Invalid APK artifact")
            shutil.copyfile(apk,root/"portal.apk")
            self.record(job,{"job_id":job,"status":"passed","sha256":self.digest((root/"portal.apk").read_bytes()),"finished_at":time.time()})
        except Exception as e:
            subprocess.run(["/usr/local/bin/docker","rm","-f",name],capture_output=True,timeout=20)
            self.record(job,{"job_id":job,"status":"failed","error":str(e),"finished_at":time.time()})

    def install(self,apk,backup):
        tools=self.sdk/"build-tools/34.0.0"
        badging=self.run([str(tools/"aapt"),"dump","badging",str(apk)])
        if not re.search(r"^package: name='com\.muse\.gadget\.portal' ",badging):raise ValueError("APK package must be the Portal app")
        version=re.search(r"^package: .*\bversionCode='(\d+)'",badging)
        if not version or int(version.group(1))<2:raise ValueError("APK predates Portal display privacy repair; install/rollback refused")
        approved={"RECORD_AUDIO","CAMERA","INTERNET","ACCESS_NETWORK_STATE","BLUETOOTH","BLUETOOTH_ADMIN",
                  "BLUETOOTH_ADVERTISE","BLUETOOTH_CONNECT","FOREGROUND_SERVICE","RECEIVE_BOOT_COMPLETED"}
        permissions=re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'",badging)
        if any(p not in {"android.permission."+a for a in approved} for p in permissions):raise ValueError("APK requests additional permissions; owner review required")
        signed=self.state/"install.apk"
        # Signing occurs only here on the host. No keystore or ADB access enters the build container.
        env=dict(os.environ,JAVA_HOME="/opt/homebrew/opt/openjdk@17")
        subprocess.run([str(tools/"apksigner"),"sign","--ks",self.keystore,"--ks-key-alias","androiddebugkey",
                        "--ks-pass","pass:android","--key-pass","pass:android","--out",str(signed),str(apk)],
                       env=env,capture_output=True,check=True,timeout=60)
        adb=[self.adb,"-s",self.serial]
        if backup:
            location=self.run(adb+["shell","pm","path","com.muse.gadget.portal"]).strip()
            if not re.fullmatch(r"package:/data/app/[A-Za-z0-9_./=+~-]+/base\.apk",location):raise ValueError("Cannot locate current Portal APK")
            self.run(adb+["pull",location[len("package:"):],str(self.state/"previous.apk")])
        self.run(adb+["install","-r",str(signed)],timeout=90)
        self.run(adb+["shell","am","start","-n","com.muse.gadget.portal/.ui.PortalActivity"])
        return {"installed":True,"package":"com.muse.gadget.portal","rollback_available":(self.state/"previous.apk").exists(),
                "verification":"APK installed and activity started; confirm reconnect and changed behavior on the device."}

    def camera(self):
        return self.bridge_request("/camera",b"")

    def bridge_request(self,path,body):
        if path not in ("/camera","/requests/read","/deliver","/delivery/status"):
            raise ValueError("Unknown Portal bridge path")
        adb=[self.adb,"-s",self.serial]
        # Consume the app-private credential internally, never return or log it.
        config=json.loads(self.run(adb+["shell","run-as","com.muse.gadget.portal","cat","no_backup/portal-bridge.json"]))
        port=config.get("port");token=config.get("token")
        if type(port)!=int or not 1024<=port<=65535 or not isinstance(token,str) or not re.fullmatch("[a-f0-9]{64}",token):raise ValueError("Invalid Portal bridge configuration")
        forward=self.run(adb+["forward","tcp:0",f"tcp:{port}"]).strip()
        if not forward.isdigit():raise ValueError("Could not establish USB Portal forwarding")
        try:
            req=Request(f"http://127.0.0.1:{forward}{path}",data=body,
                headers={"Authorization":"Bearer "+token,"Content-Type":"application/json"})
            try:
                with urlopen(req,timeout=15) as response:
                    received=response.read(200001)
            except HTTPError as exc:
                # Only bounded machine error codes leave the adapter, never the request or credential.
                try:code=json.loads(exc.read(4096)).get("error","")
                except Exception:code=""
                if not isinstance(code,str) or not re.fullmatch(r"[a-z_]{1,80}",code):code="portal_request_rejected"
                raise ValueError(code) from None
            if len(received)>200000:raise ValueError("Portal response exceeded bound")
            result=json.loads(received)
            if not result.get("ok"):raise ValueError("Portal request failed")
            return result
        finally:self.run(adb+["forward","--remove",f"tcp:{forward}"])
