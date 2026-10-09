# Lumi Signal 1.13.0 — IDX bulk data, budgeted broker verification

Field run of 1.12.5 showed the Stockbit broker endpoint (`/marketdetectors`, one request per
ticker per window) is throttled per account: most answers become HTTP 200 with an empty list,
and retrying only made the run slower. 1.13.0 stops depending on hundreds of those calls.

- **Daily OHLCVF from IDX** (`TradingSummary/GetStockSummary`): one request returns OHLC, volume,
  value, frequency and foreign buy/sell for every stock on one session. 72 sessions ≈ 80 requests on
  first sync, then ~1 request per day (completed sessions cached 150 days). Volume converted to lots.
  Stockbit per-ticker history is the automatic fallback (whole run, or tickers missing in IDX).
- **Technical ranking first**: candidates that pass the upper-bound prefilter are ranked by technical
  strength; broker verification goes to the strongest setups first.
- **Broker budget + circuit breaker**: 3 tickers in parallel, 8-minute budget, and the stage stops
  when ≥60% of the last 20 broker answers were empty/429. Empty answers are retried once (1.5 s).
- **Provisional candidates**: when a strategy has fewer than 5 broker-verified passers, it is filled
  with technically qualifying candidates (≤2 unavailable criteria) labelled "KANDIDAT SEMENTARA",
  with IDX foreign flow 5D/10D. They are shown as "BELUM LENGKAP", never ranked above verified rows,
  never stored as tracked signals and cannot be sent to Telegram.
- CI probes the IDX endpoint and publishes `idx-probe.txt` with each build.

## 1.13.1

- Field run: idx.co.id answers direct HTTP clients with Cloudflare "Attention Required" (HTTP 403).
  `IdxStockSummaryRepository` now switches to `IdxWebTransport` on the first 403/challenge: a hidden
  WebView loads IDX's own "Ringkasan Saham" page (real Chromium, site cookies) and requests
  `GetStockSummary` from inside the page with `fetch`, returning the JSON through a
  `@JavascriptInterface` bridge (kept by R8). If the page itself stays blocked, the run falls back to
  Stockbit OHLCVF as before. The activity log names the transport used.
