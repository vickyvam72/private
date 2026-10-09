# Lumi Signal 1.12.0 — Implementation Notes

## Screening integrity and speed

- Every ordinary four-letter IDX equity discovered by the live universe source is attempted; warrants and rights are excluded explicitly.
- The Home screen keeps the previous completed batch visible while a new run is in progress. A completed zero-result run remains an honest empty batch and never falls back to stale candidates.
- Every run records discovered, attempted, valid, failed, stale, broker-seed, broker-detail, and candidate counts. Incomplete critical reads are marked `PARTIAL` instead of being advertised as full-market Top 5.
- Completed Stockbit OHLCVF is cached by Jakarta screening date. Later runs refresh only the newest page.
- Broker enrichment is tiered: one 10-session seed for the market; 1D/10D aggregates for strategies that only need current broker direction; ten daily sessions for persistence/divergence; twenty daily sessions for absorption/spring event matching.
- Missing 1D/3D/5D or daily broker windows are `DATA_UNAVAILABLE` during the seed stage. They are no longer misclassified as a failed criterion, which previously prevented valid candidates from reaching enrichment and could produce all-zero strategy results.
- HTTP 429 and 5xx responses use bounded retry/backoff. Audit and direct user requests have priority over screening traffic.

## Ten-strategy data coverage

All ten formulas are computed from authenticated Stockbit OHLCV, traded value, transaction frequency, and broker summary data. Strategies that require event-date or persistence evidence cannot pass until the corresponding daily broker windows are present. Current spread, tradability, UMA, and corporate-action status are operational gates; a missing private-API field is disclosed and makes the run partial rather than being guessed.

## Audit lifecycle

- A stable key of strategy, ticker, and reference trading date prevents duplicate active signals across reruns.
- Newer unsent setups supersede older waiting setups; Telegram-sent or already-entered signals remain immutable history.
- The foreground service audits only Top 5 strategy signals, not the entire exchange.
- Stockbit running-trade prints resolve intraday event order when available. Daily/session OHLC is a conservative catch-up path; an unresolvable TP/SL order remains `AMBIGUOUS`.
- ENTRY, TP, SL, expiry, and ambiguity create Android notifications only. Telegram receives only a signal explicitly sent by the user.
- The persistent notification reports signal count, unique ticker count, last successful cycle, and failures. A user-enabled audit is restored after reboot/app update when a Stockbit session still exists.

## Portfolio and connections

- Stockbit market login is mandatory; Telegram is optional.
- Portfolio remains a separate read-only Stockbit Sekuritas session. PIN input accepts the observed 4–8 digit format and is never persisted.
- Nested Stockbit fields for portfolio totals, cash, buying power, withdrawal balance, settlement, holdings, orders, realized P/L, trade history, and performance are mapped without converting unknown values to zero.
- Connection diagnostics probe market data, BBCA broker summary, and running trade separately so a valid login is not confused with a missing entitlement or response family.

## Remaining runtime constraints

- Stockbit endpoints are undocumented private web APIs and can change independently of this APK.
- A first full-universe run must backfill at least 61 completed sessions per symbol and is necessarily slower than subsequent cached runs.
- Exact one-minute background execution depends on Android/OEM foreground-service and battery policy, network availability, and a valid Stockbit session.
- Live Stockbit and Portfolio responses require verification with the user's own entitled account; automated tests use deterministic fixtures and do not contain user credentials.
