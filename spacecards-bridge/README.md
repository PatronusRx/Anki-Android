# `:spacecards-bridge`

Unity-facing native plugin for **Space Cards VR**. Packaged as an `.aar`
that the Unity Quest project bundles under `Assets/Plugins/Android/`.

The bridge wraps `:libanki` (and its transitive Rust `rsdroid` backend) behind
a small JNI-friendly Kotlin facade, plus the WebView -> SurfaceTexture pipeline
that renders Anki card HTML into a Unity OES texture.

See `/Users/lucas/.claude/plans/there-are-two-purring-starfish.md` for the full
phased plan.

## Public surface

Single entry point: `com.spacecardsvr.bridge.SpaceCardsBridge` (Kotlin `object`,
all methods `@JvmStatic` so Unity can call them via `AndroidJavaClass.CallStatic`).

Long-running operations (`loginAnkiWeb`, `fullSync`) take a `BridgeCallback`
that Unity implements via `AndroidJavaProxy`.

## Phase mapping

Methods carry inline `TODO("P<n>: ...")` markers pointing at the phase that
implements them:

| Phase | Methods                                                                | Status                     |
|-------|------------------------------------------------------------------------|----------------------------|
| P1    | `init`, `openCollection`, `closeCollection`, `listDecks`               | implemented                |
| P3a   | `createCardSurface`, `renderCard`, `injectTouch`, `destroyCardSurface` | implemented (Kotlin only)  |
| P3b   | C++ native plugin — OES texture + render-thread `updateTexImage`       | not started (needs NDK)    |
| P3c   | Unity shader — `samplerExternalOES` sampling in URP                    | not started (Unity-side)   |
| P4    | `selectDeck`, `deckCounts`, `nextCardJson`, `showAnswer`, `answerCard` | implemented                |
| P5    | `loginAnkiWeb`, `fullSync` (upload/download; `auto` not yet supported) | implemented                |

## Build

```
./gradlew :spacecards-bridge:assembleRelease
```

Output: `spacecards-bridge/build/outputs/aar/spacecards-bridge-release.aar`.
Drop alongside its transitive AARs (`libanki`, `anki-common`, `common`,
`compat`, `anki-android-backend`) into the Unity project's
`Assets/Plugins/Android/`.

## Not shipped

The legacy `:AnkiDroid` Android app is preserved in-tree as a reference for
auth flows and template handling, but it is no longer the shipped binary.
The Quest release is a Unity APK built around this bridge.
