# Build Verification — Lumi Signal 1.11.0

Verified on 2026-10-07 with JDK 17, Android SDK 35, Build Tools 35.0.0,
Gradle 8.9, and Android Gradle Plugin 8.7.3.

## Results

- `testDebugUnitTest`: PASS — 40 tests, 0 failures, 0 errors, 0 skipped.
- `assembleRelease`: PASS — R8 minification and resource shrinking completed.
- APK package: `com.lumisignal.idxscreener`.
- Version: `1.11.0` (`versionCode 11100`).
- Minimum Android: API 28; target API 35.
- ZIP integrity and Android zip alignment: PASS.
- APK signature: PASS — v2, RSA 4096, certificate CN `Lumi Signal`.

## Behavioral verification

- The 360-ticker capacity sample is removed. Screening iterates every distinct
  equity returned by the current IDX universe discovery.
- Initial OHLCVF synchronization backfills 72 completed rows; later session
  buckets fetch page one and merge it into the stored series.
- Before 16:15 Asia/Jakarta the current candle is excluded. From 16:15 onward it
  is eligible only if Stockbit has actually returned it.
- A market-wide 10-session broker aggregate seeds broker-heavy strategies.
  Detailed daily history then uses dates proven by Stockbit candles rather than
  probing guessed weekdays.
- Missing broker data does not erase all ten scores. Strategies 2, 3, 5, and 7
  remain incomplete without their required historical broker evidence.
- BBCA and other single-ticker analyses preserve the specific broker error or
  entitlement response and still display technical strategy scores.
- Background audit selects only stored, active Top 5 strategy signals. It uses
  session-cached historical candles and repeats only current Stockbit session
  OHLC/orderbook per unique ticker each minute.
- The audit path has no Telegram send operation. Telegram History remains based
  solely on explicit user sends.

## Environment limitations

- No user Stockbit token was available in the build environment. Live BBCA
  broker entitlement and payload behavior therefore require an on-device login
  test; the application now exposes the actual HTTP/response reason if it fails.
- Full Gradle lint still depends on lint-only artifacts unavailable from the
  permitted dependency proxy. Compilation, tests, R8, packaging, integrity,
  alignment, and signature validation completed successfully.

## Security

The source archive excludes `keystore.properties`, private signing keys,
captured sessions, tokens, build output, and local Gradle caches.
