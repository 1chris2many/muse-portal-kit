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
