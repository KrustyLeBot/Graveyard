# Graveyard

A throwaway Android app for trying things out before they go into real apps. Nothing here is meant
to last: features come and go, and the app can change completely from one build to the next.
The interface is in French.

Right now the app is empty: it only shows its build number and updates itself.

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
./gradlew :app:assembleRelease
```

Needs JDK 17 or newer and the Android SDK (platform 35). The APK lands in
`app/build/outputs/apk/release/`.

## Code

Code, comments and this file are in English; only the interface is in French. The update kit
(`suite/`) is a copy of the one in the other apps of the suite.
