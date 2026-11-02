#!/bin/bash
# Installs the built APK on the real TV. Run this ONLY when nobody is watching: it talks to the TV over the network.
#   TV_HOST=<tv ip> dev/install-tv.sh
# Uses cleanyourtv's tools, because macOS can block the adb binary from reaching the TV (README there).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TOOLS="$HOME/moe/cleanyourtv/tools"
PY="$TOOLS/venv/bin/python"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
: "${TV_HOST:?set TV_HOST to the TV address, for example TV_HOST=192.168.1.50 dev/install-tv.sh}"

[ -f "$APK" ] || { echo "No APK yet. Build it first: ./gradlew :app:assembleRelease" >&2; exit 1; }

echo "Sending Zap to $TV_HOST..."
"$PY" -I "$TOOLS/b64push.py" "$APK" /data/local/tmp/zap.apk
OUT=$("$PY" -I "$TOOLS/tv.py" 'pm install -r /data/local/tmp/zap.apk; rm -f /data/local/tmp/zap.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "Install failed." >&2; exit 1 ;;
esac

# Compile ahead of time: the biggest single win for start-up and scrolling on TV hardware.
"$PY" -I "$TOOLS/tv.py" 'cmd package compile -m speed -f com.moe.zap'
"$PY" -I "$TOOLS/tv.py" 'am start -n com.moe.zap/.ui.MainActivity'
echo "Done. Zap is open on the TV."
