# Lumi Signal 1.7.0 — Build Verification

Verified on 3 October 2026 with JDK 17, Gradle 8.9, Android SDK 35, and Build Tools 35.0.0.

## Results

- Unit tests: 30 passed, 0 failed, 0 skipped.
- Android lint: 0 errors, 35 non-blocking warnings.
- Release build: successful with R8 code shrinking and resource shrinking.
- Database: Room schema version 5 exported; non-destructive migration 4→5 included.
- APK alignment: verified.
- APK signature: verified with APK Signature Scheme v2 and one RSA 4096-bit signer.
- Signer certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`.
- Version: `1.7.0` (`versionCode 10700`).
- APK SHA-256: `0827d5a063b975d462a0d219f9dccc6dfeb0fa2a7131fe60afd64250bf53669b`.

## Functional verification scope

The automated suite covers strategy independence, strict Top 5 behavior, audit transitions and actual entry prices, TP/SL ambiguity, market-hour scheduling, Stockbit token policy, ranking, persistence serialization, and strategy-description completeness. Live Stockbit and Telegram delivery still require a configured Android device and valid user credentials.
