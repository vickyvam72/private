# Lumi Signal 1.14.0 — Yahoo Finance prefilter, broker date diagnostics

- **Yahoo Finance prefilter** (`YahooChartRepository`, free, no login): daily OHLCV for every
  `.JK` stock, 8 in parallel. Yahoo has no trade frequency or broker data, so it is only used by
  `StrategyEngine.prefilterCouldQualify`, which treats missing frequency/broker inputs as possible and
  tolerates one extra missed criterion. Only stocks that could qualify are then read from Stockbit
  (full OHLCVF), cutting Stockbit price requests. If Yahoo returns <60% of the universe, every stock
  goes to Stockbit as before. IDX cached data, when complete, still takes precedence.
- **Broker diagnostics**: an incomplete daily broker history now lists filled and empty dates and
  classifies the pattern (only newest dates filled → likely account history limit; random gaps →
  likely throttling). Manual analysis also shows one raw empty Stockbit response.
- Free GitHub datasets were evaluated; the actively updated ones also scrape idx.co.id and hit the
  same Cloudflare challenge, so none was adopted.
- CI publishes `yahoo-probe.txt` with each build.

## 1.14.1

- CI probe: Yahoo answers 429 to GitHub data-centre IPs. Added a circuit breaker (after 24 tickers,
  <25% success stops the Yahoo stage) and single attempts, so a blocked network costs seconds.

## 1.14.2

- Field run: IDX via WebView works (72 sessions cached, OHLCVF 844/844 from IDX in seconds).
- Broker: Stockbit answers "Successfully retrieved market detector data" with empty broker lists for
  almost every request from the start of a run, while the Stockbit app still shows the same dates.
  Likely a per-account read cap on the API. Added: structural summary of empty vs filled answers
  (array sizes, echoed dates, bandar_detector), a seconds:outcome timeline of broker answers, both in
  the activity log and in manual analysis; and a hard budget of 400 broker requests per screening.
