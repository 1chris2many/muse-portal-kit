# Addressed Portal delivery

The Portal has an explicitly addressed output channel alongside Muse's shared
conversation stream. The existing Muse authors the response; the Portal checks
whether that response may appear or speak. Replies are bound to a real local
input, rather than inferred from which assistant message arrives next.

## Implemented in version 0.4.2

[PR 16](https://github.com/1chris2many/muse-portal-kit/pull/16) implements canonical
input lookup, one-time reply grants, text and native reply options.
[PR 17](https://github.com/1chris2many/muse-portal-kit/pull/17) adds bounded inline
images and MP3 playback. Both are merged. See the [README](../README.md#exact-request-replies-and-native-options-042)
for current payloads, limits and setup.

The optional authenticated host adapter exposes `portal.requests.read`,
`portal.deliver` and `portal.delivery.status`. Deployments must update their host
adapter as well as the Android app and expose these actions to the existing Muse
context. A merged implementation or installed APK alone does not establish that
a particular Muse runtime has completed a real request through this route.

The route currently answers text and transcribed voice inputs in the existing
main conversation. Photo replies still depend on native reply-parent metadata.
Existing phone-thread discovery, automatic main-context invocation, arbitrary
artifacts and owner recognition are separate capabilities.

## Request and response contract

1. A real local send creates a random request handle on the Portal. Input type,
   conversation and connection generation are recorded locally. A microphone
   input does not identify the speaker as the owner.
2. A native ACK binds the handle to the server's input message ID. Unknown,
   failed or ambiguous sends grant no permission to reply.
3. From its existing main context, Muse reads the precise input using
   `exact_text`, or the SHA-256 of the original uploaded WAV. The read returns
   canonical input text and its binding together. Voice reads require a
   correlated transcript; multiple matching inputs fail closed. There is no
   newest-request, substring, timestamp or fuzzy selector.
4. Muse composes for that input and echoes the binding into one final delivery.
   A worker may transport the exact authored envelope; it must not compose a
   substitute reply. Direct Wi-Fi input and the existing Muse identity remain.
5. The Portal checks authentication, destination, generation, input ID and live
   local grant. Speech permission comes from the local input type, never a
   remote `speak:true` flag.
6. Native and addressed replies consume the same grant. Identical retries return
   a receipt without redisplaying or speaking; changed retries fail. A new
   delivery ID cannot authorize a second final answer.

The envelope contains `version`, `delivery_id`, `device_id`, `generation`,
`request_id`, `input_message_id`, `conversation_id` and a typed `content` list.
Copy the binding returned by the canonical read. The adapter's `existing-main`
conversation value is a configured binding, not a native Muse thread ID.

The bridge credential authenticates the adapter connection, but cannot by itself
distinguish a main-context author from a worker. Preserve the main-context
authoring rule. Do not create synthetic user messages, a second Muse instance,
silent thread switches or a competing polling job to trigger delivery.

### Revocation and receipts

Pending requests expire within two minutes. Clear, backgrounding, the privacy
timer and reconnect revoke handles as well as clearing pixels and stopping
audio. Late ACKs and queued UI callbacks cannot restore revoked requests.

Only final replies to local voice notes may play automatically. Typed inputs,
option taps, photos and other-device traffic grant no speech permission. Stop
cancels current and pending speech without authorizing another answer. Recording
blocks delivery instead of queuing surprise playback.

After a timeout, query `portal.delivery.status` with the same delivery ID rather
than creating another ID. Rendered, queued, started, completed, cancelled and
failed states are distinct. Acceptance does not mean a person heard the audio;
exactly-once physical playback across a crash is not guaranteed.

## Content and bounds

| Content | Current implementation |
| --- | --- |
| Text | Up to 16,000 UTF-8 bytes; local display and speech policy applies |
| Reply options | Up to six bounded labels; a tap sends the exact selected text as a new silent local input |
| Image | One inline JPEG, PNG or WebP, at most 2 MiB after base64 decoding; matching MIME, valid Android decoding, source edge at most 4096 pixels, display edge at most 1280 pixels |
| Audio | One inline MP3, at most 2 MiB and three minutes; valid Android decoding and a live local voice grant required |

Request reads and receipt queries are bounded to 64 KiB; inline-media delivery
bodies to 6 MiB. The host adapter, Android receiver and any outer transport must
all accommodate that delivery bound. Inline assets use `mime_type` and canonical
`data_base64`; callers cannot substitute a URL, local path, attachment ID or a
claimed hash for the bytes. Hashes are computed from received content for retry
identity. Unsupported content and malformed assets are rejected.

Image decoding runs off the UI thread; Clear and lifecycle changes cancel stale
results. MP3 preparation does not authorize playback, and cancellation releases
the retained bytes. A failed audio export can leave readable text without
silently switching providers. Exportable Muse TTS files are supported; parity
with the phone app's conversational voice and SSML is not established.

The separate native `display.draw_url` image command is covered in the README.
Its bounded URL policy must not be confused with inline addressed delivery.

## Remaining content formats

The [capability inventory](capability-inventory.json) separates the existing
Muse's reported exports from implemented Portal behavior. Its source review is
dated October 11; the Portal implementation states reflect version 0.4.2.

Documents require format-specific viewers and bounded authenticated export.
HTML artifacts need an isolated origin, restricted resource loading and no
camera, microphone, credentials, local files or privileged JavaScript bridge.
A screenshot is a preview, not an interactive artifact.

List and map widgets need Portal renderers for their underlying data. Client
widget tokens do not transfer the phone UI or its callbacks. Actions must map
to fixed, validated operations; never accept arbitrary shell commands, executable
callbacks or widget-provided routing endpoints.

Video, reactions and targeted progress updates need their own formats and target
IDs. Unsolicited cards need a separate, explicitly enabled visual destination;
they grant no audio permission. Origin isolation alone does not make a private
conversation appropriate for everyone who can see the screen.

`sandbox://` references are client-specific, not remotely fetchable URLs.
Transfer underlying bytes within the scoped bridge. Do not export a runtime's
filesystem or publish private artifacts to obtain displayable links. Additional
widget and artifact API claims from an authorship-disputed report remain
unverified and are excluded from the supported contract.

## Verification

Production behavior is exercised by the Kotlin protocol and Android view suites,
plus host-adapter tests:

```bash
./app/gradlew -p app :protocol:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
python3 -m unittest discover -s tools -p 'test_*.py'
python3 -m unittest discover -s design -p 'test_*.py'
```

`delivery_model.py` is the original offline, text-only design model. Its 37
contract examples illustrate binding, revocation and replay rules; it is not a
server, authentication implementation or version-0.4.2 compatibility oracle.
For example, it uses a synthetic five-minute clock, permits abstract photo/widget
requests and omits canonical transcript lookup, native-stream completion sharing,
reused-ACK handling and media. Production source and tests take precedence.
The model is not imported into the app or host adapter.

End-to-end acceptance uses a genuine owner-initiated Portal input and the
existing Muse main-context read, composition and export. Verify exactly one
visible/audible voice answer, revocation and other-device isolation. If using
WAV hashing, verify byte preservation through the upload path; transformed files
must fail closed. Do not send synthetic prompts into an owner's conversation.
