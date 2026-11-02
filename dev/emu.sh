#!/bin/bash
# Drive the Android TV emulator only. Every adb call is pinned to the emulator, so it can never reach a real TV.
#   dev/emu.sh shot <file.png>        screenshot
#   dev/emu.sh keys KEY KEY ...       press remote keys (DPAD_DOWN, ENTER, BACK...), or text:abc to type
#   dev/emu.sh sh <command>           shell command
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
E="$ADB -s emulator-5554"
case "$1" in
  shot) $E exec-out screencap -p > "$2" ;;
  keys)
    shift
    for k in "$@"; do
      case "$k" in
        text:*) $E shell input text "${k#text:}" ;;
        *) $E shell input keyevent "KEYCODE_$k" ;;
      esac
      sleep 0.25
    done
    ;;
  sh) shift; $E shell "$@" ;;
  *) echo "usage: $0 shot|keys|sh" >&2; exit 2 ;;
esac
