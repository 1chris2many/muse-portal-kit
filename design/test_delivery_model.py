"""Synthetic local data only. Never sends a message to Muse or a device."""
import unittest
import hashlib
from delivery_model import DeliveryModel, Rejected


class DeliveryContractTests(unittest.TestCase):
    def setUp(self):
        self.model = DeliveryModel()
        self.model.local_ack("local-a", "native-a", "voice")
        self.envelope = dict(version=1, delivery_id="delivery-a", device_id="portal-fixture",
                             generation=self.model.generation, request_id="local-a", input_message_id="native-a",
                             conversation_id="main-fixture", content=[dict(type="text", text="Offline fixture")])

    def send(self, **changes):
        return self.model.deliver(dict(self.envelope, **changes), authenticated=True)

    def test_voice_reply_is_visible_and_speaks(self):
        self.assertEqual(self.send(), dict(state="accepted", display=True, speak=True))

    def test_identical_retry_never_redisplays_or_speaks(self):
        self.send()
        self.assertEqual(self.send(), dict(state="duplicate", display=False, speak=False))

    def test_changed_retry_rejected(self):
        self.send()
        with self.assertRaises(Rejected):
            self.send(content=[dict(type="text", text="Different content")])

    def test_new_delivery_id_cannot_answer_consumed_request(self):
        self.send()
        with self.assertRaises(Rejected):
            self.send(delivery_id="new-id")

    def test_auth_required(self):
        with self.assertRaises(Rejected):
            self.model.deliver(self.envelope)

    def test_wrong_device_rejected(self):
        with self.assertRaises(Rejected):
            self.send(device_id="phone")

    def test_wrong_conversation_rejected(self):
        with self.assertRaises(Rejected):
            self.send(conversation_id="another-thread")

    def test_wrong_generation_rejected(self):
        with self.assertRaises(Rejected):
            self.send(generation=0)

    def test_unknown_handle_rejected(self):
        with self.assertRaises(Rejected):
            self.send(request_id="mobile-input")

    def test_native_ack_must_match(self):
        with self.assertRaises(Rejected):
            self.send(input_message_id="other-input")

    def test_no_native_ack_no_delivery(self):
        model = DeliveryModel()
        with self.assertRaises(Rejected):
            model.deliver(self.envelope, authenticated=True)

    def test_expiry_boundary_rejected(self):
        self.model.now = 300
        with self.assertRaises(Rejected):
            self.send()

    def test_clear_revokes_not_only_pixels(self):
        self.model.revoke()
        with self.assertRaises(Rejected):
            self.send()
        with self.assertRaises(Rejected):
            self.send(generation=self.model.generation)

    def test_background_rejects(self):
        self.model.revoke(foreground=False)
        with self.assertRaises(Rejected):
            self.send(generation=self.model.generation)

    def test_reconnect_revokes_late_delivery(self):
        self.model.revoke()
        self.model.local_ack("new-local", "new-native", "voice")
        with self.assertRaises(Rejected):
            self.send()

    def test_restart_gets_new_generation_even_with_same_request_ids(self):
        restarted = DeliveryModel()
        restarted.local_ack("local-a", "native-a", "voice")
        with self.assertRaises(Rejected):
            restarted.deliver(self.envelope, authenticated=True)

    def test_context_change_revokes(self):
        self.model.revoke(conversation="selected-other")
        with self.assertRaises(Rejected):
            self.send(generation=self.model.generation, conversation_id="selected-other")

    def test_typed_photo_and_widget_inputs_never_speak(self):
        for kind in ("text", "photo", "widget"):
            with self.subTest(kind=kind):
                self.model.local_ack(kind, "native-"+kind, kind)
                result = self.send(request_id=kind, input_message_id="native-"+kind, delivery_id=kind)
                self.assertTrue(result["display"])
                self.assertFalse(result["speak"])

    def test_remote_speech_flag_cannot_override_input(self):
        with self.assertRaises(Rejected):
            self.send(speak=True)

    def test_multiple_pending_requests_keep_exact_binding(self):
        self.model.local_ack("local-b", "native-b", "text")
        with self.assertRaises(Rejected):
            self.send(request_id="local-b")
        result = self.send(request_id="local-b", input_message_id="native-b")
        self.assertFalse(result["speak"])
        self.assertTrue(self.send(delivery_id="voice-delivery")["speak"])

    def test_stop_audio_keeps_visual_reply_but_prevents_speech(self):
        self.model.stop_audio()
        result = self.send()
        self.assertTrue(result["display"])
        self.assertFalse(result["speak"])

    def test_recording_does_not_consume_or_enqueue_response(self):
        self.model.recording = True
        with self.assertRaises(Rejected):
            self.send()
        self.model.recording = False
        self.assertTrue(self.send()["speak"])

    def test_unknown_rich_content_fails_without_consuming_request(self):
        for part in [dict(type="html", html="<script>fixture</script>"), dict(type="image", url="http://localhost")]:
            with self.assertRaises(Rejected):
                self.send(content=[part])
        self.assertTrue(self.send()["display"])

    def test_empty_and_oversized_text_rejected(self):
        for text in ("", " ", "x"*16385):
            with self.assertRaises(Rejected):
                self.send(content=[dict(type="text", text=text)])

    def test_unexpected_fields_and_invalid_types_rejected(self):
        for changes in [dict(version=True), dict(generation=True), dict(delivery_id=[]), dict(content="text"), dict(sender="someone-else")]:
            with self.assertRaises(Rejected):
                self.send(**changes)

    def test_request_cannot_be_reregistered_after_completion(self):
        self.send()
        with self.assertRaises(Rejected):
            self.model.local_ack("local-a", "native-a", "voice")


