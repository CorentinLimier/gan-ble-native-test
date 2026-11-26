# gan-ble-native-test

A minimal native Android (Kotlin) BLE test harness, built to test one
specific question from the
[`gan-ble-raw-logger`](https://github.com/CorentinLimier/gan-ble-raw-logger)
Android connectivity investigation: **do native-only Android BLE controls —
unavailable to any browser's Web Bluetooth implementation — change how often
a GAN cube's connection hangs on this phone?**

Read the [full investigation writeup](https://github.com/CorentinLimier/gan-ble-raw-logger)
first if you haven't — this app exists specifically to test its "why this
can't be properly fixed from a web page" section.

## What it tests

Web Bluetooth gives JavaScript no access to:
- **Connection priority** (`requestConnectionPriority()`)
- **MTU negotiation control** (`requestMtu()`)
- **GATT cache refresh** (`BluetoothGatt.refresh()`, a hidden/reflection-only API)
- **`autoConnect` mode** (a different connection establishment path with its
  own internal retry/backoff)

This app connects directly to the cube via `BluetoothGatt` by MAC address
(pre-filled with the one the web tool always extracts:
`FB:DD:7A:63:A3:20`) with each of the above as an independent on/off
switch, then tries to enable notifications on the state characteristic
exactly like the web tool does — the same operation that hangs there.

A **Scan for devices** button is also available if you want to find/verify
a MAC address rather than typing one in — it shows every nearby BLE device
(not filtered to GAN ones), live, with name/address/RSSI; tap a row to fill
in the MAC field. This doubles as a native equivalent of the
`chrome://bluetooth-internals` device list used earlier in the
investigation to spot a cube advertising under more than one MAC address.

**It is deliberately not automated with a retry loop.** Tap Connect, note
whether it hangs or works, tap Disconnect, repeat — ideally ~10 times per
toggle combination — so each attempt is a clean, comparable data point
instead of averaging over an automatic retry policy. The one thing worth
comparing against is the web tool's own observed baseline: roughly
probabilistic, sometimes failing many attempts in a row.

## Suggested test procedure

1. Install/open in Android Studio, run on the same phone used in the
   investigation.
2. Start with all four switches **off** — this is the closest native
   equivalent to what Web Bluetooth does today, and should reproduce the
   hang at roughly the same rate. Run ~10 attempts, tally hangs vs. success.
3. Turn switches on **one at a time** (not all at once) and repeat ~10
   attempts each, to isolate which one (if any) actually moves the needle:
   - `autoConnect=true` alone
   - `requestConnectionPriority(HIGH)` alone
   - `gatt.refresh()` alone
   - `requestMtu(247)` alone
4. If one toggle looks promising, try combinations.
5. A "hang" looks like: `writeDescriptor() call returned true` followed by
   **no** `onDescriptorWrite` line ever appearing. A success shows
   `onDescriptorWrite status=0 (SUCCESS)` within a second or so, generally
   followed by `notification #1 ...` once you turn a face.
6. Use **Copy log** after each batch and keep notes on which toggles were on
   for which run — the in-app log has no persistence across app restarts.

## What it deliberately doesn't do

- Scanning is optional, not the default path — you can type a known MAC
  directly without ever scanning, matching how a native app would normally
  operate (and incidentally matching the "skip scanning" test already
  ruled out in the web investigation, so a direct-MAC connect isn't
  re-testing that).
- No full protocol decoding — only raw + AES-decrypted bytes per
  notification, plus one exception: GYRO events (GAN Gen4 only) are decoded
  into a quaternion and rendered as a rotating wireframe cube above the log,
  since that's a quick, visual way to confirm the live connection is
  tracking the physical cube's orientation correctly. MOVE/FACELETS/
  HARDWARE/BATTERY are still raw/decrypted hex only — full decoding for
  those already exists in the web tool if that's ever needed natively.
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
over — only reasoned through carefully and, for the AES decryption logic
specifically, cross-validated byte-for-byte in a standalone JVM program
against the already-validated web tool's output. If Android Studio flags
anything on first sync/build, that's expected risk from skipping the normal
build-test loop, not a sign the overall approach is wrong.

## Permissions

Requests `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` on Android 12+, or
`ACCESS_FINE_LOCATION` on Android 11 and below (required by the OS for BLE
regardless of whether this app actually scans). Granted at runtime on first
Connect tap.

## Protocol / crypto reference

Service/characteristic UUIDs and the AES-128-CBC key-salting scheme are the
same ones documented in
[`gan-ble-raw-logger`](https://github.com/CorentinLimier/gan-ble-raw-logger),
reproduced from the upstream MIT-licensed
[`afedotov/gan-web-bluetooth`](https://github.com/afedotov/gan-web-bluetooth)
project for interoperability with GAN-branded hardware you own.
