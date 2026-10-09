# Lumi Signal 1.12.3 — Build Verification

Build date: 8 October 2026

## Result

- Release build: PASS (`assembleRelease`)
- Debug build: PASS (`assembleDebug`)
- Unit tests: 53 passed, 0 failed, 0 errors
- Android lint vital: PASS
- APK alignment: PASS (`zipalign -c -P 16 -v 4`)
- APK signature: PASS (APK Signature Scheme v2)
- Package: `com.lumisignal.idxscreener`
- Version: `1.12.3` (`versionCode 11203`)
- Minimum / target SDK: 28 / 35
- Release certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`
- APK SHA-256: `ac4ead10ba2c69a2b56c374d1f9a789d4ed0f80db9f3e877dc0189a288f61394`

## Corrections verified in source

- Stockbit broker date ranges are inclusive. A daily request now sends the same `from` and `to` date.
- Broker requests no longer combine `period=UNSPECIFIED` with explicit dates.
- Broker traffic uses a dedicated two-slot limiter, global start spacing, bounded retries, and adaptive cooldown after HTTP 429.
- Broker failures are classified as authentication, entitlement, rate limit, timeout, empty, incomplete, network, or unknown and are shown in screening diagnostics.
- Screening performs an upper-bound technical prefilter before broker seed requests, so obvious technical misses do not consume broker-history calls.
- Detailed broker analysis is performed only for candidates with a valid ten-session broker seed.
- Missing optional market context no longer marks a valid broker detail request as failed.
- A strategy cannot pass or enter Top 5 unless its required broker calculation is available and complete.
- Manual ticker analysis still displays diagnostic scores for all ten strategies and clearly marks incomplete broker calculations.
- Cache keys were moved to `FLOW_V10`; transient rate-limit/network failures have short cache lifetime.
- Database migration 6→7 removes only old screening results/runs and invalid `FLOW_V9` broker cache entries. Saved signals, audit state, Telegram history, and portfolio data are retained.

## Runtime verification still required

The build environment did not contain an authenticated user Stockbit session. Therefore the following must be confirmed on the target phone/account after installation:

1. BBCA broker history reaches 20/20 sessions with the account's actual Stockbit entitlement.
2. A full IDX scan completes without HTTP 403/429 for that account and network.
3. Background audit notifications continue after the device-specific battery optimization exemption is granted.

The app now exposes failure categories so any remaining account entitlement or Stockbit server throttling can be distinguished from parsing and date-range errors.
