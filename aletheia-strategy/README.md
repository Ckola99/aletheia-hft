# aletheia-strategy

The ICT engine. Every detector that turns raw candles into an institutional-trading-pattern read, plus the `SignalAggregator` gatekeeper that combines them into a validated `TradeSignal`. This module contains **all** of Aletheia's trading logic and none of its execution, persistence, or data-source concerns.

---

## Where this sits

```
aletheia-core
      ▲
aletheia-strategy   ── consumed by aletheia-backtest and aletheia-api
```

**Dependencies** (`pom.xml`): `aletheia-core` — that's it. Notably **not** `aletheia-calendar`, despite Pillar 3 of the five-pillar gate being a news-blackout check: `SignalAggregator` never calls `EconomicCalendarService` itself. It only reads a pre-computed `boolean newsBlackout` field off `MarketContext`. The actual `isNewsBlackout(...)` call happens one layer up, in `BacktestEngine` and `LiveSignalService`, which both depend on `aletheia-calendar` directly and hand the boolean down. Keep this in mind if you're tracing "why didn't Pillar 3 fire" — the answer is never inside this module.

---

## The five-pillar gate: `SignalAggregator`

Constructed with three detector instances (`FairValueGapDetector`, `OrderBlockDetector`, `JudasSwingDetector` — default constructor wires library defaults; a second constructor accepts injected instances, used by `BacktestEngine` and `TradingEngineConfig` to pass production-tuned parameters). `evaluate(MarketContext ctx)` checks pillars **strictly in order**, short-circuiting and logging a `Pillar N FAIL: ...` reason on the first failure:

1. **USDX bias** — `ctx.usdxBias().isTradeable()` must be true (directional AND confidence HIGH/MEDIUM), then `usdxBias.biasForPair(ctx.instrument())` must itself be directional.
2. **Killzone** — `ctx.killzone().isActive()`.
3. **News** — `!ctx.newsBlackout()` (computed by the caller, as above).
4. **HTF PD Array** — either `FairValueGapDetector` or `OrderBlockDetector`, run over `ctx.htfCandles()`, must find a zone whose bias matches the required trade direction.
5. **Judas Swing** — `JudasSwingDetector.detect(pairBias, ctx.killzone(), ctx.ltfCandles())` must return a signal.

If all five pass: `grade = ctx.smtSignal().isPresent() ? A_PLUS : A`, and a `TradeSignal` is built and logged (with a formatted ✅ banner). **SMT is additive, not gating** — its detection happens upstream (`BacktestEngine`/`LiveSignalService` call `SmtDivergenceDetector` and pass the result *in* via `MarketContext.smtSignal()`); `SignalAggregator` itself never calls the SMT detector.

---

## The detectors, one by one

