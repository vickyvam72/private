# Lumi Signal 1.12.1 — Build Verification

Build date: 7 October 2026

## Result

- Release build: successful (`assembleRelease`).
- Debug unit tests: 49 passed, 0 failed, 0 skipped.
- APK package: `com.lumisignal.idxscreener`.
- Version: `1.12.1` (`versionCode 11201`).
- Minimum / target SDK: 28 / 35.
- APK signature: valid APK Signature Scheme v2, one RSA-4096 signer.
- Signer certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`.
- ZIP alignment: verified successfully, including 16 KiB page alignment.
- Release APK SHA-256: `c8d726364dfd487b9244e096ed5773bea2968cf6cab186a53e32b234a6df841c`.
- Merged manifest: WorkManager `SystemForegroundService` declares `dataSync`.

## Maintenance changes

- Basic Stockbit login validation now uses ordinary market data, not a PRO-only broker endpoint.
- Stockbit PRO entitlement is reported independently as `PRO`, `NON_PRO`, or `UNKNOWN`.
- All ten strategies, manual ticker analysis, and background audit require verified PRO broker access.
- Empty broker data stays `UNKNOWN`; it is not falsely classified as non-PRO.
- Screening is gated before WorkManager is enqueued.
- WorkManager, audit service, boot restore, and startup paths guard foreground-service and database failures so they return a user-visible error instead of terminating the process.
- WorkManager's foreground service is explicitly merged with the Android 14+ `dataSync` type.

## Test limitation

This environment has no physical Android device or emulator image. Compilation, JVM unit tests, R8 release packaging, signing, alignment, version metadata, and merged-manifest checks passed. Final Stockbit login/PRO detection and device-specific foreground-service behavior should still be confirmed on the target phone.
