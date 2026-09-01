# aletheia-backtest

Historical replay engine. Runs the exact same `SignalAggregator`/detector stack used live over Dukascopy tick history, with realistic spread simulation, per-candle SMT divergence, and the same two-stage TP1/TP2 trade management `aletheia-execution`/`PositionMonitor` use in production — so a backtest result is a genuine prediction of live behavior, not an idealized upper bound.

---

## Where this sits

```
aletheia-core, aletheia-strategy, aletheia-data, aletheia-calendar
      ▲
aletheia-backtest   ── standalone: run directly via RunBacktest.main(),
                        also pulled into aletheia-api's dependency tree
                        (though not wired into the live app itself)
```

---

## Running one

```bash
./scripts/run-backtest.sh [optional-log-name]
```

or manually:

```bash
./mvnw clean install -DskipTests -q
./mvnw exec:java -pl aletheia-backtest -Dexec.jvmArgs="-Xmx12g"
```

Configuration (instrument, SMT partner, date range, risk parameters) lives in `RunBacktest.java`'s `main()` — there's no external config file for backtest runs. `RunBacktest` currently runs EUR/USD (SMT partner GBP/USD), GBP/USD (SMT partner EUR/USD), AUD/USD (SMT partner NZD/USD), and USD/JPY (no SMT partner) over 2023-01-01 to 2023-12-30, then prints per-pair and combined summaries.

---

## `BacktestEngine` — the replay loop

Constructed with `(riskRewardRatio, slBufferScaled, maxOpenTrades, spreadScaled)`. Internally wires its own `SignalAggregator` (with production-tuned detector parameters — `OrderBlockDetector(14, 2.0)`, `JudasSwingDetector(3, 20, 2.5)`), `KillzoneService`, `EconomicCalendarService`, and `UsdxBiasEngine(3)`.

Three public entry points, in increasing order of fidelity:

| Method | USDX bias | SMT |
|---|---|---|
| `run(instrument, htf, ltf, UsdxBias)` | fixed (precomputed) | none — used in unit tests |
| `run(instrument, htf, ltf, monthly, weekly, daily)` | dynamic, recomputed per candle | none |
| `run(..., Optional<SmtDivergenceSignal>)` | dynamic | a single SMT signal frozen for the whole run |
| **`runWithLiveSmt(..., smtInstrument, selfMin15, smtPartnerMin15)`** | dynamic | **computed fresh every candle** — the one `BacktestRunner` actually uses |

### The main loop (`runInternal` / `runInternalLiveSmt`)

For each LTF candle, in order:

