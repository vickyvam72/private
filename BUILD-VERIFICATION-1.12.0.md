# Lumi Signal 1.12.0 — Build Verification

## Release identity

- Application ID: `com.lumisignal.idxscreener`
- Version name: `1.12.0`
- Version code: `11200`
- Minimum Android: API 28
- Target/compile Android: API 35

## Completed checks

- `testDebugUnitTest`: 44 tests, 0 failures, 0 errors.
- Release Kotlin/Java compilation: passed.
- Room schema generation: schema version 6 generated.
- R8 minification and resource shrinking: passed.
- Release APK packaging: passed.
- APK Signature Scheme v2 verification: passed with one signer.
- ZIP alignment verification: passed.
- Manifest/package metadata verified with Android build tools.

Tests include IDX tick rounding, ten independent strategy evaluations, unavailable-data behavior, strict Top 5, broker-flow aggregation, historical parser behavior, full-universe response parsing, market-clock rules, Portfolio response parsing, stable audit state transitions, same-candle ambiguity, running-trade event ordering, and Telegram/history invariants.

## Build-environment note

Android Lint Vital was excluded only in this isolated build environment because several lint-only Maven artifacts were unavailable from the local dependency mirror. Release compilation, R8, signing, unit tests, APK metadata, and binary verification completed successfully. A normal connected Android Studio/Gradle environment can run `./gradlew lint assembleRelease`.

## Runtime validation boundary

No user Stockbit token, PIN, Telegram token, or chat ID is included in the source or APK. Therefore authenticated endpoint behavior, account entitlements, Portfolio contents, push notification delivery, and OEM background survival must be smoke-tested on the user's device after login.
