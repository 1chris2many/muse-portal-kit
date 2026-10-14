"""Historical offline text-only model, not version-0.4.2 production behavior.

The caller supplies a verified auth result to model the adapter boundary. No
network, device, credentials, message transport, file writes or real audio.
Synthetic five-minute expiry and abstract photo/widget inputs differ from the
production Kotlin implementation. See ADDRESSABLE_DELIVERY.md for the scope.
"""
from dataclasses import dataclass
import hashlib
import json
import re
import uuid


class Rejected(ValueError):
    pass


@dataclass
class Pending:
    input_id: str
    kind: str
    expires: int
    upload_sha256: str | None = None
    allow_display: bool = True


class DeliveryModel:
    FIELDS = {"version", "delivery_id", "device_id", "generation", "request_id",
              "input_message_id", "conversation_id", "content"}

    def __init__(self, device="portal-fixture", conversation="main-fixture"):
        self.device = device
        self.conversation = conversation
        self.generation = uuid.uuid4().hex
        self.now = 0
        self.foreground = True
        self.recording = False
        self.speech_enabled = True
        self.pending = {}
        self.seen_requests = set()
        self.receipts = {}

    def local_ack(self, request_id, input_id, kind, upload_sha256=None, *, allow_display=True):
        """Only a trusted local send+ACK may call this; never a remote action."""
        if (not self.foreground or type(allow_display) is not bool or kind not in ("voice", "text", "photo", "widget")
                or not request_id or not input_id or request_id in self.seen_requests):
            raise Rejected("invalid local request")
        if upload_sha256 is not None and (kind != "voice" or not isinstance(upload_sha256, str)
                                         or not re.fullmatch(r"[a-f0-9]{64}", upload_sha256)):
            raise Rejected("invalid upload fingerprint")
        if len(self.seen_requests) >= 100:
            raise Rejected("generation capacity; reset required")
        self.seen_requests.add(request_id)
        self.pending[request_id] = Pending(input_id, kind, self.now + 300, upload_sha256, allow_display)

    def resolve_upload(self, device_id, conversation_id, upload_sha256, *, authenticated=False):
        """Candidate only: byte preservation in Muse's upload pipeline is unverified.

        Hash must be computed from the actual input file seen by the composer,
        never copied from a pending-ID listing. No audio or transcript is returned.
        """
        if not authenticated or not self.foreground:
            raise Rejected("unauthenticated or background")
        if device_id != self.device or conversation_id != self.conversation:
            raise Rejected("wrong upload destination")
        if not isinstance(upload_sha256, str) or not re.fullmatch(r"[a-f0-9]{64}", upload_sha256):
            raise Rejected("invalid upload fingerprint")
        matches = [(key, item) for key, item in self.pending.items()
                   if item.kind == "voice" and item.expires > self.now
                   and item.upload_sha256 == upload_sha256]
        if len(matches) != 1:
            raise Rejected("upload has no unique live binding")
        key, request = matches[0]
        return {"device_id": self.device, "conversation_id": self.conversation,
                "generation": self.generation, "request_id": key,
                "input_message_id": request.input_id}

    def revoke(self, *, foreground=True, conversation=None):
        self.generation = uuid.uuid4().hex
        self.pending.clear()
        self.seen_requests.clear()
        self.receipts.clear()
        self.foreground = foreground
        self.speech_enabled = True
        if conversation is not None:
            self.conversation = conversation

    def stop_audio(self):
        self.speech_enabled = False

    def deliver(self, envelope, *, authenticated=False):
        if not authenticated:
            raise Rejected("unauthenticated")
        if not isinstance(envelope, dict) or set(envelope) != self.FIELDS:
            raise Rejected("invalid envelope fields")
        if type(envelope["version"]) is not int or envelope["version"] != 1:
            raise Rejected("unsupported version")
        for key in ("delivery_id", "device_id", "request_id", "input_message_id", "conversation_id"):
            if not isinstance(envelope[key], str) or not 1 <= len(envelope[key]) <= 256:
                raise Rejected("invalid identifier")
        if (envelope["device_id"] != self.device or
                envelope["conversation_id"] != self.conversation or
                not isinstance(envelope["generation"], str) or envelope["generation"] != self.generation):
            raise Rejected("wrong destination or generation")
        if not self.foreground:
            raise Rejected("background")
        content = envelope["content"]
        if not isinstance(content, list) or len(content) != 1:
            raise Rejected("unsupported content")
        part = content[0]
        if (not isinstance(part, dict) or set(part) != {"type", "text"} or
                part["type"] != "text" or not isinstance(part["text"], str) or
                not part["text"].strip() or len(part["text"].encode()) > 16384):
            raise Rejected("unsupported content")
        digest = hashlib.sha256(json.dumps(envelope, sort_keys=True).encode()).hexdigest()
        delivery_id = envelope["delivery_id"]
        previous = self.receipts.get(delivery_id)
        if previous:
            if previous != digest:
                raise Rejected("changed retry")
            return {"state": "duplicate", "display": False, "speak": False}
        request = self.pending.get(envelope["request_id"])
        if request is None or request.input_id != envelope["input_message_id"]:
            raise Rejected("unknown or mismatched input")
        if self.now >= request.expires:
            raise Rejected("expired")
        if self.recording:
            raise Rejected("recording; retry same delivery when ready")
        self.receipts[delivery_id] = digest
        del self.pending[envelope["request_id"]]
        return {"state": "accepted", "display": request.allow_display,
                "speak": request.kind == "voice" and self.speech_enabled}
