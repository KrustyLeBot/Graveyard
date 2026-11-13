# Graveyard

A throwaway Android app for trying things out before they go into real apps. Nothing here is meant
to last: features come and go, and the app can change completely from one build to the next.
The interface is in French.

## What is being tested right now

- **Voix**: on-device French speech synthesis. Each voice is downloaded on demand, reads the same
  passage, and reports what it cost on this phone: generation time over audio time (under 1 means
  the phone generates faster than it reads), delay before the first sound, model load time, and
  the silences heard while waiting for the next sentence. The voice ticked here is the one the
  other tabs read with.
- **Livre**: import an EPUB (button, or "open with" from a file manager) and listen to a chapter.
  The sentence being read is highlighted; a tap reads from there. Reading goes on with the screen off.
- **Webtoon**: Poseidon Scans chapters with text recognition (blocks framed on the page, text
  listed on demand), the recognised text read aloud while the page follows the voice, automatic
  scrolling at a set speed, and background music from an audio file of the phone.

Everything runs on the phone. The only network traffic is the model downloads, the webtoon pages
and the update check.

## Voices

Models are fetched from Hugging Face into the app's private storage (`files/voices/`), not bundled:
the APK stays around 60 MB and a voice can be added to the list without the others being downloaded
again. They run with [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 1.13.8, the last version
that still has the espeak-ng front end Piper and Kokoro need.

| Voice | Download | Repository |
|---|---|---|
| Piper (Siwis, UPMC, Tom, Miro) | 63 to 77 MB each | `csukuangfj/vits-piper-fr_FR-*` |
| Kokoro (ff_siwis) | 143 MB | `csukuangfj/kokoro-int8-multi-lang-v1_0` |
| Supertonic 3 (10 voices) | 146 MB | `csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11` |
| Pocket TTS, French (voice cloning) | 380 MB | `haex-space/pocket-tts-sherpa-onnx`, reference voices from `kyutai/tts-voices` |

Piper and Kokoro also share 18 MB of espeak-ng data, downloaded once. Pocket TTS imitates a short
recording: four are provided, and any uncompressed WAV of 5 to 15 seconds can be used instead.

Each model was run once on a Linux PC with the same engine version and these settings, and produced
speech for a French sentence. How they sound, and how fast they are on a phone, is what the app is for.

## Text recognition

ML Kit's Latin text recogniser, bundled in the APK. A webtoon page is read in overlapping bands
because it is far taller than the recogniser accepts. Capitals are turned into sentence case and
watermarks are dropped before the text is read aloud.

## Builds and updates

Every push to `main` builds a release APK in CI (`.github/workflows/build-apk.yml`) and replaces
the rolling GitHub release `latest`, titled `Graveyard build N`. The app checks that release when it
opens and installs the new build over itself, like the other apps of the suite.

The APK is signed with `signing/graveyard-test.jks`, **a key committed to this repository on
purpose** (password `graveyard`). It only exists so that every build installs over the previous one
without any secret to configure. It protects nothing: anyone can sign an APK with it, so install
builds from this repository's release only, and never reuse this key for a real app.

## Build locally

```
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

Needs JDK 17 or newer and the Android SDK (platform 35). The APK lands in
`app/build/outputs/apk/release/`.

## Code

Code, comments and this file are in English; only the interface is in French. The EPUB parser, the
Poseidon Scans source and the update kit are copies from Grimoire.
