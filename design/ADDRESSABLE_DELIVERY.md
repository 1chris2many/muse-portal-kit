# Addressed Portal delivery draft

The Portal needs an explicitly addressed output channel alongside Muse's shared
conversation stream. The existing Muse authors the response; the Portal decides
whether that response may appear or speak. This design does not require copying
Muse, importing conversation history, or guessing which assistant message came
next. It is an offline proposal, not an installed feature.

## Current behavior

The Android app sends real local inputs directly through the gadget protocol and
receives their native acknowledgements. Shared assistant events often lack a
usable link to those inputs. `PortalReplyRouter` therefore hides unlinked events.
`LocalPortalBridge` currently exposes status and camera only. No delivery action
or rich-content receiver exists in the running bridge.

The official ESP32 SDK advertises `display.draw_url` for images. Android can
implement that pattern, but native command invocation must also be accessible to
the existing Muse runtime. It is not a widget or artifact export API.

## Request and response contract

1. A real local send creates a random request handle on the Portal. The local
   input type, selected conversation, connection generation and monotonic expiry
   are immutable. A microphone input does not identify the speaker as the owner.
2. The native ACK binds that handle to the server's input message ID. Pending
   handles cannot be created by a remote reply caller. A failed or ambiguous send
   grants no reply permission.
3. The existing Muse obtains the exact request through trusted input metadata or
   a narrowly scoped request-read tool. The result contains the canonical local
   input and its handle together. A worker may relay that envelope unchanged; it
   does not write the response. There must be no "choose the newest pending ID"
   or timestamp matching of a response composed for a different conversation.
4. Muse deliberately composes for that request and submits a delivery envelope.
   The interim transport can reuse the authenticated host bridge and USB loopback.
   The same envelope can later use a native gadget command. Existing direct Wi-Fi
   input and the same Muse identity remain intact.
5. The Portal validates the authenticated sender, target, generation, native input
   ID, conversation and live local handle before accepting content. Speech is
   derived from the recorded local input type, never a remote `speak:true` flag.
6. One final delivery completes a request. Identical retries return a receipt
   without redisplaying or speaking; changed content under the same delivery ID
   is a conflict. A fresh ID cannot produce a second final answer to that request.

Proposed tool names are `portal.requests.read`, `portal.deliver`, and
`portal.delivery.status`. They are **not registered or callable today**. An
endpoint supplied by a widget or by message text is never a routing authority.

### Delivery envelope

```json
{
  "version": 1,
  "delivery_id": "new-random-uuid",
  "device_id": "registered-portal-device",
  "generation": "current-connection-nonce",
  "request_id": "portal-local-random-handle",
  "input_message_id": "native-ack-message-id",
  "conversation_id": "existing-selected-conversation",
  "content": [{"type": "text", "text": "The authored reply"}]
}
```

The outer adapter authenticates the existing Muse connection. It must not accept
caller-selected host paths, device credentials, URLs for callbacks, or new sender
identities. The envelope does not cryptographically prove which model composed
the text: the current bridge credential does not distinguish a main-context
author from a worker. Preserve the existing exact-text relay rule and obtain
runtime evidence of that authorship path before activation.

### Receipts and failures

`accepted` means validated, not heard. Track rendering and playback separately as
`rendered`, `speech_started`, `speech_completed`, `speech_cancelled`, or `failed`.
On delivery timeout, query the same ID; never retry with a new ID. Runtime restart
invalidates pending requests rather than replaying old audio. No guarantee of
exactly-once physical playback is made across a crash.

Clear, idle expiry, backgrounding, conversation change and connection reset revoke
pending handles as well as clearing pixels and stopping audio. A held request
cannot repopulate a cleared screen. The last UI-thread check must revalidate the
generation so an already queued callback cannot bypass a revocation.

Only final replies to local voice notes may enter automatic speech. Typed inputs,
photos, widgets, unsolicited cards, shared-history events and other-device inputs
do not. Stop Audio immediately cancels queued speech and disables further speech
for pending requests in that generation without authorizing another response.
Reject a delivery during recording rather than queuing surprise playback later.

## Input binding options

| Option | Decision condition | Current status |
| --- | --- | --- |
| Native input metadata | Muse sees the same native input ID and device source and carries them into the delivery tool | Asked the existing Muse; not verified |
| Canonical request tool result | Muse reads the exact input plus Portal handle and deliberately answers that input through the delivery tool | Proposed fallback; no endpoint or runtime handoff yet |
| Shared stream timing | Pick the first answer after a voice note or match nearby timestamps | Rejected because concurrent devices share the stream |
| New Muse instance or silent thread switch | Create a substitute identity or move the conversation without the owner choosing it | Excluded |

The canonical-request route can remove the need for assistant parent metadata.
It cannot be enabled merely by exposing a bag of pending IDs. The actual input
must accompany each handle and be the input Muse is answering. If native metadata
does not expose the handle, establish a supported handoff into the existing Muse
context; do not silently create a second composer or a competing polling job.

## Rich content

Start with text and images, then documents, then interactive content whose format
and callbacks have been verified with Muse. Keep a versioned typed content list;
unknown types produce a visible unsupported-content receipt rather than silently
turning an artifact into prose or executing supplied code.

Images need bounded dimensions and decoded memory, verified MIME type, immutable
content hashes and authenticated transfer. Avoid arbitrary URL fetching; where a
provider download is necessary, constrain hosts, redirects, size and credential
scope. Do not put signed URLs, conversation content or tokens in logs.

Documents and artifacts need format-specific viewers. An HTML artifact needs an
isolated origin, restrictive resource loading, and no camera, microphone, local
files, credentials or native JavaScript bridge. A screenshot is a preview, not
interactive artifact support. Widget callbacks reference server-defined actions
and become new silent local requests after a user tap; never arbitrary shell or
JavaScript-to-host calls. These renderers remain backlog work.

Unsolicited visual cards use a separate explicit destination and visible opt-in
mode, with no speech permission. They must not bypass the family-accessible
screen policy. Origin isolation alone does not make responses from the same
private Muse context child-safe. Do not enable proactive cards by default while
that visibility policy is unresolved.

## Verification before activation

- Read back the exact request binding from the existing Muse runtime using
  existing metadata or an offline fixture. No synthetic main-conversation sends.
- Verify the offline model against wrong device, wrong conversation, unknown
  input, missing ACK, expiry, duplicate, changed retry, multiple pending requests,
  background, Clear, Stop Audio, recording and reconnect cases.
- Implement real authentication, bounded parsing, atomic validation/consumption,
  UI-thread revocation checks and receipts. The reference model is not production
  authentication, storage, a renderer or a network server.
- Show that a phone/Home Mini answer never appears or speaks on the Portal, and
  that an owner-initiated Portal voice note receives one visible/audible answer.
  Arrange that real acceptance with the owner; do not fabricate test messages.
- Rich-content acceptance requires actual format and export evidence from the
  existing Muse, not assumptions based on what the mobile app can display.

Run the offline model with `python3 -m unittest discover -s design -p 'test_*.py'`.
No production source, manifest permission, installed APK or runtime action is
changed by this design package.
