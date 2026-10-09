# Lumi Signal 1.12.2 — build verification

Build date: 8 October 2026 (UTC)

## Fixes in this release

- A one-session Stockbit broker-summary request now sends the next calendar date as the API `to` boundary while retaining the requested trading date in Lumi's model. This prevents a same-date range from becoming an empty interval during screening detail and manual analysis.
- If Stockbit rejects the primary custom-date request shape, Lumi retries once with `BROKER_SUMMARY_PERIOD_UNSPECIFIED`.
- Broker caches use the new `FLOW_V9` namespace so an old cached `0/20` result is not reused after upgrading.
- Corporate-action parsing now reads the explicit `active`, `text`, and `detail` fields. Asset/icon URLs and inactive action payloads are not shown as action descriptions.

## Automated checks

- `testDebugUnitTest`: 53 tests, 0 failures, 0 errors, 0 skipped.
- `assembleDebug`: successful.
- `assembleRelease`: successful with R8 and resource shrinking enabled.
- APK identity: `com.lumisignal.idxscreener`, version code `11202`, version name `1.12.2`.
- Release signature: APK Signature Scheme v2, one RSA-4096 signer.
- Signer certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`.
- APK zip alignment: successful (`zipalign -c -P 16 -v 4`).
- APK SHA-256: `f6fb299f6de7803f04546b5ba065a0791cb7f3ea9bef48e2f8a690be1d81593f`.
- Merged release manifest retains WorkManager's `SystemForegroundService` with `dataSync` foreground-service type.

## Runtime boundary

The private Stockbit endpoint could not be exercised with the user's authenticated session in this build environment. The range correction is backed by the observed failure pattern (multi-day broker seed succeeds while every one-day detail request returns empty), the request-shape inspection, and focused unit tests. Final confirmation still requires one authenticated device run: analyse BBCA and start a short screening to verify that broker-detail counts populate under the user's current Stockbit account/API response.
