# Zap

A fast IPTV player for Android TV, for subscriptions that sign in with a server URL, username and password
(Xtream Codes). Zap is only a player: it comes with no channels, so you need your own subscription.

## Install on your TV

You need an ARM-based Android TV on Android 8 or newer. Zap has only been tested on a Tornado TV (Android 11) and an
Android 12 TV emulator, so other models may behave differently.

1. On the TV, install **Downloader** from the Play Store.
2. Open Downloader and enter this address:

       https://github.com/imoeadel/zap-lightweight-iptv/releases/latest/download/zap.apk

3. When asked, allow Downloader to install unknown apps, then choose **Install**.
4. Open Zap and sign in with your subscription's server URL, username and password.

No Downloader? Download `zap.apk` from the [latest release](https://github.com/imoeadel/zap-lightweight-iptv/releases/latest)
on a computer, copy it to a USB stick, and open it with a file manager on the TV.

**To update:** repeat the same steps. Your login and favorites stay. Zap doesn't update itself.

**Voice search:** press Voice search, then use the mic on the TV's keyboard. This depends on the keyboard and remote
that came with your TV, so it may not work on every model.

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

## Build from source

Needs Java 17 and the Android SDK. Point `JAVA_HOME` and `ANDROID_HOME` at them, then:

    ./gradlew :app:testReleaseUnitTest :app:assembleRelease
    adb connect <tv-ip>:5555
    adb install -r app/build/outputs/apk/release/app-release.apk

Builds use the debug key unless a `keystore.properties` file is present. A self-built copy won't update over the
published APK, because the signatures differ: uninstall it first, which also clears the login and favorites.
`dev/install-tv.sh` is the maintainer's own install script and relies on tools outside this repo.

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
