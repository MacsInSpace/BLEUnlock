# BLEUnlock Companion (Android / Wear OS)

Turns an Android phone or Wear OS watch into a BLEUnlock key.

- **Presence:** the device advertises a fixed service UUID with a token that changes every 10 minutes (HMAC of the time window). BLEUnlock picks it up and reads RSSI from the adverts, so rotating BLE addresses and a sleeping phone don't matter.
- **Unlock:** before typing your password, BLEUnlock connects and sends a random 16-byte challenge. The device must sign it with the pairing key. A recorded advert can't unlock the Mac.
- **Key storage:** the pairing key is kept in Android Keystore (non-exportable) and in the Mac's login keychain.

Modules: `core` (protocol + foreground service), `app` (phone UI with QR pairing), `wear` (watch UI).

## Build

```sh
./gradlew :app:assembleRelease :wear:assembleRelease
# app/build/outputs/apk/release/app-release.apk    (~1 MB)
# wear/build/outputs/apk/release/wear-release.apk  (~45 KB)
```

Release builds are signed with the debug key, which is fine for sideloading.

## Phone

1. `adb install -r app/build/outputs/apk/release/app-release.apk`
2. Mac: BLEUnlock menu → **Android Companion…** → **Pair New…**
3. Phone: open **BLEUnlock Companion** → **Scan pairing QR from Mac**, then click **Done** on the Mac.

If the phone is also paired with the Mac in System Settings → Bluetooth, the Mac may hold LE keys the phone doesn't have. LE connections then fail with "Peer removed pairing information". BLEUnlock falls back to a classic Bluetooth page, which adds about 2 s. Forgetting the phone in macOS Bluetooth settings removes the delay; the companion doesn't need an OS pairing.

## Pixel Watch / Wear OS

The watch has no USB data port or camera, so you install and pair over Wi-Fi adb. This needs a normal WPA2/WPA3 personal network; WPA2 Enterprise networks usually block it.

1. Watch: Settings → System → About → Versions → tap **Build number** 7 times.
2. Watch: Settings → Developer options → turn on **ADB debugging** and **Wireless debugging** → **Pair new device**.
3. Mac: `adb pair <ip>:<pair-port>` (enter the 6-digit code), then `adb connect <ip>:<port>`.
4. Mac: BLEUnlock → **Android Companion…** → **Pair New…**. Name it e.g. "Pixel Watch", set Paired phone to **None (BLE only)**, then click **Copy Code**.
5. Mac: `./pair-watch.sh`. This installs the watch app if needed, grants its permissions, sends the code from the clipboard, and clears the clipboard.
6. Click **Done** in BLEUnlock.

Watch behaviour:
- Advertises about once a second (the phone does about 4×/s) to save battery.
- While the watch is off your wrist (off-body sensor), it stops advertising and refuses challenges, so a watch left on the desk doesn't count as presence.
- Phone and watch can both be paired; either one can unlock.

## Notes

- The app runs as a foreground service with a minimal-priority notification, as Android requires. It restarts on boot and after updates.
- Check battery use after a day in Settings → Battery → App battery usage.
- Protocol details: top of `core/.../Protocol.kt` and `BLEUnlock/Companion.swift`.
