# NostrShot — Monorepo

Two native apps for minimal Nostr publishing, one repo.

* `mac/` — macOS menu-bar composer (Swift, `NostrBar`). From `~/Projects/NostrShot`.
* `android/` — Android publisher (Kotlin/Compose). From `~/Projects/NostrShotAndroid`.

Both are single-purpose capture tools: open -> type -> publish -> gone. No feeds, no timeline.

## Structure

```
NostrShot/
  mac/       Swift Package (Package.swift, Sources/NostrBar)
  android/   Gradle project (app/, settings.gradle.kts)
  README.md  this file
```

## Quick start

**Mac:**
```bash
cd mac
swift build
swift run        # or build the app bundle with scripts/build_app.sh
```

**Android:**
```bash
cd android
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

See `mac/README.md` and `android/README.md` for platform details, `mac/ARCHITECTURE.md` / `android/ARCHITECTURE.md` for design.

## Ignored
* `NostrShot_Chrome` discarded, not included.

