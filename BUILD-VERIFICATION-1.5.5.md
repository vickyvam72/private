# Build Verification — Lumi Signal 1.5.5

## 1.5.5 source verification — 21 September 2026

- Version metadata: `1.5.5` (`versionCode 10505`).
- Focused Top 5 modes now rank by 100% of the selected category subscore.
- Distribution/chasing penalties apply only to the balanced Overall ranking.
- Regression coverage verifies IRSX Broker 74 ranks above AISA Broker 69.
- Added candlestick + EMA20/EMA50 + RSI14 + ATR14 to manual Screener results.
- Replaced editable screening weights with automatic balanced/focused Top 5 weights.
- Audit now persists one valid-fraction actual entry price and uses it for returns.
- Telegram audit output now presents detailed HOLDING, TP, and SL report cards.
- Added current-session 5-minute entry audit and 1-minute ambiguity resolution.
- Expiry remains based on completed trading sessions, not intraday bar count.
- Telegram signal/audit messages are emitted left-aligned and omit the former
  yellow historical-data disclaimer.
- Candidate chart replaces the regression trendline with EMA20, EMA50, RSI14,
  and ATR14.
- Screening balances the detailed universe across active shares priced up to
  Rp1,000 and Rp5,000, then applies an explicit activity gate before Stockbit.
- The full 23-test release unit suite completed with zero failures.
- Release compilation, Room schema export, R8, resource shrinking, signing,
  packaging, and lint-vital checks completed successfully.

## Release artifact

- Package: `com.lumisignal.idxscreener`
- Version: `1.5.5` (`versionCode 10505`)
- Minimum Android: API 28 (Android 9)
- Target/compile SDK: API 35
- APK SHA-256: `2e47d95f6ffb147d0d2a448fba18ddfea295827b40b54ae5f0d9cfe0df6054d8`
- APK Signature Scheme v2: verified
- Signer certificate SHA-256: `d1c12ac307f7d351beea11576718da46fc45a1b4b1ab4588936198ed7fc11c49`
- RSA key size: 4096 bits
- ZIP alignment: verified

## Automated checks

- `testReleaseUnitTest`: 23 tests, 0 failures, 0 errors, 0 skipped
- Kotlin/Java release compilation: successful
- `lintVitalRelease`: successful
- R8 code shrinking and resource shrinking: successful
- Release signing validation: successful

The unit tests cover IDX tick-size boundaries/rounding, audit state transitions,
same-candle TP/SL ambiguity, audit idempotency, win-rate denominator behavior,
Stockbit token URL/parsing policy, IDX-universe response parsing, mandatory-broker
independent ranking dimensions, aggregate broker-flow interpretation,
1D/10D foreign-flow snapshot integrity, automatic per-mode weights, actual entry
selection from candle open/range, and valid IDX-fraction enforcement.

## 1.5.2 foreign-period clarity and chart presentation

- Stockbit foreign flow is fetched and persisted separately for 1D and cumulative
  10D. The 1D tile is directly comparable with Stockbit Net F on the reference date;
  the 10D tile is explicitly labelled cumulative.
- Home ranking selection is a dropdown containing Overall, Broker, Volume, Anomaly,
  and Technical, so every option is visible without horizontal scrolling.
- Candidate detail ends with a real Yahoo OHLC candlestick chart for the latest
  60 sessions and a least-squares close trendline over the latest 20 sessions.

## 1.5.0 Home ranking and mandatory broker score

- Screener only renders the ticker explicitly entered by the user; market Top 5
  content was removed from that page.
- Home calculates five independent Top 5 views from the latest broker-valid batch:
  Overall, Broker, Volume, Anomaly, and Technical.
- Every Home candidate card has separate Detail and Telegram actions. Detail opens
  the complete score, 1D/3D/5D/10D flow, buyers, sellers, foreign flow, anomaly,
  risk, trade plan, and explanation snapshot.
- The optional broker checkbox was removed. A valid Stockbit session and a real
  broker summary are required both for market screening and single-ticker analysis;
  no Yahoo-only broker score is synthesized.
- The broker shortlist is the bounded union of preliminary Overall, Volume,
  Anomaly, and Technical leaders. This preserves connector stability; each ranking
  is therefore Top 5 inside the liquidity-prequalified, broker-validated batch.

## 1.5.1 Stockbit period alignment and broker dominance correction

- The former all-broker net total was removed because total broker buys and sells
  are the two sides of the same market and cancel to approximately zero.
