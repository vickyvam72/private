# Lumi Signal - IDX Screener

Android application built with Kotlin, Jetpack Compose, Material 3, Room, WorkManager, a one-minute foreground audit service, Android Keystore, live IDX-universe discovery, an experimental read-only Stockbit WebView connector, optional Telegram Bot API, ten strategy-specific screeners, immutable signal snapshots, and performance statistics.

Version 1.12.3 keeps the separate, optional read-only Portfolio module. A Stockbit PRO session is mandatory for the ten strategies because the complete scoring model uses broker/bandar confirmation. Stockbit Sekuritas portfolio access uses its own session, unlocked by a 4–8 digit PIN that is sent once to Stockbit and never stored. The application contains no buy, sell, amend, cancel, deposit, or withdrawal endpoint. Version 1.12.3 uses Stockbit's inclusive broker date ranges, adaptive backfill pacing, technical prefiltering, classified broker failures, and prevents candidates without verified broker data from entering Top 5.

## Build

1. Open the project in Android Studio Ladybug or newer.
2. Use JDK 17 and Android SDK 35.
3. Build `app` with `./gradlew assembleRelease` or Android Studio.

Package: `com.lumisignal.idxscreener`  
Minimum Android: 9 (API 28)

The downloadable source archive deliberately excludes the private release keystore
and `keystore.properties`. Provide your own signing configuration when producing a
new distributable build; the supplied APK is already release-signed.

## Data integrity

- No ticker or signal result is hardcoded. The screening universe is discovered live, then every candidate is recalculated from authenticated Stockbit history.
- TradingView and IDX are discovery fallbacks only. All strategy OHLCV, traded value, transaction frequency, current audit price, and broker flow are read from Stockbit.
- Stockbit historical summary is paged at twelve sessions per request. Lumi fetches enough pages for the 60-session baseline and rejects incomplete value/frequency history.
- Stockbit login runs inside an isolated WebView. Only allowlisted Stockbit authentication responses are observed; form fields, passwords, CAPTCHA, trading PINs, and order routes are excluded. Access/refresh tokens are encrypted by Android Keystore and excluded from backup.
- The Portfolio tab reads total equity, cash, buying power, withdrawable balance, settlement fields, holdings, open orders, transaction history, realized P/L, and trading performance when those fields are present. Unknown response keys remain unavailable rather than becoming zero. Securities access/refresh tokens use separate encrypted slots and are deleted when Portfolio is locked or Stockbit is logged out.
- Stock detail pages request current orderbook/spread, UMA or special-notation flags, corporate-action status, shareholder context, and trade-book context. These optional private-API fields are displayed as supplemental evidence and never silently change a strategy score.
- This is a read-only experimental private-web-API connector adapted from the unofficial `stockbit-mcp` community project, not an official Stockbit SDK. If Stockbit changes or refuses its API, broker data remains unavailable rather than being fabricated.
- Broker analysis uses 1D/3D/5D/10D aggregates plus ten single-session snapshots where persistence is required. The snapshots make the 6-of-10 persistence rule testable instead of inferring persistence from one aggregate. Broker evidence required by a strategy remains mandatory.
- Full official IDX universe and official broker summary may require licensed IDX/redistributor data. The app never substitutes dummy data when a source is unavailable.

## Signal methodology — version 1.12.3

Home requires one of ten strategies: Quiet Accumulation, Absorption at Support, Broker Accumulation Persistence, Frequency Creep, Broker–Price Divergence, Volatility Compression, Shakeout–Spring Reclaim, Markup Ignition, Reaccumulation After First Markup, or Breakout Retest Confirmation. Core criteria are hard gates: a high total score cannot compensate for missing evidence that defines the pattern.

The Home screen ranks the latest market batch independently per strategy. It stores
at most five candidates that actually pass the configured 8/10 or 9/10 strategy
threshold. The list can contain zero to five shares and is never padded by relaxing
the rules. A ticker may appear in several strategies, but each strategy-ticker
snapshot remains independent.