1. **Check open trades for exits** against this candle's high/low (`SimulatedTrade.checkExit`).
2. If already at `maxOpenTrades`, or still in cooldown (24 bars after the last signal), skip evaluation — but exits from step 1 always run regardless.
3. Build the **visible** HTF/LTF/USDX candle windows — every filter is `!c.time().isAfter(now)`, so nothing after "now" is ever visible. This is where no-look-ahead is structurally enforced, not just promised.
4. (`runWithLiveSmt` only) Recompute SMT divergence for *this candle* from a bounded, no-look-ahead window (`lastVisible`, capped at 300 candles — mirroring `LiveSignalService`'s buffer limit), updating a shared `SwingPointRegistry` exactly like the live service does.
5. Build `MarketContext`, call `aggregator.evaluate(ctx)`.
6. On a signal: compute `effectiveEntry` (idealEntry ± half the simulated spread — long pays the synthetic ask, short receives the synthetic bid), `sl` from the sweep price ± buffer, and `tp1`/`tp2` at 2R/3R off `effectiveEntry`. Open a `SimulatedTrade`, start the cooldown.

At the end of the candle series, any still-open trade is force-closed against the final candle.

---

## `SimulatedTrade` — the TP1/TP2/breakeven trade-management model

This class is a deliberate mirror of `ManagedOrder` + `PositionMonitor`'s live rules — same TP1/TP2 multiples (2R/3R), same 70% partial-close fraction, same breakeven-on-TP1 stop move. Constructed with `(signal, initialStop, tp1, tp2, tp1CloseFraction)`.

### Per-candle state machine (`checkExit(high, low, time)`)

**Phase 1 — position whole (before TP1):**
- Stop *and* TP1 both touched in the same candle → **ambiguous**. The engine can only see a candle's high/low, not the intra-candle path, so it can't know which was hit first. **Conservative rule: assume the stop was hit first** — full `-1.0R` loss. This means the backtest can never accidentally flatter itself by resolving ambiguity in its own favor.
- Stop alone touched → full `-1.0R` loss, trade closed.
- TP1 alone touched → bank `tp1CloseFraction × tp1R` (e.g. `0.70 × 2.0R = 1.4R`) into `realisedR`, move the stop to breakeven (`= entryPrice`), and **fall through to Phase 2 in the same candle** (the runner might also reach TP2 or its new breakeven stop before the candle closes).

**Phase 2 — runner active (after TP1, stop at breakeven):**
- Breakeven stop *and* TP2 both touched → same ambiguity rule: assume breakeven first, contributes `0` to `realisedR`.
- TP2 alone touched → bank `runnerFraction × tp2R` (e.g. `0.30 × 3.0R = 0.9R`).
- Breakeven stop alone touched → contributes `0`; trade done.

### The four possible outcomes (2R/3R at 70/30)

| Outcome | Realised R |
|---|---|
| Full loss (stopped before TP1) | **−1.0R** |
| Breakeven scratch (TP1 hit, runner stopped at breakeven) | **+1.4R** |
| Full win (TP1 hit, runner reaches TP2) | **+2.3R** |

`isWin()` = `realisedR > 0` — **a breakeven scratch counts as a win** in win-rate statistics, since it's net-positive. Keep this in mind when comparing win rate against a simpler single-target strategy's numbers; they're not the same measurement.

`pnlPips()` = `realisedR × (riskDistance in pips)` — P&L is tracked internally as a fraction-weighted R multiple across the legs and only converted to pips for reporting, so the reported unit stays comparable to older single-TP runs while correctly accounting for the partial-close weighting. `pnlScaled()` is provided for compatibility with callers expecting a scaled-price figure, reconstructed from `realisedR × riskDistance`.

---

## `PerformanceMetrics` — the report

Computed from the closed subset of a `BacktestResult`'s trades: total trades, win/loss counts, win rate, net/gross P&L in pips, profit factor, max drawdown (peak-to-trough on the cumulative pip equity curve), Sharpe ratio (mean/stddev of per-trade pip P&L — sample variance, so needs ≥2 trades), average winner R:R, A vs A+ win-rate breakdown, and a per-killzone breakdown (trade count, win %, net pips) so you can see whether the edge concentrates in London or New York.

---

## Data pipeline: `HistoricalCandleBuilder`, `BacktestRunner`, and the disk cache

`HistoricalCandleBuilder` wraps `aletheia-data`'s `CandleAggregator` in-memory — `buildFrom(ticks)` or `processOneTick(tick)` feed it, `getCandles(instrument, timeframe)` reads the result back out, explicitly documented as "the exact same `CandleAggregator` that live trading uses." `loadCandles(List<Candle>)` bypasses tick aggregation entirely, loading pre-built candles directly — this is what the disk cache (below) uses to skip re-aggregation on a cache hit.

`BacktestRunner.downloadAndAggregate(instrument, start, end)` is a three-tier cache:

1. **In-memory** (`candleCache`, keyed by `instrument|start|end`) — within a single run, re-requesting the same range (e.g. the DXY 3-month lead-in overlapping a later window) is free.
2. **On-disk CSV** (`data/cache/{instrument}_{start}_{end}.csv`) — across runs. Only used for **complete past ranges** (`endDate` more than 2 days old) — a range still "in progress" relative to today is never cached, since it could still change. On a hit, `loadFromDisk` reads the CSV straight back into candles via `loadCandles(...)`, skipping both the Dukascopy download and re-aggregation.
3. **Download + aggregate** — the fallback: `DukascopyHistoryLoader` (via a `TickRepository` subclass with `jdbc=null` whose `onTick` forwards straight into the builder) streams ticks in, and on completion (again, only for complete past ranges) writes the result back to the disk cache for next time.

This is why a repeat backtest over the same instrument/date-range is near-instant after the first run, while a brand-new range still pays the full Dukascopy download cost (which the root README notes can take hours for a full year across multiple pairs).

`SyntheticUsdxBuilder.fromEurUsd(candles)` is the fallback dollar-index proxy used only if the real `DOLLAR_IDX` download fails: it inverts EUR/USD candles (`1/price`, with high and low swapped since inverting reverses direction), on the reasoning that EUR is 57.6% of the real USDX basket weight, so the inverse's *structure* (HH/HL/LH/LL) is a reasonable proxy even though the absolute level isn't real.

---

## `BacktestResult` / `RunBacktest`

`BacktestResult(trades, signalsGenerated, signalsRejected)` — `metrics()` builds a `PerformanceMetrics` on demand; `printReport()` prints it. `RunBacktest.main()` is the CLI entry point: builds a `BacktestRunner`, runs it across the configured instrument set, and prints both a per-trade log (`printTradeLog`) and a combined cross-pair summary.

---

## Design note: how this stays a fair prediction of live, not an idealized best case

Three things this module goes out of its way to match to `aletheia-execution`/`aletheia-api`'s live behavior, specifically so the backtest doesn't lie to you:

1. **The same TP1/TP2/breakeven math** as `ManagedOrder`/`PositionMonitor` (`SimulatedTrade`'s docstring says so explicitly).
2. **The same order-expiry rule** as `OrderExpiryService` — a resting simulated limit order that never gets touched within its killzone (or within 3 hours) doesn't just sit open forever distorting later statistics; it should expire the same way a real pending order would (see [`aletheia-execution`](../aletheia-execution/README.md#orderexpiryservice) for the exact rule this mirrors).
3. **The same ambiguity-resolution bias** (assume the worse outcome when a candle's range could support either the stop or the target) — a live limit-order fill and a live stop-loss fill both happen at whatever price the market actually printed, in whatever order it actually happened; a backtest working from OHLC candles instead of tick-by-tick replay genuinely cannot know that order, so it resolves the ambiguity pessimistically rather than optimistically.

If you're extending this engine, preserving that parity is the whole point — a change here that makes backtest numbers look better without a matching change in `aletheia-execution`/`aletheia-api` is very likely just reintroducing an unrealistic assumption.
