"""One-time BLE provisioning for a USB-connected Portal, not a Muse host.

Uses the official SDK's encrypted setup protocol. No shell/file commands are
exposed to Muse. Credentials only transit RAM and Portal app-private staging;
the Android app validates, encrypts, and removes that staging file on startup.
"""
from __future__ import annotations

import argparse
from collections import deque
from dataclasses import asdict
import getpass
import json
import logging
from pathlib import Path
import queue
import re
import subprocess
import sys
import time

import CoreBluetooth as CB
from Foundation import NSObject, NSDate, NSRunLoop

SERVICE = "7fdd3d1c-38ea-46cf-8b46-314ecf5f240c"
RX = "4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01"
TX = "d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c"
PACKAGE = "com.muse.gadget.portal"


class SetupDiagnostics(logging.Handler):
    """Only protocol state names, never message bodies or exception details."""
    def emit(self, record):
        args = record.args if isinstance(record.args, tuple) else ()
        if record.msg == "RX action: %s%s" and args:
            action = args[0] if args[0] in {"get_device_info", "pairing_client_hello", "pairing_encrypted", "pairing_client_finished", "wifi_scan", "provision_v2"} else "unknown"
            print("Setup received: " + action, flush=True)
        elif record.msg in {"TX %s", "TX %s (encrypted #%s)"} and args:
            kind = args[0] if args[0] in {"device_info", "pairing_ready", "wifi_scan_result"} else "protocol response"
            print("Setup queued: " + kind, flush=True)
        elif record.msg == "TX status (encrypted #%s): %s" and len(args) == 2:
            status = args[1]
            if isinstance(status, str) and re.fullmatch(r"[a-z_]{1,64}", status):
                print("Setup status: " + status, flush=True)
        elif record.exc_info:
            print("Setup internal error type: " + record.exc_info[0].__name__, flush=True)


class Peripheral(NSObject):
    def peripheralManagerDidUpdateState_(self, manager):
        state = manager.state()
        print(f"Mac Bluetooth state: {state}", flush=True)
        if state == CB.CBManagerStatePoweredOn:
            self.tx = CB.CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
                CB.CBUUID.UUIDWithString_(TX), CB.CBCharacteristicPropertyRead | CB.CBCharacteristicPropertyNotify,
                None, CB.CBAttributePermissionsReadable)
            self.rx = CB.CBMutableCharacteristic.alloc().initWithType_properties_value_permissions_(
                CB.CBUUID.UUIDWithString_(RX),
                CB.CBCharacteristicPropertyWrite | CB.CBCharacteristicPropertyWriteWithoutResponse,
                None, CB.CBAttributePermissionsWriteable)
            service = CB.CBMutableService.alloc().initWithType_primary_(CB.CBUUID.UUIDWithString_(SERVICE), True)
            service.setCharacteristics_([self.rx, self.tx])
            manager.addService_(service)
        elif state in (CB.CBManagerStateUnauthorized, CB.CBManagerStateUnsupported):
            self.failure = "Mac Bluetooth permission or peripheral support unavailable."

    def peripheralManager_didAddService_error_(self, manager, service, error):
        if error:
            self.failure = "Mac could not publish the pairing service."
            return
        advertisement = {CB.CBAdvertisementDataLocalNameKey: self.device_name}
        if not self.name_only:
            advertisement[CB.CBAdvertisementDataServiceUUIDsKey] = [CB.CBUUID.UUIDWithString_(SERVICE)]
        manager.startAdvertising_(advertisement)

    def peripheralManagerDidStartAdvertising_error_(self, manager, error):
        if error:
            self.failure = "Mac could not start pairing advertisement."
        else:
            print(f"PAIRING READY: {self.device_name} (10-minute window)", flush=True)

    def peripheralManager_central_didSubscribeToCharacteristic_(self, manager, central, characteristic):
        if self.central is not None and self.central.identifier() != central.identifier():
            return
        self.central = central
        self.subscribed = True
        self.payload_size = min(512, int(central.maximumUpdateValueLength()))
        print(f"Muse phone subscribed; CoreBluetooth notification limit {int(central.maximumUpdateValueLength())} bytes.", flush=True)

    def peripheralManager_central_didUnsubscribeFromCharacteristic_(self, manager, central, characteristic):
        if self.central is not None and self.central.identifier() == central.identifier():
            self.central = None
            self.subscribed = False
            self.pending.clear()
            while not self.outgoing.empty():
                try:
                    self.outgoing.get_nowait()
                except queue.Empty:
                    break
            if self.controller:
                self.controller.on_disconnect()

    def peripheralManager_didReceiveReadRequest_(self, manager, request):
        allowed = (request.characteristic().UUID() == self.tx.UUID() and request.offset() == 0 and
                   (self.central is None or self.central.identifier() == request.central().identifier()))
        if allowed:
            self.central = request.central()
            request.setValue_(b"")
        print(f"Pairing characteristic read: {'accepted' if allowed else 'rejected'}", flush=True)
        manager.respondToRequest_withResult_(request, CB.CBATTErrorSuccess if allowed else CB.CBATTErrorReadNotPermitted)

    def peripheralManager_didReceiveWriteRequests_(self, manager, requests):
        for request in requests:
            allowed = ((self.central is None or request.central().identifier() == self.central.identifier()) and
                       request.characteristic().UUID() == self.rx.UUID() and request.offset() == 0)
            print(f"Pairing write: {len(request.value() or b'')} bytes, {'accepted' if allowed else 'rejected'}", flush=True)
            manager.respondToRequest_withResult_(request, CB.CBATTErrorSuccess if allowed else CB.CBATTErrorWriteNotPermitted)
            if allowed and self.controller:
                self.central = request.central()
                self.controller.on_write(bytes(request.value() or b""))

    def peripheralManagerIsReadyToUpdateSubscribers_(self, manager):
        self.drain()

    def drain(self):
        while True:
            try:
                self.pending.extend(self.outgoing.get_nowait())
            except queue.Empty:
                break
        while self.central is not None and self.subscribed and self.pending:
            if time.monotonic() < self.next_notify_at:
                break
            if not self.manager.updateValue_forCharacteristic_onSubscribedCentrals_(
                    self.pending[0], self.tx, [self.central]):
                break
            self.pending.popleft()
            self.next_notify_at = time.monotonic() + 0.05