### `FairValueGapDetector` (stateless)
Slides a 3-candle window `[prev, impulse, next]` across the whole candle list:
- **Bullish FVG**: `prev.high() < next.low()` (strict inequality — exactly-touching candles don't count). Zone = `[prev.high(), next.low()]`.
- **Bearish FVG**: `prev.low() > next.high()`. Zone = `[next.high(), prev.low()]`.

Timeframe-agnostic — works identically at any `Timeframe`. Worked example (from the test suite): prev `(O108100,H108200,L108050,C108180)`, impulse `(O108190,H108500,L108180,C108480)`, next `(O108490,H108600,L108300,C108550)` → bullish FVG zone **108200–108300**.

### `OrderBlockDetector(int atrPeriod, double displacementMultiplier)` (default `20, 1.5`)
For each index `i`, candidate = `candles[i]`, displacement = `candles[i+1]`. Computes `atr = AtrCalculator.calculate(candles, i, atrPeriod)`; skips if the displacement candle's `bodySize()` doesn't exceed `atr × displacementMultiplier`. Then:
- **Bullish OB**: candidate is bearish, displacement is bullish → zone = candidate's high/low.
- **Bearish OB**: candidate is bullish, displacement is bearish → zone = candidate's high/low.

Same-direction candidate/displacement pairs never produce an OB.

### `PdArray` (sealed interface, `permits FairValueGap, OrderBlock`)
The shared parent both `FairValueGap` and `OrderBlock` implement — same shape (`upper`, `lower`, `time`, `timeframe`, `bias`), with default `midpoint()` = `(upper+lower)/2` and `contains(price)` = inclusive-both-ends range check. The nested `PdArray.Bias` enum (`BULLISH`/`BEARISH`) is what both `FairValueGap.bias()` and `OrderBlock.bias()` return. Being `sealed` enables exhaustive pattern matching over PD-array types.

### `AtrCalculator` (static utility)
`calculate(candles, endIndex, period)` = average `(high - low)` over the trailing `period` candles ending at `endIndex` — a simplified True Range (no gap/previous-close adjustment, justified because forex trades ~24h with rare gaps). Returns `0` for out-of-range indices. Integer division on scaled longs — no floating point anywhere in this calculation.

### `SwingPointDetector(int lookback)`
A candle at index `i` is a swing high iff its `high()` strictly exceeds the highs of all `lookback` candles on both sides; symmetric for swing low. A single candle can qualify as both simultaneously (checked independently). Requires `size >= 2×lookback + 1`. Production tuning: **HTF (structure) uses lookback 5, LTF (entry precision) uses lookback 3** — `JudasSwingDetector`'s own default constructor independently defaults to 3.

### `MarketStructureAnalyser(int lookback)` (default 5)
Wraps a `SwingPointDetector`, takes the two most recent swing highs and two most recent swing lows:
- `BULLISH` iff `lastHigh > prevHigh` **and** `lastLow > prevLow` (HH+HL).
- `BEARISH` iff `lastHigh < prevHigh` **and** `lastLow < prevLow` (LH+LL).
- Otherwise `NEUTRAL`, with a diagnostic reason (`"HH but LL (expanding range)"`, `"Equal highs and lows — consolidation"`, etc.) attached to the returned `StructureResult(bias, swings, reason)`.

### `JudasSwingDetector(int swingLookback, int atrPeriod, double displacementMultiplier)` (default `3, 20, 1.5`; production/backtest uses `3, 20, 2.5`)
Composes an internal `SwingPointDetector` + `FairValueGapDetector`. `detect(htfBias, killzone, candles)` rejects immediately on `NEUTRAL` bias, an inactive killzone, or too few candles (`< atrPeriod + 10`). Then, for the bullish case (bearish mirrors it):

1. Collect all swing **lows**.
2. Scan backwards for a **sweep candle** — one whose `low()` breaks below a swing low that formed *before* it (the most recent such swing wins).
3. From the sweep candle forward, look for a **displacement candle**: bullish, with `bodySize() > ATR(..., displacementMultiplier)`.
4. Run `FairValueGapDetector` over a window around the sweep + displacement; prefer a bullish FVG at or after the displacement candle, falling back to any bullish FVG in the window.
5. On success: `JudasSwingSignal(bias, instrument, entryZone, sweptSwing, sweepPrice, killzone, grade=A)`.

Worked example (from the test suite, `swingLookback=2, atrPeriod=5, displacementMultiplier=1.5`): baseline candles establish ATR≈100; a swing low forms at **108,100**; price sweeps to **108,050**; the next candle `(O108090,H108360,L108080,C108350)` is bullish with body `260 > 150` (displacement); the following candle's low (108,340) clears the sweep candle's high, producing an FVG entry zone **108,230–108,340**.

`JudasSwingSignal.idealEntry()` = `entryZone.midpoint()` — the price at which the live/backtest engine places a resting limit order (see [`aletheia-execution`](../aletheia-execution/README.md) and [`aletheia-backtest`](../aletheia-backtest/README.md)).

### `SmtDivergenceDetector` (stateless, `MAX_SWING_SEPARATION = 30 minutes`)
`detect(pair, timeframe, registry, killzone)` rejects on an inactive killzone. Pulls each instrument's two most recent swings of the relevant type from a `SwingPointRegistry`, requires the *most recent* swings of both instruments to be within 30 minutes of each other (`isTemporallyAligned`), then:
- **Bullish SMT**: instrument B makes a lower low, instrument A fails to follow (equal or higher low) → divergence.
- **Bearish SMT**: instrument B makes a higher high, instrument A fails to follow → divergence.

Worked example: GBP/USD swing lows `108100 → 108050` (the trap — makes a new low), EUR/USD swing lows `108100 → 108150` (fails to follow, makes a higher low) → `SmtType.BULLISH`, `instrumentToTrade() == "EUR_USD"`, `judasInstrument() == "GBP_USD"`.

### `SwingPointRegistry(int swingLookback, int maxSwingsPerKey)` (default `3, 20`)
Thread-safe via `ConcurrentHashMap` + immutable snapshot replacement (no locks needed). `update(instrument, timeframe, recentCandles)` recomputes the full swing list, trims to the most recent `maxSwingsPerKey`, and atomically swaps it in. Keys are `"{instrument}:{timeframeName}"`. `getSwings()` never returns `null` — an unknown key yields `List.of()`.

### `KillzoneService`
Converts any time to `America/New_York` (DST-aware via `ZoneId`, not a fixed UTC offset) and classifies by decimal hour into the same four windows as `KillzoneWindow` in `aletheia-core`. See that module's README for the exact boundaries.

### `UsdxBiasEngine(int lookback)` (default 5)
Runs `MarketStructureAnalyser` independently on Monthly, Weekly, and Daily candle series, then applies consensus logic in priority order:

1. All three agree and are directional → that direction, confidence `HIGH`.
2. Monthly == Weekly (Daily diverges) → Monthly's direction, confidence `MEDIUM`.
3. Weekly == Daily (Monthly diverges) → Weekly's direction, confidence `MEDIUM`.
4. Monthly == Daily (Weekly diverges) → Monthly's direction, confidence `MEDIUM`.
5. Otherwise → `NEUTRAL`, confidence `LOW`.

---

## The signal-shaped records

| Record | Fields | Notes |
|---|---|---|
| `FairValueGap` | `bias, upper, lower, time, timeframe` | `time` is the impulse (middle) candle's timestamp |
| `OrderBlock` | `bias, upper, lower, time, timeframe` | Same shape as `FairValueGap`, all behavior from `PdArray` defaults |
| `JudasSwingSignal` | `bias, instrument, entryZone, sweptSwing, sweepPrice, killzone, grade` | `idealEntry()` = `entryZone.midpoint()` |
| `SmtDivergenceSignal` | `type, pair, timeframe, trapSwing, confirmingSwing, killzone` | `instrumentToTrade()`/`judasInstrument()` read off the pair |
| `SmtPair` | `instrumentA, instrumentB` | `instrumentA` is traded, `instrumentB` is the confirming/trap pair. Static constants `EUR_GBP`, `NAS_DOW` exist "for future use" — production config in `aletheia-api` builds pairs from `trading.smt-pairs` directly rather than these constants |
| `TradeSignal` | `bias, instrument, entryZone, idealEntry, sweepPrice, killzone, grade, usdxBias, judasSignal, smtSignal, generatedAt` | `entryZone` is always FVG-typed (not the `PdArray` interface) since Pillar 5's Judas Swing always produces an FVG, even though Pillar 4 also accepts Order Blocks |
| `UsdxBias` | `direction, confidence, monthlyBias, weeklyBias, dailyBias` | `biasForPair(instrument)` = `direction.invert()` if directional else `NEUTRAL` — **every traded instrument is treated as USD-inverse uniformly**, no per-instrument correlation table |
| `MarketContext` | `now, instrument, killzone, usdxBias, htfCandles, ltfCandles, newsBlackout, smtSignal` | Pure data snapshot — `SignalAggregator` never fetches anything itself |

`SignalGrade` is `A_PLUS` (Judas Swing + SMT) or `A` (Judas Swing alone). `ConfidenceLevel` is `HIGH`/`MEDIUM`/`LOW`, with `isTradeable()` true for `HIGH`/`MEDIUM`.

---

## Consumers

- **`aletheia-backtest`**'s `BacktestEngine` wires `SignalAggregator` with production-tuned parameters (`OrderBlockDetector(14, 2.0)`, `JudasSwingDetector(3, 20, 2.5)` — tighter than the library defaults), plus its own `UsdxBiasEngine(3)` and `KillzoneService`. It also computes SMT divergence itself (per-candle, no look-ahead) and feeds `EconomicCalendarService.isNewsBlackout(...)` into `MarketContext.newsBlackout`.
- **`aletheia-api`**'s `LiveSignalService` is the live equivalent: on every closed LTF candle for a traded instrument, it builds `UsdxBias` (preferring the real DXY feed, falling back to synthetic), runs `SmtDivergenceDetector` against the instrument's *explicitly configured* partner (`trading.smt-pairs` — see [`aletheia-api`](../aletheia-api/README.md)), computes news blackout, and calls `signalAggregator.evaluate(ctx)`.

No other module (`aletheia-core` excepted, as a dependency) imports `com.aletheia.strategy`.

---

## Testing notes

Every detector is validated with fabricated candle sequences representing exact ICT scenarios — the worked examples above are pulled directly from those tests, not invented. If you're tuning `atrPeriod`/`displacementMultiplier`/`swingLookback` for a new instrument, the existing test fixtures are the fastest way to understand what a parameter change actually does to detection sensitivity before running a full backtest.
