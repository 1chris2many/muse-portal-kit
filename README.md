# muse-portal-kit

> **Active development fork:** [1chris2many/muse-portal-kit](https://github.com/1chris2many/muse-portal-kit)
> · [Original project](https://github.com/hanfang/muse-portal-kit)

Bring **Muse** to **Meta Portal** as a fully capable, embedded experience. The goal
is feature parity with the **Muse Android app**, or as close to parity as the
Portal hardware and available Muse interfaces allow. The native Android client
connects to your existing Muse account and runtime, preserving continuity across
your devices.

## Project goal

The Portal should support the breadth of what you can do with Muse on your phone,
adapted to its large screen, microphones, speakers and camera:

- **Conversation:** voice and text, visible transcripts, responsive replies,
  natural speech, and an optional local "Nova" wake word.
- **Rich content:** photos, generated images, documents, artifacts, widgets and
  interactive reply options, with useful native rendering and working actions.
- **Conversation choice:** access to the main conversation and existing Muse
  threads, with replies delivered to the intended device and conversation.
- **Camera and access:** owner-authorized photos initiated by either the user
  or Muse, plus optional owner recognition for private conversation access.
- **Portal interface:** a polished, touch-friendly client with clear conversation,
  recording, playback and connection states, accessible controls and privacy settings.
- **Muse development:** let the existing Muse help improve its Portal client
  through scoped source access, builds, installation and rollback.

Push-to-talk is the first working interaction and the foundation for the full
client. Use the Muse Android app as
the reference when evaluating features; where a direct equivalent is unavailable,
investigate a supported export or bridge and document the remaining gap.

## Current build

> **Status:** hardware-tested development build, not a production release.
> Android 9 Portal installation, encrypted account provisioning, direct Wi-Fi
> connection, chat acceptance, voice transcription, camera capture, isolated
> developer builds, install/rollback and reconnect have been observed. Automatic
> reply display/audio remains blocked when the server omits reply-parent links.
> Native remote camera invocation depends on the Muse runtime exposing gadget tools;
> an authenticated USB-host fallback has been verified. The original unwired
> always-listening and BLE skeleton prototypes are retained in `legacy/android/`,
> outside this app's build and lint source sets.

Today, hold Talk, speak, and release. Voice notes go to your existing Muse account;
server transcripts appear on this device. Replies appear only when the server
explicitly links them to a request from this Portal. Voice-linked replies can use
an installed offline Android voice. The current build has no always-listening
microphone or continuous camera stream. The goals above describe the intended
product; the sections below distinguish implemented behavior from planned work.

### Reply-options component (offline development slice)

`ReplyOptionsGate` and `ReplyOptionsView` implement bounded native option buttons
with exact-label taps, request/device/conversation checks, expiry, replay handling
and clearing. They are **not connected to incoming Muse payloads yet**. The
`options` content shape is a proposed addressed-delivery format, not a claim that
the gadget SDK exports `muse.create_options` widgets. The view cannot send a
message itself. Integration must validate every tap through the gate on its
executor, then send the exact selected label as a fresh local text input; it must
also clear both the gate and view on background, reconnect and privacy clear.
Protocol and Android 9 view fixtures exercise this component without opening a
Portal connection. See issue [#2](https://github.com/1chris2many/muse-portal-kit/issues/2).

## Architecture

```
┌─────────────────────┐   BLE GATT peripheral   ┌──────────────┐
│  Meta Portal (this  │◄──── pairing v5 ────────►│  Phone (Muse │
│  app)               │   0xFE-framed chunks     │  app)        │
│                     │                          └──────────────┘
│  :app (Android)     │   Noise XX handshake
│   ├─ PortalActivity│◄──── wss://…/v1/noise ──►┌──────────────┐
│   ├─ PortalBlePeri- │   protobuf envelopes     │  Muse VM     │
│   │   pheral        │   (request/response/     │  (server)    │
│   └─ MuseConnection│    datagram)             └──────────────┘
│                     │
│  :protocol (JVM)    │   HTTPS (api.muse.ai)
│   ├─ pairing  (BLE pairing v5: ECDH→transcript→HKDF→AES-GCM)
│   ├─ noise    (Noise_XX_25519_AESGCM_SHA256 + protobuf framing)
│   ├─ session  (chat stream/subscribe, NDJSON events, turn state machine)
│   ├─ audio    (16 kHz WAV voice-note encoder)
│   ├─ api      (fetch_vms / device_token/refresh client)
│   └─ identity (hatch-link: device identity constants)
└─────────────────────┘
```

On first-generation Portal firmware without a usable GATT server, the optional
Mac helper supplies the setup-only BLE peripheral. The Android app still owns
the identity and connects directly over Wi-Fi afterward.

Protocol flow: BLE pairing v5 establishes `access_token`/`refresh_token` →
`fetch_vms` picks a VM → WebSocket upgrade → Noise XX handshake → encrypted
`/chat/stream` requests, `/chat/subscribe` NDJSON events → explicit local-request
origin check → device-only reply display and voice-only TTS. Subscription traffic
alone never grants permission to display or speak.

## Quick start

Requires JDK 17 and Android SDK 35 with Build Tools 34.0.0. Configure
`ANDROID_HOME`. The checked-in wrapper downloads Gradle 8.14.3 with checksum
verification. Never commit `local.properties`, tokens or signing keys.

```bash
# Clone this fork; main contains the integrated Android work.
git clone https://github.com/1chris2many/muse-portal-kit.git
cd muse-portal-kit

# 1. Run the protocol unit tests
cd app
./gradlew :protocol:test

# 2. Build the Android app (requires Android SDK)
./gradlew :app:assembleDebug
adb -s YOUR_PORTAL_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
```

The root Android plugin is configured even for protocol-only Gradle invocations;
install the SDK for this build as a whole. Tests themselves run offline on the JVM.
GitHub Actions builds the app and runs protocol, offline Android layout, and helper tests without a paired
device, account token or conversation. A successful CI run does not verify live
Muse behavior.

## Handoff to another developer or coding agent

Start with `main` in the public fork. The review branch `feat/portal-live-voice-ui`
tracks the same integration; upstream [PR 1](https://github.com/hanfang/muse-portal-kit/pull/1)
contains baseline compatibility fixes and [PR 2](https://github.com/hanfang/muse-portal-kit/pull/2)
contains the app and development workflow. PR 2 currently includes PR 1's commits.

- App entry point: `app/app/src/main/kotlin/com/muse/gadget/portal/ui/PortalActivity.kt`.
- Front end: native Kotlin Android Views in `PortalUi.kt` (`PortalDashboard`) and
  `DeviceStatusView.kt` in the same directory. The Portal home screen is voice-and-touch
  first; optional local typing is under Settings → Show typing controls. The owner's
  Portal runs at 1280×800 mdpi, with a measured 1280×740 content area after system UI.
  `:app:testDebugUnitTest` checks that the voice controls fit that area without a
  keyboard, plus conservative insets, long status text, and rich-image clearing.
  These are offline layout tests, not hardware interaction acceptance.
  Design for the app-parity goal above; the legacy screens are
  not the active client. Keep frontend changes separate from transport changes
  where possible so multiple contributors can work without colliding.
- Transport: `MuseConnection.kt` in the parent package; protocol and pure tests
  are in `app/protocol`. `PortalReplyRouter` isolates display and speech origins.
- Hardware: `PortalCamera.kt`, `LocalPortalBridge.kt`; optional host adapter:
  `tools/portal_dev.py`. This repo contains no private household service/config.
- Build/test locally using the commands above. Hardware acceptance uses the
  owner's real interactions, not generated prompts. **Do not send synthetic
  text, voice, startup or probe messages into an owner's Muse conversation.**
- Preserve the existing Muse identity and credentials. Do not create another
  Muse session as a workaround for missing routing. Do not reinstall by uninstalling:
  that loses app data. Another machine's debug key cannot update an existing APK;
  use the owner's existing trusted installer/signing environment or a separately
  authorized development device. Never publish or copy the signing key into git.
- An absent parent link must remain hidden/silent. Do not substitute the next
  assistant message, timing, sequence adjacency or a self-parent ID.
- Camera use requires owner authorization, Android permission, foreground app
  and the physical privacy switch. Keep those requirements and build isolation.

Acceptance still outstanding: authoritative reply correlation from the Muse
runtime, native command invocation in contexts without gadget tools, and broader
device testing. Android 9 first-generation Portal is the tested hardware; other
Android devices and later Portal variants are not claimed compatible.

## Roadmap toward Android app parity

Feature work is tracked in:
[local wake word](https://github.com/1chris2many/muse-portal-kit/issues/1),
[rich content](https://github.com/1chris2many/muse-portal-kit/issues/2),
[voice/expressive speech](https://github.com/1chris2many/muse-portal-kit/issues/3),
[owner recognition](https://github.com/1chris2many/muse-portal-kit/issues/4), and
[main conversation/thread selection](https://github.com/1chris2many/muse-portal-kit/issues/5).

Reliable [reply delivery](https://github.com/1chris2many/muse-portal-kit/issues/6)
and [Muse command invocation](https://github.com/1chris2many/muse-portal-kit/issues/7)
underpin those features. Track runtime capability, client rendering and verified
Portal behavior separately: a feature visible in the phone app may need a new
delivery or rendering path here. Open issues and proposed implementations do not
mean a feature is enabled in the current build.

## UI reuse from Muse Gadgets

The official ESP32 UI uses C/LVGL, not Android Views. Its
[`muse_ui.c`](https://github.com/facebookincubator/muse-gadget-sdk/blob/main/esp32/components/muse/muse_ui.c),
[`muse_state.h`](https://github.com/facebookincubator/muse-gadget-sdk/blob/main/esp32/components/muse/muse_state.h)
and settings UI are useful references for a status ring, microphone level meter,
recording progress, readable captions and persistent speaker controls. This fork
adapts the status-ring pattern and color palette with an original Android Canvas
view, a live microphone peak meter and recording-progress ring, plus a visible
Stop Audio control. No avatar or LVGL widget code is copied. Port the
interaction patterns into Android; copying the LVGL widgets would require a
separate rendering/input stack. State must reflect observed events, not pretend
that the Muse is thinking or speaking.

The SDK's Apache-2.0 license covers most code, but **not the bundled Jollybot
avatar**. This fork does not redistribute that avatar. Keep upstream notices for
any code port and check separate asset/font licenses before importing them.

The official [Muse Gadgets showcase](https://gadgets.muse.ai/) accepts project
submissions through Discord `#projects`; publishing this fork or opening an
upstream PR does not automatically add it to that showcase.

## Pairing and first-generation Portal limitations

Obtain an SDK token from [Muse Gadgets](https://gadgets.muse.ai/settings/sdk-tokens).
Enter it in the app's Settings → Pair device dialog; it is encrypted with Android
Keystore. Devices with a working GATT peripheral can use the native setup window.
Native GATT is not available on every Portal firmware even when the Bluetooth
adapter reports advertising support. Do not root or reset a device to work around it.

For such devices, `tools/pair_via_mac.py` is an optional, one-time Mac CoreBluetooth
transport for the **same Portal identity**. It uses the official SDK's pairing
implementation. It is not a Mac Muse installation and exposes no shell or files
to Muse. After setup it stops; the Portal connects over Wi-Fi without the Mac.

Install the helper dependencies into a local virtual environment:

```sh
python3 -m venv .pairing-venv
.pairing-venv/bin/pip install pyobjc-framework-Cocoa pyobjc-framework-CoreBluetooth cryptography 'websockets>=13.1,<16'
git clone https://github.com/facebookincubator/muse-gadget-sdk ../muse-gadget-sdk
.pairing-venv/bin/python tools/pair_via_mac.py \
  --sdk-source ../muse-gadget-sdk --adb /path/to/adb --serial YOUR_PORTAL_SERIAL --name-only
```

The token prompt does not echo or persist the SDK token on the Mac. The helper reads
only the Portal's public generated identity and active Wi-Fi name, validates the
phone's device authorization against Muse, then transfers credentials through USB
to app-private staging. The app validates, encrypts and deletes the staging file.
USB debugging and this debuggable APK are required for that development workflow;
the helper will not work with an arbitrary production APK.

In the Muse phone app enable Settings → Devices → Developer mode and add the
advertised `MuseGadgetXXXXXX`. Select the actual network the Portal already uses;
the helper does not reconfigure Wi-Fi or retain its password. Pairing closes after
10 minutes. Keep the phone app current: [upstream issue 51](https://github.com/facebookincubator/muse-gadget-sdk/issues/51)
describes an Android MTU-517 provisioning failure, and
[issue 79](https://github.com/facebookincubator/muse-gadget-sdk/issues/79) describes
the placeholder-SSID failure. A name-only advertisement avoids observed discovery
failure with the combined CoreBluetooth name/service advertisement.

## Voice and privacy

- Settings → Test speaker checks the installed offline voice. Stock Portal voices
  use the ISO-639-2 language code `eng`; standard Android engines generally use `en`.
- The app does not send reply text to an additional online TTS service. If there
  is no offline English voice, it displays the limitation rather than silently
  sending private conversations to another provider. The unwired Edge Read Aloud
  prototype is kept in `legacy/android/tts/`, outside the build.
- Spoken replies go through `SpeechScript` (`app/protocol`, tested offline):
  Markdown marks, tables, links, tags and emoji are removed; the reply is split
  into sentences with short silent pauses between sentences, list items and
  paragraphs; questions, exclamations and headings get small per-utterance
  rate and pitch changes. Android `TextToSpeech` is not assumed to read SSML,
  so no markup of any kind is handed to the engine. Captions still show the
  reply as Muse sent it. Status changes once per reply, not per sentence.
- Voice notes are limited to 15 seconds. Their transcript replaces the pending
  bubble only after the server message ID matches this device's submission ACK.
  Notes from other clients are not used as substitute transcripts.
- Automatic speech is restricted to replies whose explicit parent ID matches a
  voice note acknowledged from this Portal. Shared mobile/Home Mini traffic,
  typed messages, photos, and uncorrelated replies stay silent. Voice eligibility
  expires after five minutes and is cleared on reconnect; duplicate replies do
  not replay. Displaying a message alone never authorizes speech.
- Current live API limitation: observed assistant deltas leave `reply_to_message_id`
  empty and may set `parent_message_id` to the assistant message itself. These
  replies remain hidden and silent, including genuine voice replies, until an authoritative
  link is available. No next-message/timing fallback is used.
- Shared/main-chat history and unlinked assistant messages are never displayed.
  Only replies explicitly parented to this device's acknowledged requests qualify.
  Typed/photo requests can authorize display, never automatic speech.
- Conversations and recordings are not written to disk. Clear removes visible
  messages immediately; backgrounding clears messages and the unsent input. Visible
  messages expire two minutes after the last new message. Failed sends are not
  automatically replayed. Android screenshot/recents capture is protected.
- Origin isolation is **not child-content filtering**: this is still the same
  Muse conversation, so a genuinely linked answer can draw on its private context.
  Do not treat it as an isolated child account or an unsupervised child-safe mode.
- With Android camera permission granted, **Take & share photo** sends a still
  JPEG to the paired Muse. The same Muse can invoke `camera.capture` without a
  per-photo confirmation while this app is in the foreground. A visible capture
  indicator and thumbnail show camera activity. The physical privacy switch is
  never bypassed. Grant camera permission only if you want both forms of access.
- The same Muse can ask the Portal to show one image with `display.draw_url`
  (see **Rich content from Muse**). Each call needs approval in the Muse app;
  the image appears as a card in the conversation and clears with it.
- Camera results are bounded to 128 KiB, retained only in memory, and returned
  over the existing encrypted link. Concurrent captures are rejected; repeated
  request IDs do not take another photo. Camera closes on completion, failure,
  timeout or backgrounding. No general shell or file tools are exposed.
- This is a foreground prototype. Background wake words, arbitrary remote
  commands, OTA, and automatic startup at boot are not enabled.

## Native Muse voice status

Checked against the official SDK on 2026-10-11 for
[issue 3](https://github.com/1chris2many/muse-portal-kit/issues/3):

- Muse's own speech endpoint, `GET /api/voice/tts-stream` (older VMs:
  `/voice/tts-stream`), streams MP3 for a message ID, but only for replies
  produced with `output_modality: "voice"`. The server stopped serving those
  turns (every one failed with a canned error), so upstream
  [PR 12](https://github.com/facebookincubator/muse-gadget-sdk/pull/12) moved
  gadgets to text replies and removed the fetch.
  [PR 146](https://github.com/facebookincubator/muse-gadget-sdk/pull/146), which
  requested voice output again with a device hint, was closed unmerged.
- [Issue 14](https://github.com/facebookincubator/muse-gadget-sdk/issues/14):
  the maintainer says a gadget voice pipeline is in progress and recommends a
  TTS of your choice meanwhile. No SSML, prosody or voice-selection control
  exists anywhere in the SDK; MP3 is the only speech format it has handled.
- [PR 139](https://github.com/facebookincubator/muse-gadget-sdk/pull/139) (open)
  shows the client contract if the endpoint returns: one stream per message ID,
  a 404 fallback path, cancellation by resetting the stream, bounded buffers.
- This app therefore keeps the installed on-device voice. Re-check when
  issue 14 is updated. A native stream would plug into `voiceReply` in
  `MuseConnection`, still behind `PortalReplyRouter`; nothing else would
  authorize playback.

## Rich content from Muse

Surveyed against the official SDK on 2026-10-11 for
[issue 2](https://github.com/1chris2many/muse-portal-kit/issues/2). This is what
a gadget can receive from Muse today; none of it is Muse mobile-app parity, and
nothing below imports shared history.

| Path | Status on this Portal |
|---|---|
| `display.draw_url` (command Muse invokes with an image URL; the official firmware's name, parameter and result shape) | **Supported, bounded.** https only, public host names only, and the resolver vets every address before the connection uses it: private, loopback, link-local, carrier-grade NAT, multicast, documentation and IPv4-in-IPv6 forms are refused, and a mixed answer is refused whole, for redirect targets too. No cookies or credentials, up to 3 same-scheme redirects, 2 MiB, JPEG/PNG/WebP by magic bytes, source up to 4096 px a side, shown at up to 1280 px, one 30 s budget across redirects and decoding, one at a time, foreground only. Result reports format, size, scale, bytes and time, never the URL. Needs the Muse app's per-call approval and the same runtime invocation support as `camera.capture` (issue 7). |
| `display.show_animation` (command) | **Supported.** Clears images shown by `display.draw_url`. Images also clear with the Clear button, backgrounding, reconnect and the two-minute privacy timer. Each of those revokes any image still downloading: it is cancelled where possible, rechecked on the UI thread before display, and reported to Muse as `cancelled`, never as shown. |
| `delta.presentation` on `/chat/subscribe` (images, audio, video, forms attached to a reply; undocumented, shape per [SDK issue 186](https://github.com/facebookincubator/muse-gadget-sdk/issues/186)) | **Announced in words only.** A gadget's token cannot read the files (`path not allowed for device token`), so the Portal shows "Muse sent an image with this reply…" under the same parent-link gate as text, never speaks it, never fetches, and logs the event's field names only. Forms (`data.html`) are never rendered. |
| Widgets, artifacts, app-style cards | **No API.** The gadget SDK has no such payload; none is claimed or rendered. |
| Gadget → Muse images | Already supported: **Take & share photo** sends a JPEG item on `/chat/stream`. |

What never happens: no WebView or script execution, no local files, camera or
host access handed to remote content, no fetch of `sandbox://` paths, no http://
or private-network fetches on Muse's behalf. Re-check SDK issue 186 for file
access or inline media and issue 108 for approval-free drawing.

## Muse development and the command-routing fallback

Some Muse contexts do not expose gadget invocation even after a successful
registration ([SDK issue 154](https://github.com/facebookincubator/muse-gadget-sdk/issues/154)).
This app keeps its native `camera.capture` command, and also provides an optional
USB developer bridge: a loopback-only HTTP server with a random bearer stored
in app-private no-backup storage. It exposes only status and single-photo capture.
The trusted host adapter consumes that bearer through `adb run-as`, forwards a
temporary localhost port and removes it after the request. No key is returned
to the Muse or logged. Voice and chat still connect directly over Wi-Fi.

`tools/portal_dev.py` can be mounted in an **existing authenticated owner bridge**.
Never expose its actions on an unauthenticated/public endpoint. Configure one
source worktree, one device serial, one SDK/signing key and one builder image.
The owner must authorize both agent camera access and app development/deployment.

| Action | Arguments | Result |
|---|---|---|
| `portal.camera` | `{}` | JPEG in `data_base64`; `format: jpeg-base64` |
| `portal.files` | `{}` | Editable source paths |
| `portal.read` | `path` | UTF-8 source and SHA-256 |
| `portal.write` | `path`, `content`, `expected_sha256` | Optimistic update; use null hash only for a new file |
| `portal.diff` | `{}` | Tracked source differences |
| `portal.build` | `{}` | Asynchronous immutable-snapshot job ID |
| `portal.status` | Optional `job_id` | Build/test result and bounded log tail |
| `portal.install` | `job_id` | Installs only a passed, unchanged APK for this package |
| `portal.rollback` | `{}` | Restores the previous privacy-capable APK without clearing app data |

The authorized Muse can edit Kotlin/XML/README in a separate worktree; build scripts, the
host adapter, credentials and unrelated Mac files are outside that scope.
Builds have no network, host home, signing key, ADB, or Docker socket access.
First prepare the Docker SDK image and warm its dependency cache from reviewed
source using `tools/portal-build.Dockerfile` and `tools/portal-warm.Dockerfile`.
New dependencies require a reviewed cache refresh. The host checks package,
permission scope and artifact hash before signing/installing. Installation can
restart the app; test the changed behavior afterward. Rollback restores code,
not an earlier copy of app data. Install and rollback reject version codes below
2, which predate the display privacy repair. This version floor is a regression
guard, not proof of arbitrary source-code safety; review edits before deployment.
Nothing is automatically published to GitHub.

## Hardware smoke tests

The separate Android test APK contains `PortalSmokeInstrumentation`; it is not
included in the production app. With explicit developer authorization,
`-e camera true` captures one JPEG without storing/sharing it. The test harness
never sends chat messages or voice notes. Former `voice`, `audio`, and `probe`
options are explicitly rejected before starting the app. Transcript and speech
routing tests use offline JVM fixtures. Coordinate with the Muse through the
agreed authenticated channel, not synthetic prompts in the main conversation.

## Security policy — tokens and secrets

**No hardcoded tokens, keys, or personal data — ever.** This is enforced by code
review and by `.gitignore`:

- SDK tokens (`mgst_…`), `access_token`/`refresh_token`, keystores, `local.properties`,
  `*.pem`/`*.key` files, and any `*token*.json` / `*credential*.json` config are
  **never committed**. See `.gitignore`.
- Runtime credentials are encrypted in app-private storage using Android Keystore,
  never embedded in source code, resources, or build files. The USB development
  importer deletes private plaintext staging after import or validation failure.
- Test fixtures use obvious placeholders (`mgst_test`, `mgst_x`) — never real tokens.

**If a token leaks** (pasted into a log, committed, screenshotted): revoke it
immediately at **gadgets.muse.ai**, then delete the leaked copy and rotate any
derived credentials.

## Acknowledgments

This project builds on the work of these upstream projects — thank you:

| Project | Used for | License |
|---|---|---|
| [muse-gadget-sdk](https://github.com/facebookincubator/muse-gadget-sdk) (Meta) | Gadget session protocol reference (pairing v5, Noise XX, chat streams) — the `:protocol` module is a Kotlin port of its Linux SDK | Apache 2.0 |
| [immortal](https://github.com/starbrightlab/immortal) (Starbright Lab) | Meta Portal repurposing research (ADB sideload, wake-word notes) | MIT |
| [portal-room-os](https://github.com/jacobturner-king/portal-room-os) | Portal repurposing research (BootReceiver / kiosk patterns) | *No LICENSE file found in the referenced snapshot — verify before redistributing its code* |
| [pylutron-caseta](https://github.com/gurumitts/pylutron-caseta) | Lutron LEAP-over-TLS research for phase-2 smart-home control | Apache 2.0 |

## License

Apache 2.0 — see [LICENSE](LICENSE).