The Screener page is reserved for one ticker entered by the user. It displays all
ten strategy scores in one overview. Selecting a score opens that strategy's
filter evidence, structural trade plan, explanation, indicators, and candlestick
context; market Top 5 cards are shown only on Home.

Network stages are bounded. Every discovered IDX equity is evaluated instead of an
alphabetical/liquidity sample. The first Stockbit synchronization backfills the
required history; later sessions merge only the newest historical page into a
session-aware local cache. Broker-heavy strategies use one true 10-session aggregate
per ticker for market-wide seeding, then only the required 1D/10D aggregates or
10/20-session daily history for their shortlist. Market progress is checkpointed after every concurrency batch. If Android
stops a worker during a mobile/Wi-Fi handoff or resource pressure, one controlled
recovery continues from the checkpoint instead of silently restarting from zero.
The user can cancel an active run from the progress card.

The last incomplete trading day is excluded before the Jakarta market close. Entry, TP, and SL are rounded through the modular IDX tick-size engine. Audit counts expiry from completed Stockbit daily sessions and evaluates Stockbit's current-session orderbook OHLC every minute. Conflicting TP/SL touches whose order cannot be proven remain `AMBIGUOUS`, never a win.

Free-float turnover and historical offer-replenishment were removed because the connector cannot prove their defining evidence. Strategy 5 is now Broker–Price Divergence; strategy 10 is Breakout Retest Confirmation. Frequency Creep and Markup Ignition use Stockbit's daily frequency. UMA/FCA, corporate-action completeness, and historical spread remain explicit global data limitations.

Background audit covers every valid Top 5 setup across all ten strategies. The
same ticker retains independent entry, TP, SL, and audit status per strategy,
while History groups those setups under one ticker card for the same reference
date. Audit status changes produce Android notifications only. Telegram receives
only signals explicitly selected by the user, and Telegram History uses that same rule.

## Credentials

Telegram and Stockbit access/refresh tokens are AES/GCM-encrypted with a non-exportable Android Keystore key. They are excluded from Android backup and never written to Room or activity logs.

## Tests

Unit tests cover all price-fraction boundaries, valid rounding,
entry/holding/TP transitions, same-candle ambiguity, terminal idempotency, the
7-win/3-loss = 70% win-rate rule, Stockbit login-response token policy, and IDX
universe response parsing/filtering. The single-ticker analysis path refuses to
fabricate a result when its broker summary is unavailable. Additional tests cover
broker-mandatory independent ranking modes, aggregated broker flow/foreign detail,
broker evidence persistence in screening snapshots, 1D/10D foreign-flow
separation, live-session entry detection without intraday expiry inflation, and
compact K/M/B volume formatting. Seven strategy-engine tests verify ten independent
evaluations, honest unavailable-frequency handling, strict no-padding Top 5,
complete unique strategy descriptions, missing optional metadata handling, and
explicit UMA blocking;
three market-clock tests verify the regular, Friday, break, and weekend schedules.

## Release identity

Version 1.12.3 uses `versionCode 11203` so Android installs it above prior builds. It retains the guarded WorkManager/foreground-service startup and independent Stockbit PRO identification from version 1.12.1.
Screening evaluates the full discovered IDX universe, caches completed Stockbit
OHLCVF by session date, incrementally refreshes one page, and reuses the first-pass
series during broker enrichment. Detailed twenty-session broker history is requested
only for Absorption/Spring event candidates; persistence/divergence uses ten sessions. Home owns the ten
strategy-specific Top 5 views and candidate Detail actions, while Screener analyzes
only the ticker entered by the user across all strategies. Stockbit login remains
mandatory through the in-app WebView; Telegram remains optional. The one-minute
foreground audit monitors every strategy during BEI sessions and preserves
the actual audited entry price and TP/SL result without sending audit events to Telegram.
