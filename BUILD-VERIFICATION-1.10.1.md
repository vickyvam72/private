# Build Verification — Lumi Signal 1.10.1

Verified on 2026-10-07 with JDK 17, Android SDK 35, Build Tools 35.0.0,
Gradle 8.9, and Android Gradle Plugin 8.7.3.

## Results

- `testDebugUnitTest`: PASS — 37 tests, 0 failures, 0 errors, 0 skipped.
- `assembleRelease`: PASS — R8 minification and resource shrinking completed.
- APK package: `com.lumisignal.idxscreener`.
- Version: `1.10.1` (`versionCode 11001`).
- Minimum Android: API 28; target API 35.
- ZIP integrity: PASS.
- Android zip alignment: PASS.
- APK signature: PASS — v2, RSA 4096, certificate CN `Lumi Signal`.

## Behavioral checks

- The background audit calls `auditAll()` and emits Android notifications for
  ENTRY, TAKE PROFIT, STOP LOSS, expiry, or ambiguity transitions.
- The audit path contains no Telegram send call and cannot mark a signal as sent
  to Telegram.
- Telegram History filters only `telegramSent`; it therefore contains only a
  signal the user explicitly sent.
- Missing optional UMA, spread, or corporate-action response keys are disclosed
  as data limitations instead of erasing all strategy results. An explicit bad
  value (for example UMA active) still blocks the candidate.
- Completed Stockbit OHLCVF series are cached for six hours and reused between
  the technical and broker-enrichment stages. Both stages use bounded parallelism.
- Portfolio lot data is no longer presented as a fabricated share count when the
  Stockbit payload contains only lots.

## Lint note

The complete Gradle lint task could not be executed in the isolated build
environment because its lint-only Groovy, Kotlin 1.9.20, and Apache HttpClient
artifacts were unavailable from the permitted dependency proxy. Release
compilation, unit tests, R8, resource shrinking, APK packaging, integrity,
alignment, and signature verification all completed successfully. This is an
environmental dependency limitation, not a reported lint finding.

## Security and reproducibility

The source archive excludes `keystore.properties`, private `.jks`/`.keystore`
files, captured sessions, tokens, build directories, and local Gradle caches.
The Gradle configuration supports an unsigned release build when no signing
configuration is provided; distributors should sign with their own protected key.
