# Lumi Signal 1.8.0 — Implementation Notes

## Strategy engine

- Replaced Free-Float Turnover Creep with Broker–Price Divergence.
- Replaced Supply Vacuum Breakout with Breakout Retest Confirmation.
- Added hard-gate evaluation so a strategy cannot pass when its defining evidence fails.
- Frequency Creep now calculates RFREQ, average trade size, and consecutive frequency growth from Stockbit fields.
- Markup Ignition now calculates RFREQ 1/60 from Stockbit frequency.
- Broker Accumulation Persistence now uses ten single-session broker snapshots; an aggregate 10D response is no longer accepted as proof of persistence.
- Absorption and Spring broker confirmation are matched to the event's trading date when that date is present in the ten-session broker archive.

## Stockbit market data

- Added paged parsing for `/company-price-feed/historical/summary/{symbol}`.
- Added official traded value and transaction frequency to `Candle`.
- Screening, single-ticker analysis, chart loading, and daily audit history now use Stockbit.
- Current-session audit uses Stockbit orderbook session OHLC. No Yahoo request remains in an active code path.
- Historical bars use Stockbit's lot convention consistently for relative-volume calculations; official `value` is used directly rather than derived from volume.

## Broker cache and migration

- Broker cache payload now preserves ten daily snapshots and top-buyer persistence.
- Cache namespace moved to `FLOW_V5_DAILY_10D`, preventing an older aggregate-only cache from being mistaken for daily evidence.
- Legacy stored names for strategies 5 and 10 resolve to their replacement slots so old database rows do not crash or fall back to strategy 1. Their historical criteria remain inside the immutable snapshot.

## UI

- Data connection now distinguishes Stockbit Market Data, Stockbit Account, and optional Telegram.
- All ten strategy descriptions were rewritten as concise user-facing hooks.
- Detail cards identify Stockbit as the candle source.
- Settings explain hard gates and disclose remaining global data limitations.

## Verification additions

- Added parsing coverage for official Stockbit OHLCV/value/frequency rows.
- Updated strategy tests to exercise real frequency criteria.
- Added legacy strategy-name mapping tests.

## Remaining global limitations

The ten strategy formulas no longer depend on free-float shares or historical orderbook replenishment. However, full validation of UMA/FCA/special notation, the complete corporate-action set, and median historical spread remains separate work. The application continues to disclose these fields rather than fabricating them.
