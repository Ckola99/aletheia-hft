# aletheia-core

Pure Java domain model for Aletheia. **Zero dependencies** — `pom.xml` declares none, only a `<parent>` reference. Every other module in the project depends on this one; this one depends on nothing but the JDK.

If you're new to the codebase, start here: these ten types are the vocabulary every other module speaks.

---

## Where this sits

```
aletheia-core  ◄── every other module (aletheia-data, aletheia-strategy,
                    aletheia-calendar, aletheia-execution, aletheia-backtest,
                    aletheia-observability, aletheia-api)
```

Nothing in this module imports anything else in the project. That's not incidental — it's what lets `Candle`, `Tick`, and friends be shared, unmodified, across the live pricing path, the historical backtest path, and the ICT detectors without any of them coupling to each other.

---

## The two data records: `Tick` and `Candle`

**`Tick`** — one bid/ask quote at an instant, the rawest unit of market data.

```java
record Tick(Instant time, String instrument, long bid, long ask)
```

- `mid()` → `(bid + ask) / 2` — used for candle construction.
- `spread()` → `ask - bid` — spreads spike during news; useful as a volatility proxy.
- `Tick.of(Instant, String, double bid, double ask)` — the one place raw OANDA doubles get converted to scaled longs via `PriceScale.toScaled(...)` and never touched as doubles again.

**`Candle`** — an immutable OHLCV bar at a specific `Timeframe`, the structure every ICT concept (FVGs, Order Blocks, market structure) is defined against.

```java
record Candle(Instant time, String instrument, Timeframe timeframe,
              long open, long high, long low, long close, long volume)
```

`time` is the candle's **open** time. `volume` is a tick count (OANDA doesn't provide real trade volume).

Non-trivial helper methods, all consumed by `aletheia-strategy`'s detectors:

| Method | Formula | Used for |
|---|---|---|
| `isBullish()` / `isBearish()` / `isDoji()` | `close > open` / `close < open` / `close == open` | Judas Swing displacement direction, Order Block candidate/displacement pairing |
| `bodySize()` | `\|close - open\|` | Displacement-strength threshold (`body > ATR × multiplier`) in `JudasSwingDetector`/`OrderBlockDetector` |
| `totalRange()` | `high - low` | ATR calculation |
| `upperWick()` / `lowerWick()` | `high - max(open,close)` / `min(open,close) - low` | Rejection-block qualification |
| `bodyRatio()` | `bodySize() / totalRange()` (0.0 if range is 0) | Near 1.0 = strong directional candle; near 0.0 = indecision |

---

## `Timeframe` — the fractal parameter

One enum spanning `SECONDS_5` through `MONTHLY`, passed as a plain parameter into nearly every detector rather than being modeled as a class hierarchy — the same detector code runs "fractally" at every scale it's given.

```
LTF (execution):  SECONDS_5, SECONDS_15, SECONDS_30
Mid (context):    MIN_1, MIN_5, MIN_15 (primary HTF), MIN_30
HTF (bias):       HOUR_1, HOUR_4
Macro (USDX):     DAILY, WEEKLY, MONTHLY
```

- `toSeconds()` — exhaustive switch (compiler-enforced — no `default`, so a new enum constant forces every switch site to be updated) returning duration in seconds; `MONTHLY` is a 30-day approximation. Used by `CandleAggregator` to detect period rollover.
- `isMacro()` → true for `DAILY`/`WEEKLY`/`MONTHLY`, used by `UsdxBiasEngine`.
- `isPrimaryHtf()` → true only for `MIN_15`.
- `isExecutionTf()` → true for the three `SECONDS_*` values.
- `displayName()` → human-readable label (`"4H"`, `"15min"`) for logs and reports **only** — never for serialization or lookups, since the backtest disk cache and the DXY feed's own disk cache both round-trip candles through `Timeframe.valueOf(name())`, which `displayName()` is not compatible with.

---

## Structural-analysis primitives: `SwingPoint` and `SwingType`

```java
record SwingPoint(Instant time, String instrument, SwingType type, long price, Timeframe timeframe)
enum SwingType { HIGH, LOW }
```

A significant local pivot (peak or trough). Consumed by three places in `aletheia-strategy`:
1. `MarketStructureAnalyser` — compares consecutive swings to classify trend (HH+HL → BULLISH, LH+LL → BEARISH).
2. `SmtDivergenceDetector` — compares two correlated instruments' swing lows/highs for divergence.
3. `JudasSwingDetector` — the swept swing is the liquidity target of the Judas Swing pattern.

The pivot lookback (how many candles on each side must be exceeded) is a constructor parameter on the *detector* side (`SwingPointDetector`/`MarketStructureAnalyser`), not stored on `SwingPoint` itself.

---

## `MarketBias` — the tri-state directional signal

