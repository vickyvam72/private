# Lumi Signal 1.9 — Read-only Portfolio

## Added

- Separate Portfolio navigation tab; the screener and Portfolio state remain independent.
- Separate Stockbit Sekuritas token domain and refresh chain.
- Six-digit PIN unlock flow. The PIN is validated locally, used for one HTTPS login request, and never saved or logged.
- Read-only account panels for equity, cash, buying power, withdrawable cash, T+0/T+1/T+2, holdings, open orders, transaction history, realized P/L, and trading performance.
- Holdings are cross-referenced with Home and Screener tickers so owned symbols receive an explicit badge.
- Optional Stockbit live-context panel for orderbook spread, UMA/special notation, corporate actions, shareholder context, and trade book.
- Conservative JSON parsers: missing or unknown financial fields render as unavailable, never as zero.

## Security boundary

The application has no route or method for real buy, sell, amend, cancel, deposit, withdrawal, or e-IPO submission. Main-market and securities credentials are encrypted in different Android Keystore-backed entries. Locking Portfolio removes only the securities session; logging out of Stockbit removes both.

## Evidence level

Market OHLCV/value/frequency, orderbook, and broker summary mappings have existing coverage. The community reference currently marks the Stockbit Sekuritas account field mappings as projected rather than live-observed. Therefore Lumi accepts common field aliases but exposes a warning whenever a response cannot be mapped safely. A real-account validation pass is still required before the Portfolio module can be described as production-verified.

## Build status in this workspace

Source structure checks completed. Gradle compilation could not start because Gradle 8.9 is absent locally and the execution environment cannot reach the Gradle distribution hosts. Do not distribute or rename the pre-existing v1.7 APK as v1.9.
