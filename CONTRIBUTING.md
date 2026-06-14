# Contributing

Thanks for your interest in the Upload Manager SDK.

## Project layout

```
upload-manager/   the SDK (Android library, namespace dev.uploadmanager)
sample/           Compose demo app, runs against the Firebase Emulator Suite
firebase/         security-rules templates + emulator config
docs/             tech-spec, revision docs, and test plans
```

## Building & testing

- Unit tests (no device): `./gradlew :upload-manager:test`
- Assemble everything: `./gradlew assembleRelease assembleDebug`
- Instrumented tests (needs an emulator + the Firebase emulators):
  ```
  cd firebase && firebase emulators:start --project demo-upload-manager
  ./gradlew :upload-manager:connectedDebugAndroidTest
  ```

CI mirrors this: a fast job runs unit tests and compiles everything (including
the instrumented sources); a separate job runs the instrumented suite on an
emulator with the Firebase emulators.

## Conventions

- Keep decision logic pure and unit-tested (see `ConcurrencyPolicy`,
  `RetryClassifier`, `SourceChecker.compare`); push framework/IO to the edges.
- Room is the source of truth; Firestore is a best-effort projection — nothing
  in the Firestore path may block or fail an upload.
- New device-condition or retry behavior should come with a pure unit test, and
  any user-visible behavior with an instrumented CUJ test where feasible.
- Match the surrounding style; favor small, single-responsibility components.

## Pull requests

- Reference the relevant spec section or revision doc in the description.
- Make sure `./gradlew :upload-manager:test` is green; the maintainers will run
  the emulator suite.