```java
enum MarketBias { BULLISH, BEARISH, NEUTRAL }
```

- `invert()` — `BULLISH ↔ BEARISH`, `NEUTRAL → NEUTRAL`. This is specifically how the USDX-to-pair correlation works: `UsdxBiasEngine` computes bias *for the dollar*, and `invert()` turns it into the tradeable bias for a USD-quote pair (dollar bullish → EUR/USD bearish).
- `isDirectional()` → `this != NEUTRAL`. Gates Pillar 1 of the five-pillar signal system.

---

## `KillzoneWindow` — ICT session timing

```
LONDON_OPEN     02:00–05:00 EST
NEW_YORK_OPEN   07:00–10:00 EST
LONDON_CLOSE    10:00–12:00 EST
NONE            everything else
```

- `isActive()` → `this != NONE`.
- `displayName()` → human-readable string with the time range for logging.

Note: the five-pillar gate's Pillar 2 check is literally `killzone.isActive()` — meaning `LONDON_CLOSE` genuinely does pass the gate today, even though the project's narrative framing (in the root README) emphasizes London/NY Open as the primary windows. If you only want London/NY Open in production, that's a config/detector-tuning decision, not something enforced by this enum.

---

## News-calendar types: `ImpactLevel` and `EconomicEvent`

```java
enum ImpactLevel { HIGH, MEDIUM, LOW }
record EconomicEvent(Instant scheduledTime, String currency, String eventName, ImpactLevel impact)
```

Mirrors Forex Factory's impact color-coding. `EconomicEvent.isHighImpact()` → `impact == HIGH`. Only `HIGH`-impact events trigger the ±15-minute trading blackout in `aletheia-calendar`'s `EconomicCalendarService` — `MEDIUM`/`LOW` are informational only in the current gate logic.

---

## `PriceScale` — the most important class in the codebase

A non-instantiable utility class (private constructor) that converts between human-readable `double` prices and scaled `long` integers, eliminating floating-point rounding error from every price computation in the project. **This is the reason nothing in Aletheia does price arithmetic in `double`.**

### Scale factors (`scaleFor(String instrument)`)

| Instrument group | Scale | Effective decimals |
|---|---|---|
| `EUR_USD`, `GBP_USD`, `AUD_USD`, `USD_CHF`, `USD_CAD` | `100_000L` | 5 |
| `USD_JPY`, `EUR_JPY`, `GBP_JPY` | `1_000L` | 3 |
| `XAU_USD` (gold) | `100L` | 2 |
| `DOLLAR_IDX` | `1_000L` | 3 |
| `US30_USD`, `NAS100_USD`, `SPX500_USD` | `10L` | 1 |
| anything else | `100_000L` (default) | 5 |

### Conversions

- `toScaled(double price, String instrument)` → `Math.round(price * scaleFor(instrument))`. Called exactly once per value, at the boundary where a `double` first enters the system (parsing OANDA's JSON, or converting Dukascopy's raw pip ints). `Math.round` absorbs float imprecision (`1.08250 * 100000` can compute as `108249.99999...` without it).
- `toDouble(long scaled, String instrument)` → `(double) scaled / scaleFor(instrument)`. **Display-only** — the class's own documentation is explicit that this result must never re-enter arithmetic.
- `onePip(String instrument)` → the scaled-unit value of one pip (currently `10L` across every branch — the same numeric constant means a different real-world pip size per instrument because it's relative to that instrument's own scale). Used by `RiskManager` for stop-loss distance math and throughout `aletheia-backtest` for pip-denominated P&L reporting.

### Verified behavior (from `PriceScaleTest`)

```java
toScaled(1.08250, "EUR_USD")  == 108_250L
toDouble(108_250L, "EUR_USD") == 1.08250
toScaled(1920.50, "XAU_USD")  == 192_050L   // gold, scale 100
onePip("EUR_USD")              == 10L
```

And the whole point demonstrated directly: `108.260 - 108.250` in raw `double` arithmetic is *not* exactly `0.010`, while `108_260L - 108_250L == 10L` always is.

---

## Summary table

| Type | Kind | Role |
|---|---|---|
| `Tick` | record | Raw bid/ask quote |
| `Candle` | record | OHLCV bar at a `Timeframe` |
| `Timeframe` | enum | Fractal scale parameter, `SECONDS_5`→`MONTHLY` |
| `SwingPoint` | record | A pivot high/low |
| `SwingType` | enum | `HIGH` / `LOW` |
| `MarketBias` | enum | `BULLISH` / `BEARISH` / `NEUTRAL`, with `invert()` |
| `KillzoneWindow` | enum | ICT session windows |
| `ImpactLevel` | enum | News event severity |
| `EconomicEvent` | record | A scheduled news release |
| `PriceScale` | utility | double ⇄ scaled-long conversion, per-instrument |
