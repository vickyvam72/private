# Lumi Signal 1.15.0 — Stockbit silent-throttle handling

Field diagnostics (1.14.2, manual analysis of BBCA/ANTM) settled the broker failures:

- Empty answers are `200 "Successfully retrieved market detector data"` with empty broker lists **and an
  empty `from`/`to` echo**; filled answers echo the requested dates. The dates were never applied.
- Timeline `0:ok 0:ok 1:E 1:E …`: the first two reads succeed, everything after (~2–3 reads/s) is
  empty. Empty dates are random (10-09 filled, 10-08 empty), not old-only → silent rate limiting,
  not a history limit. The Stockbit app (one date per tap) is unaffected.

Changes:
- A blank-echo empty answer is classified as throttled: the broker lane pauses (≥4 s), slows down
  (AIMD), and the same window is retried up to 4 times. A date-echoing empty answer is accepted as a
  genuinely empty session. The circuit breaker only counts windows that stay throttled.
- Broker lane: one request in flight, 0.9–1.5 s spacing (adaptive up to 15 s).
- Screening skips the two FOREIGN broker reads per ticker when IDX foreign flow is available;
  2 tickers in parallel, 10-minute broker budget, 400-request cap.
- Simulated against the observed throttle: 2 tickers × 20 daily sessions complete, 0 failures.
