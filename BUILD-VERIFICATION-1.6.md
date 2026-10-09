# Lumi Signal 1.6.0 — Build Verification

Verified on 3 October 2026 with JDK 17, Gradle 8.9, Android SDK 35, and Build Tools 35.0.0.

## Commands

```text
gradle --no-daemon testDebugUnitTest
gradle --no-daemon lintDebug assembleRelease
zipalign -c -v 4 app-release.apk
apksigner verify --verbose --print-certs app-release.apk
```

## Results

- Unit tests: 29 passed, 0 failed, 0 skipped.
- Android lint: 0 errors, 34 non-blocking warnings.
- Release build: successful with R8 code shrinking and resource shrinking.
- APK alignment: verified.
- APK signature: verified with APK Signature Scheme v2, one RSA 4096-bit signer.
- Signer certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`.
- Version: `1.6.0` (`versionCode 10600`).
- APK SHA-256: `7eaa1e320bbc3a32a239cb76a88518fd14b205fb2ddb205f7ad7f6710c9e588e`.
