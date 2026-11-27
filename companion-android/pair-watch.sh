#!/usr/bin/env bash
# Pair a Wear OS watch with BLEUnlock (the watch has no camera for the QR code).
#
# 1. On the Mac: BLEUnlock menu -> Android Companion… -> Pair New… -> Copy Code
#    (choose "None (BLE only)" for Paired phone and name it e.g. "Pixel Watch").
# 2. Connect to the watch with wireless adb (Developer options -> Wireless debugging):
#      adb pair <ip:pair-port>   then   adb connect <ip:port>
# 3. Run: ./pair-watch.sh [adb-serial]      (installs the watch app if needed)
# 4. Click Done in BLEUnlock.
#
# The pairing code is read from the Mac clipboard, sent to the watch over adb, and the
# clipboard is cleared. It is never printed.
set -euo pipefail

PKG=com.github.macsinspace.bleunlockcompanion
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK="$HERE/wear/build/outputs/apk/release/wear-release.apk"

serial="${1:-}"
if [[ -z "$serial" ]]; then
	# Pick the only connected watch (characteristics contain "watch").
	watches=()
	for s in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
		[[ "$(adb -s "$s" shell getprop ro.build.characteristics 2>/dev/null)" == *watch* ]] && watches+=("$s")
	done
	if [[ ${#watches[@]} -ne 1 ]]; then
		echo "Found ${#watches[@]} watches; pass the adb serial explicitly. Devices:" >&2
		adb devices -l >&2
		exit 1
	fi
	serial="${watches[0]}"
fi

code="$(pbpaste)"
if [[ "$code" != bleunlock-companion:1:* ]]; then
	echo "Clipboard does not hold a BLEUnlock pairing code. Click Copy Code in BLEUnlock first." >&2
	exit 1
fi

if ! adb -s "$serial" shell pm path "$PKG" >/dev/null 2>&1; then
	[[ -f "$APK" ]] || (cd "$HERE" && ./gradlew :wear:assembleRelease -q)
	echo "Installing watch app on $serial ..."
	adb -s "$serial" install -r "$APK"
fi

for p in BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT POST_NOTIFICATIONS; do
	adb -s "$serial" shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true
done

adb -s "$serial" shell am start -n "$PKG/.WearActivity" --es pair "$code" >/dev/null
printf '' | pbcopy
echo "Sent pairing code to $serial and cleared the clipboard. Now click Done in BLEUnlock."
