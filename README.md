# gan-ble-native-test

A minimal native Android (Kotlin) BLE test harness, built originally to test
one specific question from the
[`gan-ble-raw-logger`](https://github.com/CorentinLimier/gan-ble-raw-logger)
Android connectivity investigation: **do native-only Android BLE controls —
unavailable to any browser's Web Bluetooth implementation — change how often
a GAN cube's connection hangs on this phone?** It has since grown into a
small standalone GAN cube viewer: once connected, it decodes the cube's own
BLE data (not the phone's sensors) and renders a live, colored, rotating
Rubik's cube.

Read the [full investigation writeup](https://github.com/CorentinLimier/gan-ble-raw-logger)
first if you haven't — the connection-reliability testing this app does is
specifically about that writeup's "why this can't be properly fixed from a
web page" section.

## What it does

**Before connecting** (this is the only UI visible while disconnected):
- Native BLE controls Web Bluetooth can't expose, each an independent
  on/off switch: **connection priority** (`requestConnectionPriority()`),
  **MTU negotiation** (`requestMtu()`), **GATT cache refresh**
  (`BluetoothGatt.refresh()`, hidden/reflection-only), **`autoConnect`
  mode**.
- **Scan for devices**: shows every nearby BLE device (not filtered to GAN
  ones), live, with name/address/RSSI — tap a row to fill in the MAC field.
  Doubles as a native equivalent of the `chrome://bluetooth-internals`
  device list used earlier in the investigation to spot a cube advertising
  under more than one MAC address.
- A MAC field, pre-filled with the one the web tool always extracts
  (`FB:DD:7A:63:A3:20`).

**Once connected and notifications are enabled**, the UI switches to just
three buttons and the cube:
- **Disconnect**
- **Reset gyro** — client-side only, re-anchors the current physical
  orientation as the on-screen "home" pose (no command sent to the cube).
- **Reset cube** — sends the GAN "treat current position as solved"
  command to the cube's firmware and resets the local display to solved.
  Only press it when the physical cube is actually solved.
- A live, colored, 3D Rubik's cube: GAN Gen4 GYRO events rotate the whole
  cube to match the physical cube's orientation; MOVE events are replayed
  with the same Kociemba cubie move tables the web tool uses (already
  cross-checked there against gan-web-bluetooth's documented example) to
  update sticker colors in real time as you turn faces; periodic FACELETS
  sync events overwrite the tracked state wholesale, so it self-corrects if
  a move notification was ever missed.

**Logging goes to Android Studio's Logcat only** (tag `GanBleNativeTest`) —
nothing is rendered in-app. That used to be in-app and caused a real
performance problem under a high notification rate (full log history was
being re-rendered on every single line); Logcat has no such issue. A
"Log every notification" switch controls whether the noisy per-notification/
GYRO/MOVE lines go to Logcat at all; connection-lifecycle logs are
unaffected either way.

## Testing native BLE controls against the connection hang

1. Install/open in Android Studio, run on the same phone used in the
   investigation.
2. Start with all four switches **off** — closest to what Web Bluetooth
   does today, should reproduce the hang at roughly the same rate. Tap
   Connect, Disconnect, repeat ~10 times, tally hangs vs. success (watch
   Logcat, filtered to `tag:GanBleNativeTest`).
3. Turn switches on **one at a time** and repeat ~10 attempts each to
   isolate which one (if any) actually moves the needle.
4. If one toggle looks promising, try combinations.
5. In Logcat, a **hang** looks like: `writeDescriptor() call returned true`
   followed by **no** `onDescriptorWrite` line ever appearing — the app's UI
   will also just sit on "Connected, enabling notifications…" forever. A
   **success** shows `onDescriptorWrite status=0 (SUCCESS)` within a second
   or so, and the UI switches to the minimal connected view.

## Scope notes

- Only Gen4 (the GAN12 ui Maglev used throughout this investigation) is
  decoded for MOVE/FACELETS/GYRO — Gen2/Gen3 use an entirely different byte
  layout and will connect fine but won't drive the cube visualization.
- HARDWARE/BATTERY events aren't decoded at all (not needed for the
  visualization or the connectivity testing this app is for).
- No pairing/bonding of any kind — matches the web tool and the cube's own
  design (it refuses OS-level pairing; see the investigation doc).

## Building

This is a standard Android Studio project (Kotlin, classic Views, no
Compose). Open the repo root folder directly in Android Studio ("Open" →
select this folder). If Android Studio reports the Gradle wrapper is
missing or incomplete, let it regenerate one automatically (or use
**File → Sync Project with Gradle Files**) — the wrapper jar itself isn't
committed here since it couldn't be verified outside of Android Studio in
the environment this was written in; `gradle/wrapper/gradle-wrapper.properties`
specifies Gradle 8.4, which Android Studio will fetch.

minSdk 26, compileSdk/targetSdk 34 — runs on the Android 10 (API 29) phone
from the investigation and anything newer.

**Disclosure:** this was written without access to the Android SDK/Gradle/an
emulator or device, so it has not been built or run before being handed
over — only reasoned through carefully. The AES encryption/decryption and
the Kociemba move/facelets math were cross-validated against the
already-validated web tool's output/logic (byte-for-byte for AES, line-by-
line diffed for the move tables); the cube rendering's rotation matrix and
backface-culling logic were hand-verified on paper. If Android Studio flags
anything on first sync/build, that's expected risk from skipping the normal
build-test loop, not a sign the overall approach is wrong.

## Permissions

Requests `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on Android 12+, or
`ACCESS_FINE_LOCATION` on Android 11 and below (required by the OS for BLE
regardless of whether this app actually scans). Granted at runtime on first
Connect tap.

## Protocol / crypto reference

Service/characteristic UUIDs, the AES-128-CBC key-salting scheme, and the
Gen4 MOVE/FACELETS/GYRO bit layouts are the same ones documented/validated
in [`gan-ble-raw-logger`](https://github.com/CorentinLimier/gan-ble-raw-logger)'s
`protocol.js`, reproduced from the upstream MIT-licensed
[`afedotov/gan-web-bluetooth`](https://github.com/afedotov/gan-web-bluetooth)
project for interoperability with GAN-branded hardware you own. The cubie
move tables are the standard ones from the public Kociemba two-phase solver
(same cubie numbering convention).
