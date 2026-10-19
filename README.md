# TurboTranslate — a live conversation translator for D-pad/keypad phones

A standalone, keypad-only push-to-talk translator. One key per side of
the conversation — always English on one, a settable language on the
other — hold to talk, release to see the translation. Part of the same
"Turbo" family as TurboText and TurboVoice, purple-accented to match.

- **Right softkey (or the `9` key)**: hold while the English speaker
  talks, release — it transcribes (Groq's cloud Whisper) and translates
  into the other language.
- **Left softkey (or the `7` key)**: hold while the other speaker talks,
  release — it translates into English.
  The `7`/`9` aliases exist so this works on other D-pad phones whose
  softkeys send different (or no) codes. On the language picker screen
  specifically, `9` stays as a plain T9 search digit instead (see below)
  since aliasing it there would break searching for any language
  starting with W, X, Y, or Z.
- Every exchange lands in a scrolling transcript: English turns on the
  right, the other language on the left, styled like a texting app. The
  translation is shown large (that's the line whoever's reading the phone
  actually needs); the original transcription is shown smaller above it
  for reference.
- **`*` key > Change language**: opens a searchable language picker — type the digits for a
  language's name (T9-style) to filter, D-pad Up/Down to move the
  highlight, **center/OK only** to confirm (no softkey or number-key
  alias here — those digits are already spoken for by the search).
- **D-pad Up/Down**: steps through the transcript — jumps straight to a
  message that fits the screen, or scrolls gradually through a longer one
  so you can read all of it before it moves on to the next message. Pins
  to the newest message until you navigate away. **Center/OK**: copies
  the highlighted message's translation to the clipboard.
- The last 20 exchanges (spoken or photographed) are saved automatically
  and reloaded the next time you open the app — closing the app doesn't
  lose your conversation. Once there are more than 20, the oldest drops
  off as each new one comes in.
- **`#` key**: takes a photo (menus, signs, labels, etc.), sends it to a
  Groq vision model to read whatever text is in it, and adds the
  extraction + translation to the transcript, marked with a 📷 icon. The
  photo itself is never saved anywhere — the file in the app's cache is
  only used for that one upload. If the photographed text is already in
  English, it's translated to your configured "other language"; anything
  else found in a photo is translated to English, regardless of which
  language it turns out to be (so this works even for a third language
  neither softkey is currently set to).
- Text-only — nothing is read aloud on either side (Groq's TTS voice is
  English-only, so it can't speak the translated/non-English side).
- **DEL/Back**: cancels an in-progress recording if one's running,
  otherwise exits the app.
- **`*` key (or Menu)**: opens Options — Change language, Take photo,
  and Check for updates (see "Updates" below).

### Key map

| Key | What it does |
|---|---|
| Left softkey / `7` | Hold to talk in the other language → English |
| Right softkey / `9` | Hold to talk in English → the other language |
| `#` | Photo translate |
| `*` / Menu | Options (change language, photo, check for updates) |
| D-pad Up/Down | Step through the transcript |
| OK (center) | Copy the highlighted translation |
| Back / DEL | Cancel a recording, otherwise exit |

The right softkey would normally be Options on these phones (see
Flip-DumbPhoneGuide), but here both softkeys are push-to-talk, so
Options lives on `*` instead.

## Updates

TurboTranslate updates itself from this repo's GitHub Releases, the same
way Flip Launcher does. About once a week, when you open the app, it
checks for a newer version and asks whether to install it. You can also
check any time: press `*`, select **Check for updates**, press OK. A
Wi-Fi connection is preferred for the download; the prompt tells you if
you're on mobile data.

The first time you choose **Install**, Android asks whether TurboTranslate
may install apps:

1. The "Install unknown apps" screen for TurboTranslate opens. Press OK
   on **Allow from this source** to turn it on.
2. Press Back to return to TurboTranslate.
3. Press `*`, select **Check for updates**, press OK, then select
   **Install** again.

After the download, the system installer opens: select **Install**
(D-pad down to it, then OK). Your saved conversation is kept.

An update only installs over a copy signed with the same release key. If
the copy on your phone was built from Android Studio (a debug build),
uninstall it once and install the first release APK from the Releases
page by hand. Updates work automatically after that.

This app is separate from TurboText (the texting app) — different
package, different APK, its own Groq API key. They don't share any code
at build time, though a few files started as copies of TurboText's (the
voice-recording and translation helpers, the audio-silence trimmer).

## Step 1 — Install Android Studio (one-time, ~30-45 min)

1. Go to https://developer.android.com/studio and download Android Studio
   for your computer (Windows/Mac/Linux all work).
2. Run the installer and accept the defaults. When it first opens, let it
   download the "Android SDK" components it prompts you about — that's
   normal and can take a while on the first run.

## Step 2 — Open this project

1. Unzip the `TurboTranslate` folder somewhere on your computer.
2. Open Android Studio, choose **Open**, and select the `TurboTranslate`
   folder (the one containing `settings.gradle`).
3. Let it sync — accept any Gradle/AGP version-update prompt it offers.

## Step 3 — Set the Groq API key

The key isn't built into the app. It's read on the phone from the same
text file TurboVoice and TurboText use:

```
Internal storage/Turbo Key/groq_api_key.txt
```

If you already use TurboVoice or TurboText, the file is there and
TurboTranslate picks it up. Otherwise, open TurboTranslate once after
granting storage access (below) and it creates an empty file for you.
Paste your key (from https://console.groq.com/keys) into it on its own
line. You may need to restart the phone before the folder shows up over
USB.

TurboTranslate needs storage access to read that file. The first time
you open it, it asks:

- **Android 10 and older (e.g. the Kyocera DuraXV Extreme)**: a
  permission prompt appears. Select **Allow** and press OK.
- **Android 11 and newer**: the "All files access" list opens. Select
  **TurboTranslate**, press OK on **Allow access to manage all files**,
  then press Back.

Or grant it over ADB:
```
adb shell pm grant com.turbotranslate.app android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.turbotranslate.app android.permission.WRITE_EXTERNAL_STORAGE
```
(Android 11+: `adb shell appops set com.turbotranslate.app MANAGE_EXTERNAL_STORAGE allow`.)

For debug builds from Android Studio only, a `GROQ_API_KEY=...` line in
`local.properties` (gitignored, next to `settings.gradle`) is used as a
fallback when the phone has no key file. Release builds never include it,
because release APKs are published publicly.

## Step 4 — Build the APK

**Build > Build Bundle(s) / APK(s) > Build APK(s)**, then click "locate"
on the notification once it finishes. The file will be under
`TurboTranslate/app/build/outputs/apk/debug/app-debug.apk`.

## Step 5 — Get it onto the phone and install it

Same as TurboText: email/Drive the APK to yourself, open it on the phone,
enable "Install unknown apps" for whatever app you used to get it there
if prompted, then confirm Install. Accept the microphone permission
prompt the first time you hold a softkey to talk — you'll need to press
that softkey a second time after granting it, since the first hold is
consumed by the permission dialog.

## Publishing an update

Releases are published by hand: no CI is involved, and the signing key
never leaves your computer. The repo has to stay **public**, because the
app reads its Releases without logging in.

### One-time: create the release signing key

```
keytool -genkeypair -v -keystore turbotranslate-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias turbotranslate
```

Then create `keystore.properties` next to `settings.gradle`. It's
gitignored, so it never gets committed:

```properties
storeFile=C:/path/to/turbotranslate-release.jks
storePassword=...
keyAlias=turbotranslate
keyPassword=...
```

**Back up the `.jks` file and its passwords.** Android installs an update
only if it's signed with the same key as the installed app. If you lose
the key, every phone has to uninstall and reinstall.

### Each release

1. In `app/build.gradle`, raise `versionCode` by 1 and set `versionName`
   (e.g. `"1.1"`). Commit that.
2. Build the signed APK: **Build > Generate Signed Bundle / APK** (or run
   `gradlew assembleRelease`). The output is
   `app/build/outputs/apk/release/app-release.apk`.
3. On GitHub, go to **Releases > Draft a new release**. Create the tag
   `v<versionName>` (e.g. `v1.1`), attach `app-release.apk`, and click
   **Publish release**. Don't mark it as a draft or pre-release, because
   the app only looks at the latest full release. It compares that tag
   with its own `versionName`.

## Notes

- **Right softkey quirk on some devices**: logcat from the Kyocera DuraXV
  Extreme this was originally built for showed the phone's OS swallowing
  the *press* (`ACTION_DOWN`) of the right softkey before any app ever
  sees it, while still delivering the *release* (`ACTION_UP`) normally —
  the same general class of thing as the swallowed camera-key shortcut
  documented in TurboText's README, just for a different key. Since
  there's no way to know a press started until it's already over, the app
  auto-detects this per key: wherever `DOWN` does arrive, it's a normal
  hold-to-talk — press, speak, release. For a key where `DOWN` never
  shows up, it silently falls back to **tap-to-start / tap-to-stop**: one
  quick press-release starts recording, a second stops it and sends it
  off for translation. You don't need to configure anything — each key
  figures out its own mode automatically — but it does mean two keys can
  feel different to use (hold vs. two taps) depending on which mode they
  land in on a given phone. The `7`/`9` aliases go through the exact same
  auto-detection as their softkey counterparts.
- If a phone's keys send codes other than `KEYCODE_SOFT_LEFT` /
  `KEYCODE_SOFT_RIGHT` / `KEYCODE_7` / `KEYCODE_9`, the fix is in
  `TranslatorActivity.kt`'s `dispatchKeyEvent` — tell me what happens on
  the real device and I'll help track down the actual codes.
- This build requires internet access during the Android Studio build
  (not on the phone) so Gradle can download dependencies. The phone
  itself needs a data/wifi connection only at the moment you actually use
  voice or photo translation.
