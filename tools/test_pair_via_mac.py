"""Offline helper tests; no Bluetooth manager, device, token, or network used."""
import contextlib
import io
import logging
import time
import unittest
from collections import deque
from queue import Queue

from pair_via_mac import Network, Peripheral, SetupDiagnostics


class HelperTests(unittest.TestCase):
    def test_real_network_name_is_preserved(self):
        self.assertEqual(Network("Example Wi-Fi").current_connection_entry(),
                         {"ssid": "Example Wi-Fi", "rssi": -40, "secure": False})

    def test_diagnostics_never_print_unknown_action_contents(self):
        output = io.StringIO()
        record = logging.LogRecord("test", logging.INFO, "", 0, "RX action: %s%s",
                                   ("PRIVATE_VALUE", " (encrypted)"), None)
        with contextlib.redirect_stdout(output):
            SetupDiagnostics().emit(record)
        self.assertEqual(output.getvalue(), "Setup received: unknown\n")

    def test_notification_backpressure_retains_packet(self):
        class Manager:
            accept = False
            def updateValue_forCharacteristic_onSubscribedCentrals_(self, data, tx, peers):
                return self.accept
        p = Peripheral.alloc().init()
        p.outgoing = Queue()
        p.pending = deque([b"example"])
        p.central = object()
        p.tx = object()
        p.subscribed = True
        p.next_notify_at = 0
        p.manager = Manager()
        p.drain()
        self.assertEqual(list(p.pending), [b"example"])
        p.manager.accept = True
        p.drain()
        self.assertEqual(list(p.pending), [])
        self.assertGreater(p.next_notify_at, time.monotonic())


if __name__ == "__main__":
    unittest.main()
