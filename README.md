# muse-portal-kit

> **Repository:** `github.com/hanfang/muse-portal-kit`

Turn a retired **Meta Portal** into a foreground, push-to-talk device for **Muse**.
Hold Talk, speak, and release. Voice notes go to your existing Muse account;
server transcripts and replies appear in the conversation. Replies use an
installed offline Android voice. No always-listening microphone or continuous camera stream.

> **Status:** hardware-tested development build, not a production release.
> Android 9 Portal installation, encrypted account provisioning, direct Wi-Fi
> connection, chat acceptance and reconnect have been observed. Transcript and
> audible reply acceptance are still being tested. The original unwired
> always-listening prototype is retained in source but excluded from this build.

## Architecture

```
┌─────────────────────┐   BLE GATT peripheral   ┌──────────────┐
│  Meta Portal (this  │◄──── pairing v5 ────────►│  Phone (Muse │
│  app)               │   0xFE-framed chunks     │  app)        │
│                     │                          └──────────────┘
│  :app (Android)     │   Noise XX handshake
│   ├─ ChatActivity   │◄──── wss://…/v1/noise ──►┌──────────────┐
│   ├─ PortalBlePeri- │   protobuf envelopes     │  Muse VM     │
│   │   pheral        │   (request/response/     │  (server)    │
│   └─ VoiceService*  │    datagram)             └──────────────┘
│                     │
│  :protocol (JVM)    │   HTTPS (api.muse.ai)
│   ├─ pairing  (BLE pairing v5: ECDH→transcript→HKDF→AES-GCM)
│   ├─ noise    (Noise_XX_25519_AESGCM_SHA256 + protobuf framing)
│   ├─ session  (chat stream/subscribe, NDJSON events, turn state machine)
│   ├─ audio    (16 kHz WAV voice-note encoder)
│   ├─ api      (fetch_vms / device_token/refresh client)
│   └─ identity (hatch-link: device identity constants)
└─────────────────────┘
        * planned
```

Protocol flow: BLE pairing v5 establishes `access_token`/`refresh_token` →
`fetch_vms` picks a VM → WebSocket upgrade → Noise XX handshake → encrypted
`/chat/stream` requests, `/chat/subscribe` NDJSON events → turn state machine
settles the reply → TTS speaks it back.

## Quick start

Requires JDK 17, Gradle 8.14.3 and Android SDK 35. Configure `ANDROID_HOME`.

```bash
# 1. Run the protocol unit tests (no Android SDK needed)
cd app
gradle :protocol:test

# 2. Build the Android app (requires Android SDK)
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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
  sending private conversations to another provider.
- Voice notes are limited to 15 seconds. Their transcript replaces the pending
  bubble only after the server message ID matches this device's submission ACK.
  Notes from other clients are not used as substitute transcripts.
- Conversations and recordings are not written to disk; reopening clears the
  visible conversation. Failed sends are not automatically replayed.
- With Android camera permission granted, **Take & share photo** sends a still
  JPEG to the paired Muse. The same Muse can invoke `camera.capture` without a
  per-photo confirmation while this app is in the foreground. A visible capture
  indicator and thumbnail show camera activity. The physical privacy switch is
  never bypassed. Grant camera permission only if you want both forms of access.
- Camera results are bounded to 128 KiB, retained only in memory, and returned
  over the existing encrypted link. Concurrent captures are rejected; repeated
  request IDs do not take another photo. Camera closes on completion, failure,
  timeout or backgrounding. No general shell or file tools are exposed.
- This is a foreground prototype. Background wake words, arbitrary remote
  commands, OTA, and automatic startup at boot are not enabled.

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
| `portal.rollback` | `{}` | Restores the previous APK without clearing app data |

Nova can edit Kotlin/XML/README in her separate worktree; build scripts, the
host adapter, credentials and unrelated Mac files are outside that scope.
Builds have no network, host home, signing key, ADB, or Docker socket access.
First prepare the Docker SDK image and warm its dependency cache from reviewed
source using `tools/portal-build.Dockerfile` and `tools/portal-warm.Dockerfile`.
New dependencies require a reviewed cache refresh. The host checks package,
permission scope and artifact hash before signing/installing. Installation can
restart the app; test the changed behavior afterward. Rollback restores code,
not an earlier copy of app data. Nothing is automatically published to GitHub.

## Hardware smoke tests

The separate Android test APK contains `PortalSmokeInstrumentation`; it is not
included in the production app. With explicit developer authorization,
`-e camera true` captures one JPEG without storing/sharing it, and `-e voice true`
sends a generated, clearly labeled synthetic voice fixture from the test assets
and checks that the server transcription reaches the message view. Generate
`app/app/build/smoke-assets/transcription-test.wav` as mono 16 kHz PCM before
building that test APK. The optional `probe` mode sends a labeled integration
request; use an established authenticated coordination channel instead if the
Muse cannot authenticate requests arriving through device chat.

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