class UploadBindingTests(unittest.TestCase):
    def setUp(self):
        self.model = DeliveryModel()
        self.digest = hashlib.sha256(b"offline fake audio bytes, never uploaded").hexdigest()
        self.model.local_ack("local-upload", "native-upload", "voice", self.digest)

    def resolve(self, **changes):
        args = dict(device_id="portal-fixture", conversation_id="main-fixture",
                    upload_sha256=self.digest, authenticated=True)
        args.update(changes)
        return self.model.resolve_upload(**args)

    def test_exact_bytes_bind_to_original_native_input(self):
        binding = self.resolve()
        self.assertEqual(binding["input_message_id"], "native-upload")
        result = self.model.deliver(dict(binding, version=1, delivery_id="bound-reply",
                                        content=[dict(type="text", text="Reply to that exact file")]),
                                    authenticated=True)
        self.assertTrue(result["speak"])

    def test_identical_pending_audio_rejects_ambiguous_binding(self):
        self.model.local_ack("another-upload", "another-native", "voice", self.digest)
        with self.assertRaises(Rejected):
            self.resolve()

    def test_changed_or_resampled_bytes_do_not_match(self):
        with self.assertRaises(Rejected):
            self.resolve(upload_sha256=hashlib.sha256(b"different encoding").hexdigest())

    def test_wrong_device_conversation_or_auth_rejected(self):
        for changes in [dict(device_id="phone"), dict(conversation_id="private-other"), dict(authenticated=False)]:
            with self.subTest(changes=changes), self.assertRaises(Rejected):
                self.resolve(**changes)

    def test_expired_audio_does_not_resolve(self):
        self.model.now = 300
        with self.assertRaises(Rejected):
            self.resolve()

    def test_background_or_clear_revokes_file_lookup(self):
        self.model.revoke(foreground=False)
        with self.assertRaises(Rejected):
            self.resolve()

    def test_consumed_audio_is_not_a_second_request(self):
        binding = self.resolve()
        self.model.deliver(dict(binding, version=1, delivery_id="bound-reply",
                                content=[dict(type="text", text="Fixture")]), authenticated=True)
        with self.assertRaises(Rejected):
            self.resolve()

    def test_malformed_digests_rejected(self):
        for digest in ("", "filename.wav", "a"*63, "G"*64, None):
            with self.subTest(digest=digest), self.assertRaises(Rejected):
                self.resolve(upload_sha256=digest)

    def test_photo_hash_cannot_authorize_voice(self):
        with self.assertRaises(Rejected):
            self.model.local_ack("photo", "native-photo", "photo", self.digest)


if __name__ == "__main__":
    unittest.main()