class Transport:
    def __init__(self, delegate):
        self.delegate = delegate

    def mtu(self):
        return self.delegate.payload_size + 3

    def send_packets(self, packets):
        self.delegate.outgoing.put(packets)

    def disconnect(self, delay):
        self.delegate.finish_at = time.monotonic() + max(1, delay)


class Network:
    def __init__(self, ssid):
        self.ssid = ssid

    def is_online(self):
        # The account API validation below is the actual connectivity check.
        return True

    def current_connection_entry(self):
        return {"ssid": self.ssid, "rssi": -40, "secure": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-source", type=Path, required=True)
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--name-only", action="store_true", help="Discovery compatibility test; GATT service stays unchanged")
    parser.add_argument("--probe", action="store_true", help="Check CoreBluetooth only; no credentials or advertising")
    args = parser.parse_args()
    sys.path.insert(0, str(args.sdk_source / "linux" / "src"))
    from musegadget.ble_setup import SetupController, ProvisionFailed
    from musegadget.pairing import PairingSession
    from musegadget.identity import Identity
    from musegadget.muse_api import fetch_vms_with_status

    # Do not log arbitrary remote error bodies or credentials.
    logging.basicConfig(level=logging.CRITICAL)
    setup_log = logging.getLogger("musegadget.ble_setup")
    setup_log.setLevel(logging.INFO)
    setup_log.propagate = False
    setup_log.addHandler(SetupDiagnostics())
    adb = [str(args.adb), "-s", args.serial]
    identity_data = json.loads(subprocess.check_output(adb + ["shell", "run-as", PACKAGE,
                                      "cat", "no_backup/identity-public.json"], timeout=10))
    mac = identity_data["mac"]
    if not re.fullmatch(r"[0-9a-f]{2}(:[0-9a-f]{2}){5}", mac):
        raise ValueError("Invalid public Portal identity")
    identity = Identity(mac)
    if identity.node_id != identity_data["node_id"]:
        raise ValueError("Portal identity mismatch")
    # Read only the active SSID, not saved networks or Wi-Fi credentials.
    ssid_line = subprocess.check_output(adb + ["shell",
        "dumpsys wifi | grep mWifiInfo"],
        timeout=10).decode().strip()
    ssid_match = re.search(r"SSID: ([^,]*)", ssid_line)
    ssid = ssid_match.group(1).strip('"') if ssid_match else ""
    if not ssid or ssid == "<unknown ssid>" or len(ssid.encode()) > 32:
        raise ValueError("Portal active Wi-Fi name could not be verified")
    token = "" if args.probe else getpass.getpass("Muse SDK token (hidden; held only in memory): ").strip()
    if not args.probe and (not token.startswith("mgst_") or not 12 <= len(token) <= 512):
        raise ValueError("Invalid SDK token format")

    delegate = Peripheral.alloc().init()
    delegate.device_name = identity.ble_name
    delegate.name_only = args.name_only
    delegate.central = None
    delegate.subscribed = False
    delegate.payload_size = 20
    delegate.pending = deque()
    delegate.next_notify_at = 0.0
    delegate.outgoing = queue.Queue()
    delegate.failure = None
    delegate.finish_at = time.monotonic() + (15 if args.probe else 600)
    delegate.controller = None
    completed = False

    def provision(credentials, commit):
        if credentials.api_url_v2.rstrip("/") not in ("", "https://api.muse.ai"):
            raise ProvisionFailed("error_auth_failed")
        if credentials.noise_host not in ("", "hatch.metaaivm.com"):
            raise ProvisionFailed("error_auth_failed")
        vms, status = fetch_vms_with_status(credentials.access_token, "https://api.muse.ai")
        if status != 200 or not (len(vms) == 1 or sum(bool(v["is_default"]) for v in vms) == 1):
            raise ProvisionFailed("error_auth_failed")
        payload = json.dumps(asdict(credentials), separators=(",", ":")).encode()
        def save():
            try:
                subprocess.run(adb + ["shell", "-T", "run-as", PACKAGE, "sh", "-c",
                    "'umask 077; cat > no_backup/pairing-import'"], input=payload,
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15, check=True)
                return True
            except (subprocess.SubprocessError, OSError):
                return False
        if not commit(save):
            raise ProvisionFailed("error_auth_failed")

    def done():
        nonlocal completed
        completed = True
        delegate.finish_at = time.monotonic() + 3
        print("Phone authorization received; transferring to Portal for validation.", flush=True)

    if not args.probe:
        pairing = PairingSession(node_id=identity.node_id, device_id=identity.device_id,
                                 mac=identity.mac, firmware_version="0.2.0", sdk_token=token)
        delegate.controller = SetupController(pairing=pairing, identity=identity, version="0.2.0",
            transport=Transport(delegate), network=Network(ssid), provision=provision, on_complete=done)
        delegate.controller.start()
    # Probe must not publish a service or start an advertisement.
    if args.probe:
        delegate.failure = "Probe requires the normal Bluetooth callback; no account data involved."
        # Use a minimal delegate below to avoid advertising during a probe.
        probe = Probe.alloc().init()
        delegate.manager = CB.CBPeripheralManager.alloc().initWithDelegate_queue_options_(probe, None, None)
    else:
        delegate.manager = CB.CBPeripheralManager.alloc().initWithDelegate_queue_options_(delegate, None, None)
    try:
        while time.monotonic() < delegate.finish_at:
            NSRunLoop.currentRunLoop().runUntilDate_(NSDate.dateWithTimeIntervalSinceNow_(0.1))
            if args.probe:
                if getattr(probe, "received", False):
                    break
            else:
                delegate.drain()
                if delegate.failure:
                    print(delegate.failure, flush=True)
                    break
    except KeyboardInterrupt:
        print("Pairing window stopped by operator.", flush=True)
    finally:
        delegate.manager.stopAdvertising()
        delegate.manager.removeAllServices()
        if delegate.controller:
            delegate.controller.stop()
    if completed:
        subprocess.run(adb + ["shell", "am", "force-stop", PACKAGE], check=True, timeout=10)
        subprocess.run(adb + ["shell", "am", "start", "-n", PACKAGE + "/.ui.PortalActivity"], check=True, timeout=10)
        print("Pairing helper stopped. Portal is now responsible for its own connection.", flush=True)
    elif not args.probe:
        print("Pairing ended without completion; no permanent relay was started.", flush=True)


class Probe(NSObject):
    def peripheralManagerDidUpdateState_(self, manager):
        print(f"CoreBluetooth peripheral state: {manager.state()} (5 = ready)", flush=True)
        self.received = True


if __name__ == "__main__":
    main()
