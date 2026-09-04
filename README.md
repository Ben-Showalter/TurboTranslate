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
- **`*` key**: opens a searchable language picker — type the digits for a
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

The key is no longer pasted directly into a source file — it's read at
build time from `local.properties` (the same gitignored file Android
Studio already uses for your SDK path), so it's never committed if this
project's source gets published, same as TurboText/TurboVoice.

Your existing key has already been carried over, so this build works as
soon as it opens. To change it later, open `local.properties` (next to
`settings.gradle`, alongside the `sdk.dir` line) and edit:
```
GROQ_API_KEY=gsk_your_actual_key_here
```

**About "the turbo key folder"**: I don't have visibility into how your
other Turbo apps' key storage is actually structured, so I built this on
the standard `local.properties` convention instead of guessing wrong.
`app/build.gradle` also already supports a second option — set
```
TURBO_KEY_FOLDER=C:\path\to\your\shared\folder
```
in `local.properties`, and it'll read a `groq.key` file (just the key,
nothing else) from inside that folder instead, if `GROQ_API_KEY` isn't
already set directly. Tell me the actual folder path and file format
you're using and I'll adjust `app/build.gradle`'s lookup to match exactly
rather than the placeholder convention above.

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