- Broker period tiles now represent the explicitly labelled Top-5 buyer versus
  Top-5 seller imbalance for 1D, 3D, 5D, and 10D.
- Top buyer/seller lists can be switched between the same four horizons; the
  default 1D view can be compared directly with a one-day Stockbit broker summary.
- Foreign net flow is labelled 10D so it is not confused with Stockbit's 1M
  cumulative F Flow display.
- Chasing risk now states its 20D-return and EMA20-distance inputs. A value of zero
  explicitly means neither extension threshold has been crossed.
- The unsupported Minimum Transaction Frequency setting was removed from UI,
  validation, persistence writes, and the runtime liquidity model.

## 1.4.2 interrupted-screening recovery and analysis layout

- The market scan no longer uses WorkManager's connected-network constraint, which
  could stop a running worker during a 5G/Wi-Fi transition.
- Completed ticker batches and their preliminary data-derived candidates are stored
  in a credential-free cache checkpoint. One Android/system restart resumes the same
  work from that checkpoint; repeated system termination ends with an explicit error.
- Manual screening replaces stale unfinished work, so an old queued job cannot replay
  after the user starts a new scan.
- Quote volume is displayed in compact `K`, `M`, or `B` notation.
- Search analysis now separates score, flow/activity, trade plan, broker periods,
  buyers, sellers, foreign flow, and POV flow into readable cards and rows.

## 1.3.0 corrective checks

- Settings detail rows constrain both columns, removing the unintended tall blank area.
- History reset cancels any active screening worker, waits for cancellation, and clears
  Room on an IO dispatcher so background work cannot immediately repopulate history.
- Ticker search now performs the full screening analysis and renders final, broker,
  volume, anomaly, technical, distribution, chasing, entry/TP/SL, RR, and explanation.

## 1.4.0 screening reliability and flow analysis

- Settings are snapshotted once at worker start and never start or restart screening.
- Yahoo and Stockbit have per-ticker and whole-stage time limits; a cancel control is
  visible while a run is active.
- Hidden WorkManager retry is disabled, and progress counters are serialized so they
  cannot move backwards inside the same stage.
- Stockbit uses aggregate 1D/3D/5D/10D windows plus a separate FOREIGN investor
  window instead of requesting up to ten daily rows for every ticker. Valid broker
  results are cached for 30 minutes.
- The Screener supports Overall, Broker, Volume, Anomaly, and Technical Top 5 views.
  The Broker view excludes candidates without a valid broker response.
- Broker snapshots include top net buyers/sellers, average prices and investor class
  when supplied, foreign investor net flow, and a price-aware flow interpretation.

## 1.4.1 release identity, WebView and universe recovery

- `versionCode` is raised to `10401` so Android cannot silently retain an older
  low-numbered test build during an update.
- Every page header shows `v1.4.1 • build 10401`; About also shows the release
  marker `WEBVIEW-STOCKBIT / MULTI-SOURCE-IDX`.
- Home and Settings both expose `LOGIN STOCKBIT VIA WEBVIEW`. The packaged
  manifest contains the non-exported `StockbitLoginActivity`, and the release
  DEX contains both WebView login button labels.
- Session capture accepts Stockbit authentication responses across Stockbit
  subdomains and can detect refresh/access JWTs written to WebView local storage;
  credentials remain encrypted and no password, CAPTCHA or trading PIN is read.
- IDX-universe discovery tries TradingView Indonesia, TradingView Global, then
  the official IDX company directory. Yahoo still validates each candidate and
  supplies the OHLCV used by the scoring engine.

## Packaged checks

- Launcher label is `Lumi Signal`.
- `StockbitLoginActivity` is present and not exported.
- Third-party notice and official supplied Lumi logo resources are packaged.
- Internet, network-state, WorkManager wake/boot, and foreground-service
  permissions are declared; no account, contacts, SMS, location, or trading
  permissions are requested.

## Live-service boundary

The APK uses TradingView's live IDX catalogue, Yahoo Finance completed-session
candles, Telegram Bot API, and a read-only experimental Stockbit private-web
connector. The live universe request returned 844 IDX symbols during the build
investigation. Stockbit credentialed end-to-end validation was
not executed during this build because no user credential was supplied. The app
does not mark Stockbit as connected until a user login yields a valid session and
the read-only broker-directory request succeeds. Source failures remain visible;
the app does not fabricate market or broker data.
