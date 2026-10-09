# Lumi Signal 1.12.4 — Broker reliability & screening speed

## Root causes of the broker failures in 1.12.3

1. **Per-ticker timeouts counted queue time.** Detailed analysis needs ~26 broker requests per
   candidate, all funnelled through a 2-slot / 750 ms lane shared by 4 tickers. Even without
   throttling a ticker needed ~80 s of its 120 s budget; after a single HTTP 429 the lane slowed to
   2.5–8 s per request and recovered only 50 ms per success, so nearly every detail ticker hit
   `TIMEOUT` (seed tickers had only 35 s).
2. **One missing day failed the whole ticker.** The 20 daily windows were taken from exactly 20
   proven sessions; any single empty/failed day produced `INCOMPLETE`, and any single exception
   (including optional FOREIGN flow) failed the whole analysis.
3. **Cloudflare challenges were reported as "akses PRO".** A 403 HTML challenge page was
   classified as `ENTITLEMENT` and never retried.
4. **Refresh-token thundering herd.** Stockbit rotates the refresh token on every refresh. When the
   access token expired mid-scan, every parallel request forced its own refresh.
5. **Transient failures were cached** (60 s / 5 min) and replayed as failures.

## Changes

- `AdaptiveRateGate` (AIMD) for the general and broker lanes: speeds up on success (broker floor
  300 ms, 3 in flight), backs off once per burst on 429/challenge with a lane-wide pause, never
  holds a permit while sleeping. Throttling is waited out on a time budget (150 s broker).
- Unified retry policy (`gatedRequest`): 429/challenge → back off & retry; 401 → single refresh
  (skipped if another request already refreshed); 5xx/socket → exponential backoff; other 4xx → fail.
- HTML 403/503 or `cf-mitigated: challenge` → `RateLimitException(challenge = true)`.
- Broker window cache: in-memory de-duplication of identical windows (1D == newest day, seed 10D ==
  10D horizon) plus persistent storage of completed sessions (30 days). Repeat screenings only
  download new sessions.
- Daily windows fetched concurrently with 6 reserve sessions; a failed window gets a second chance;
  FOREIGN flow is optional.
- Failures are no longer cached (only genuine EMPTY for 30 min). Cache keys moved to `FLOW_V11`;
  expired cache rows are purged at the start of each run.
- Access token kept in RAM (AndroidKeyStore decrypt per request was serialised).
- Screening worker: OHLCVF sync and broker analysis now run as a pipeline with work-queue
  concurrency (8 OHLCVF / 6 broker tickers) instead of chunk barriers; market context is fetched in
  parallel with broker history; OHLCVF first-sync pages and market-context reads are concurrent.
- Diagnostics: run duration, request counts, 429/challenge counts and final broker pacing are
  written to the activity log; duration is shown on the coverage card.

## 1.12.5 hotfix (after first field run)

- Field run showed `NullPointerException` for every *successful* broker detail: the worker stored
  "complete" as `null` in a `ConcurrentHashMap`, which rejects null values. Outcomes are now
  `Optional<BrokerFailureKind>`; this was why 0 candidates were saved.
- 278/315 seeds returned HTTP 200 with an empty broker list. Every requested date has OHLCVF
  volume, so an empty answer is treated as silent throttling: the broker lane slows down
  (no hard pause) and the window is retried after 2 s / 6 s / 15 s. Empty windows and EMPTY
  analyses are never cached (keys moved to `BW3` / `FLOW_V12`). Broker lane: 2 in flight, 350 ms floor.
- The activity log now reports empty/recovered counts and one raw empty-response sample
  ("Contoh respons broker kosong Stockbit") so the real Stockbit behaviour can be confirmed.
