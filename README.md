# Zap

A fast IPTV player for Android TV, for subscriptions that sign in with a server URL, username and password
(Xtream Codes). Built for one TV: a Tornado on Android 11 (MediaTek m7332, 32-bit, 1.7 GB RAM, 1080p interface).

## Why it is fast

- **The whole catalog lives on the TV.** Browsing and search read a local database, not the provider's server.
  A daily background refresh downloads the lists first, then swaps them in all at once.
- **Instant search.** A full-text index, with a remote-friendly keyboard always on screen. Case, accents and Arabic
  spelling variants (أ/إ/ا, ة/ه, ى/ي) all match. Results update on every key press.
- **Light interface.** Plain Android views and recycled lists, with no heavy UI framework. Long lists load 60 rows
  at a time, and posters are decoded at display size in 16-bit color.
- **Quick playback.** Live starts after half a second of buffer. Hardware decoders do the work, and a decoder that
  fails on a stream falls back to the next one. Dropped streams reconnect by themselves.

## Being polite to the provider

Providers answer bursts of requests with HTTP 429 and can block playback for a while. So program-guide requests go
one at a time and spaced out, streams open no faster than one every 800 ms, and after a 429 the app sends nothing
until the provider's wait is over. The player does not retry a 429 by itself. `adb logcat -s ZapNet:W` shows request
types and status codes, never URLs.

## Remote

| Where | Button | Does |
|---|---|---|
| Lists | OK | Open or play |
| Lists | Hold OK | Add to or remove from favorites |
| Lists | Back | Step left: list, categories, menu, then exit |
| Live list | Right | Catch-up: past programs, on channels that have it |
| Live | Up / Down, Channel +/- | Change channel |
| Live | Left | Channel list |
| Live | Right | Last channel |
| Live | OK | Show or hide channel info |
| Live | Hold OK, or Menu | Options: audio, subtitles, sleep |
| Movie, episode | OK | Pause or play |
| Movie, episode | Left / Right | Seek. Holding goes faster |
| Movie, episode | Down | Options: audio, subtitles, sleep |

## Build and install

    export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    ./gradlew :app:testReleaseUnitTest :app:assembleRelease
    dev/install-tv.sh

Create `local.properties` first if it is missing: `sdk.dir=/opt/homebrew/share/android-commandlinetools`.

## Testing without a subscription or the TV

    dev/make_media.sh                                  # test video, once
    python3 dev/fake_xtream.py                         # 80,000-title fake server, login test / test
    emulator -avd zaptv -no-window -no-audio           # server address in the app: 10.0.2.2:8099
    dev/emu.sh keys DPAD_DOWN ENTER                    # drive the emulator; never touches a real TV

## Hiding and locking

Settings, Manage categories steps each category through Shown, Hidden and Locked. Hidden and still-locked categories
also stay out of All, Recently added, Favorites and search. Unlocking lasts until the app closes. The PIN is stored as
a salted hash; a forgotten PIN needs a sign-out, which also clears favorites and resume points.

## Credentials

The login is encrypted with a key in the Android Keystore and is never logged. App backups are off. Providers put
the username and password in stream URLs, so on a plain `http://` server they cross the network unencrypted. That
comes from the Xtream protocol and applies to every player using it.
