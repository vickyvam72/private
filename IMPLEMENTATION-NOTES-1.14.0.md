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
