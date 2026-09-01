# Aletheia HFT

**An algorithmic forex trading engine implementing the ICT (Inner Circle Trader) methodology as deterministic, test-driven code.**

Aletheia ingests live market data, detects institutional trading setups (Fair Value Gaps, Order Blocks, market structure shifts, liquidity sweeps, SMT divergence), validates them against a five-pillar gatekeeper, and executes managed trades through the OANDA v3 REST API — with full backtesting, partial-take-profit trade management, and production observability.

> **Status:** Core engine, backtesting, and live execution are complete and running against the OANDA practice account, including position reconciliation and Prometheus/Grafana monitoring. See [Project Status & Roadmap](#project-status--roadmap).

---

## Table of Contents

- [What It Does](#what-it-does)
- [The Strategy in Brief](#the-strategy-in-brief)
- [Architecture](#architecture)
- [Module Breakdown](#module-breakdown)
- [Technology Stack](#technology-stack)
- [Data Flow](#data-flow)
- [The Five-Pillar Signal System](#the-five-pillar-signal-system)
- [Backtesting](#backtesting)
- [Risk & Trade Management](#risk--trade-management)
- [Observability](#observability)
- [Getting Started](#getting-started)
- [Running a Backtest](#running-a-backtest)
- [Running the Live Engine](#running-the-live-engine)
- [Admin & Monitoring Endpoints](#admin--monitoring-endpoints)
- [Configuration Reference](#configuration-reference)
- [Design Principles](#design-principles)
- [Project Status & Roadmap](#project-status--roadmap)
- [Testing](#testing)
- [Known Limitations & Gotchas](#known-limitations--gotchas)
- [Disclaimer](#disclaimer)

---

## What It Does

Aletheia turns discretionary ICT chart-reading into a fully automated system:

- **Streams** live tick data from OANDA and **aggregates** it into multi-timeframe candles in real time.
- **Loads** years of historical tick data from Dukascopy for backtesting, with a two-tier (in-memory + on-disk CSV) cache so repeat runs skip redownloading.
- **Detects** ICT price structures: Fair Value Gaps, Order Blocks, swing points, market structure (HH/HL/LH/LL), Judas Swings, and SMT divergence across correlated pairs.
- **Determines** directional bias from the US Dollar Index across multiple timeframes, sourced from real Dukascopy DXY data with a disk-cached warm-start and a synthetic EUR/USD-inverse fallback.
- **Filters** trades through killzone timing, economic-news blackouts (with a service + CSV fallback chain), and a strict five-pillar confluence gate.
- **Sizes** positions by fixed-percentage risk, **executes** limit orders with stop-loss and take-profit, and **manages** them through a two-stage partial take-profit (TP1/TP2) with breakeven stops.
- **Reconciles** live broker state every 10 seconds — detecting fills, broker-side closes, and TP1 hits — and books OANDA's authoritative realised P&L.
- **Protects** capital with a circuit breaker, order-expiry logic, and an emergency kill switch.
- **Backtests** the entire strategy against historical data with realistic spread simulation, per-candle SMT divergence, and the exact same partial-TP trade management used live.
- **Observes** itself in production via Prometheus metrics (signals, orders, news blackouts, P&L, stream health) and pre-built Grafana dashboards.

Everything is built **test-first** — the strategy detectors, execution lifecycle, and calendar guard are all validated against fabricated candle sequences representing exact ICT scenarios.

---

## The Strategy in Brief

Aletheia implements a specific, well-defined ICT model. It only takes a trade when **all** of the following align:

1. **Dollar bias is clear** — the US Dollar Index shows agreeing structure across Monthly, Weekly, and Daily timeframes. Since EUR/USD, GBP/USD, etc. move inversely to the dollar, this sets the directional filter.
2. **We are inside an active killzone** — London Open (02:00–05:00 EST), New York Open (07:00–10:00 EST), or London Close (10:00–12:00 EST), when institutional activity peaks.
3. **No high-impact news** is within ±15 minutes (NFP, FOMC, CPI, central-bank rate decisions).
4. **Price is at a higher-timeframe PD Array** — an unfilled Fair Value Gap or Order Block on the 1-hour chart, matching the dollar-bias direction.
5. **A Judas Swing fires** on the lower timeframe — price sweeps a prior swing (grabbing liquidity), then displaces aggressively in the true direction, leaving a Fair Value Gap that becomes the entry zone.

When a correlated pair confirms the setup through **SMT divergence** (configured per-instrument — see [`trading.smt-pairs`](#configuration-reference)), the signal grade is upgraded from **A** to **A+** (the highest-confidence tier).

---

## Architecture

Aletheia is a **Maven multi-module** project. Each module has a single responsibility and depends only on the layers beneath it, keeping the strategy logic completely decoupled from data sources and execution venues.

```
                       ┌────────────────────────────────┐
                       │          aletheia-api            │  Spring Boot app:
                       │  wiring, REST endpoints,          │  live pipeline,
                       │  live signal loop, position       │  admin/health,
                       │  reconciliation, DXY feed         │  metrics sampling
                       └────────────────┬──────────────────┘
        ┌───────────────┬───────────────┼──────────┬────────────────┬────────────────┐
        ▼               ▼               ▼          ▼                ▼                ▼
┌──────────────┐ ┌──────────────┐ ┌──────────┐ ┌──────────────┐ ┌───────────────┐ ┌──────────┐
│ aletheia-    │ │ aletheia-    │ │ aletheia-│ │ aletheia-    │ │ aletheia-     │ │ (tests   │
│ execution    │ │ backtest     │ │ strategy │ │ calendar     │ │ observability │ │  only)   │
│ orders, risk,│ │ replay engine│ │ ICT      │ │ news guard   │ │ Prometheus    │ │          │
│ kill switch  │ │ + metrics    │ │ detectors│ │ HTTP+CSV     │ │ metrics       │ │          │
└──────┬───────┘ └──────┬───────┘ └────┬─────┘ └──────┬───────┘ └───────────────┘ └──────────┘
       │                │               │              │
       └────────────────┴───────┬───────┴──────────────┘
                                 ▼
                       ┌──────────────────┐
                       │   aletheia-data   │  OANDA stream, candle
                       │  streaming, agg,  │  aggregation, Dukascopy
                       │  persistence      │  loader, repositories
                       └────────┬──────────┘
                                ▼
                       ┌──────────────────┐
                       │   aletheia-core   │  Domain records: Tick,
                       │   domain model    │  Candle, Timeframe,
                       │ (zero dependencies)│  SwingPoint, PriceScale…
                       └──────────────────┘
```

`aletheia-api` is the only module that depends on every other module — it is purely an *assembler*: Spring wiring, REST controllers, and scheduled tasks. All real logic lives in the modules beneath it, each independently testable with no Spring context required.

**Design principles** (see [Design Principles](#design-principles) for the full list):

- **The strategy never knows whether it is live or backtesting.** The same `SignalAggregator` and detectors run identically over a historical candle list or a live stream. Only the data source and execution target differ.
- **No look-ahead bias.** At every point in a backtest, the engine sees only candles that had closed at that moment — enforced explicitly via `!c.time().isAfter(now)` filters throughout `BacktestEngine`.
- **Prices are scaled `long`s, never `double`s**, in any arithmetic path. `PriceScale` is the single source of truth for per-instrument decimal precision; doubles appear only at the two API boundaries (parsing OANDA JSON, parsing Dukascopy pips) and are converted away immediately.
- **Immutability by default.** Ticks and closed candles are immutable records; only actively-building candles (`CandleAggregator`'s internal `OpenCandle`) are mutable, and only on one thread.
- **Broker-agnostic by interface.** `BrokerExecutor` and `PricingStream` are the only seams the live engine touches — `OandaOrderExecutor`/`OandaPricingStream` are today's implementations, swappable without touching strategy, risk, or order-management code.

---

## Module Breakdown

| Module | Responsibility | Key Components | README |
|--------|----------------|-----------------|--------|
| **aletheia-core** | Pure domain model, zero dependencies | `Tick`, `Candle`, `Timeframe`, `SwingPoint`, `MarketBias`, `KillzoneWindow`, `ImpactLevel`, `EconomicEvent`, `PriceScale` | [README](aletheia-core/README.md) |
| **aletheia-data** | Market data ingestion & storage | `OandaPricingStream`, `OandaTickParser`, `CandleAggregator`, `TickRepository`, `CandleRepository`, `Bi5TickParser`, `DukascopyHistoryLoader` | [README](aletheia-data/README.md) |
| **aletheia-strategy** | The ICT engine | `FairValueGapDetector`, `OrderBlockDetector`, `SwingPointDetector`, `MarketStructureAnalyser`, `KillzoneService`, `UsdxBiasEngine`, `JudasSwingDetector`, `SmtDivergenceDetector`, `SignalAggregator` | [README](aletheia-strategy/README.md) |
| **aletheia-calendar** | Economic-news guard | `EconomicCalendarService`, `ForexFactoryHtmlParser`, `CsvCalendarLoader`, `HttpCalendarSource`, `CalendarDataSource` | [README](aletheia-calendar/README.md) |
| **aletheia-execution** | Order & risk management | `RiskManager`, `OrderManager`, `ManagedOrder`, `BrokerExecutor`, `OandaOrderExecutor`, `KillSwitch`, `OrderExpiryService` | [README](aletheia-execution/README.md) |
| **aletheia-backtest** | Historical replay & metrics | `BacktestEngine`, `BacktestRunner`, `SimulatedTrade`, `PerformanceMetrics`, `HistoricalCandleBuilder`, `SyntheticUsdxBuilder` | [README](aletheia-backtest/README.md) |
| **aletheia-observability** | Metrics facade | `MetricsService` (Micrometer/Prometheus) | [README](aletheia-observability/README.md) |
| **aletheia-api** | Spring Boot application | `TradingEngineConfig`, `TradingEngineRunner`, `LiveSignalService`, `PositionMonitor`, `DxyFeedService`, `CalendarLoaderService`, `MetricsUpdater`, `AdminController`, `HealthController` | [README](aletheia-api/README.md) |

---

## Technology Stack

**Language & Build**
- Java 21
- Maven (multi-module, wrapper-pinned for reproducible builds)

**Frameworks & Libraries**
- Spring Boot 3.2.3 — application container, dependency injection, REST, scheduling, Actuator
- OkHttp — OANDA REST/streaming client, calendar-service HTTP client
- Jackson — JSON parsing
- Jsoup — Forex Factory HTML calendar parsing
- Apache Commons Compress + XZ — LZMA decompression of Dukascopy `.bi5` tick files
- Micrometer + Prometheus registry — metrics, scraped via Spring Boot Actuator
- JUnit 5 + AssertJ — testing

**Data & Infrastructure**
- TimescaleDB (PostgreSQL + time-series extension) — tick, candle, calendar-event, and trade-journal storage, accessed via plain Spring JDBC (no ORM)
- Redis — provisioned in the dev stack as a cache layer
- Prometheus + Grafana — metrics and a pre-built dashboard (`grafana/provisioning/dashboards/aletheia-dashboard.json`)
- Adminer — database inspection
- Docker Compose — local development stack

**External Services**
- **OANDA v3** (practice) — live pricing stream + order execution
- **Dukascopy** — historical tick data (forex pairs + `DOLLAR_IDX` dollar index)
- **Aletheia Calendar Service** (a separate deployed service, `calendar.service.url`) — primary economic-calendar source, with Forex Factory scraping and a JBlanked API integration behind it; a local CSV is the fallback

---

## Data Flow

**Live mode**

```
OANDA stream ──► OandaTickParser ──► CandleAggregator ──► CandleRepository (TimescaleDB)
                                            │
                                            ├──► TickRepository (batched writes)
                                            │
                                            └──► LiveSignalService (on each LTF candle close,
                                                  for TRADED instruments only — SMT-partner-only
                                                  instruments are buffered but never evaluated)
                                                       │
                                                       ├─ USDX bias (real DXY feed, disk-cached)
                                                       ├─ SMT divergence (explicit trading.smt-pairs)
                                                       ├─ killzone + news gate
                                                       ▼
                                                 SignalAggregator.evaluate()
                                                       │  (signal?)
                                                       ▼
                                                 OrderManager ──► BrokerExecutor ──► OANDA
                                                       ▲
                                                       │  every 10s: reconcile fills/closes,
                                                       │  manage TP1 → breakeven
                                                 PositionMonitor ◄── BrokerExecutor.getOpenTrades()
```

**Backtest mode**

```
Dukascopy .bi5 ──► Bi5TickParser ──► HistoricalCandleBuilder ──► BacktestEngine
   (LZMA, disk-cached                                                  │
    CSV per instrument/range)                            same strategy engine,
                                                    simulated fills, spread, and
                                                 per-candle SMT — no precomputation
                                                                        ▼
                                                                PerformanceMetrics
```

The **`CandleAggregator`** is shared by both paths — the exact same aggregation code builds candles from live ticks and from historical ticks, guaranteeing that what you backtest is structurally what you'd trade. The backtest engine additionally mirrors the *trade-management* rules exactly (TP1/TP2 partials, breakeven, per-candle SMT) so its numbers are directly comparable to live behavior — see [`aletheia-backtest`](aletheia-backtest/README.md) for the one deliberate divergence (a conservative same-candle stop/target ambiguity rule).

---

## The Five-Pillar Signal System

`SignalAggregator` is the gatekeeper, checked in this order — it short-circuits and logs a `Pillar N FAIL` reason on the first failure:

| Pillar | Question | Component |
|--------|----------|-----------|
| **1. USDX Bias** | Is the dollar's direction clear (`isTradeable()`) and does the pair's inverse bias resolve to a direction? | `UsdxBiasEngine` (Monthly/Weekly/Daily consensus → HIGH/MEDIUM/LOW confidence) |
| **2. Killzone** | Is `KillzoneWindow.isActive()` true right now? | `KillzoneService` (EST/EDT-aware) |
| **3. News Clearance** | Is there no high-impact event within ±15 min for this instrument's currencies? | `EconomicCalendarService` (called by the caller — `BacktestEngine`/`LiveSignalService` — and passed in via `MarketContext`, not by `aletheia-strategy` itself) |
| **4. HTF PD Array** | Is price at a 1-hour FVG *or* Order Block matching the required bias? | `FairValueGapDetector`, `OrderBlockDetector` |
| **5. Judas Swing** | Sweep + ATR-scaled displacement + FVG on the lower timeframe? | `JudasSwingDetector` |

**A+ upgrade:** if `SmtDivergenceDetector` finds a divergence between the traded pair and its *explicitly configured* correlated partner (`trading.smt-pairs`, e.g. `EUR_USD:GBP_USD`) at the same moment, the signal grade is promoted from **A** to **A+**. An instrument with no configured partner (e.g. `USD_JPY` in the current default config) trades A-only. SMT is additive, not gating — a missing/negative SMT check never blocks a trade on its own.

Full detector-by-detector algorithm detail (exact FVG/displacement/sweep definitions, tunable parameters, worked examples) is in [`aletheia-strategy`](aletheia-strategy/README.md).

---

## Backtesting

`BacktestEngine` performs a walk-forward, event-driven replay with no look-ahead bias, realistic **spread simulation**, a **cooldown** between trades, and the *same* two-stage TP1/TP2 partial-close trade management (`SimulatedTrade`) used live:

- TP1 at 2R closes 70% of the position and moves the stop to breakeven; TP2 at 3R closes the 30% runner.
- Within a single candle, if the high/low range spans **both** a target and the active stop, the engine can't know which was touched first — it conservatively assumes the stop hit first, so the backtest never flatters itself.
- SMT divergence is computed **per candle** from the currently-visible window (`runWithLiveSmt`), exactly mirroring `LiveSignalService.detectSmt()`, rather than precomputed once for the whole run.

Reported metrics (`PerformanceMetrics`): Net P&L (pips), Gross Profit/Loss, Win Rate, Profit Factor, Max Drawdown, Sharpe Ratio, Average Winner R:R, A vs A+ breakdown, and a per-killzone breakdown.

> ICT strategies are intentionally low win-rate / high R:R. With the two-stage TP structure, a full win nets roughly +2.3R, a breakeven scratch (TP1 hit, runner stopped at breakeven) nets +1.4R, and a full loss is -1.0R — so win rate alone understates the strategy's edge; profit factor and the win/breakeven/loss mix matter more.

See [`aletheia-backtest`](aletheia-backtest/README.md) for the exact TP1/TP2 math, the disk-cache mechanics, and how to read a run's output.

---

## Risk & Trade Management

- **Position sizing** — `RiskManager` risks a fixed percentage (`trading.risk-percentage`, default 1%) of live account balance per trade. Lot size is derived from the stop-loss distance so the dollar risk is constant regardless of setup.
- **Order placement** — `OrderManager` places limit orders with SL/TP, enforces `trading.max-open-positions`, and rejects duplicate setups (same entry/sweep within a 5-pip, 30-minute window).
- **Two-stage take-profit** — TP1 at 2R closes 70% of the position and moves the stop to breakeven on the runner; TP2 at 3R closes the remaining 30% with zero remaining risk. Live, this is driven by `PositionMonitor`'s 10-second reconciliation loop against OANDA's actual open-trades state — not a local price feed — so it survives restarts and stays truthful to what the broker actually holds.
- **Position reconciliation** — every 10 seconds, `PositionMonitor` detects PENDING→FILLED transitions (by matching OANDA's `clientExtensions` ID back to the `ManagedOrder` that created it), detects broker-side closes (SL/TP2 hit) and books OANDA's authoritative *incremental* realised P&L (USD, not pips), and executes the TP1 partial-close + breakeven-stop move when price has reached it.
- **Order expiry** — `OrderExpiryService` cancels unfilled limit orders once their killzone ends or after a 3-hour safety cutoff, since an ICT setup is only valid within the session that formed it.
- **Circuit breaker** — `OandaOrderExecutor` stops sending orders after 3 failures in 60 seconds, auto-resetting after a quiet period.
- **Kill switch** — `KillSwitch` cancels all pending orders and closes all open positions immediately, then halts trading until a manual restart. Triggerable via REST (`POST /admin/kill-switch`).

Full lifecycle detail (state machine, `BrokerExecutor` contract, the exact reconciliation algorithm) is in [`aletheia-execution`](aletheia-execution/README.md) and [`aletheia-api`](aletheia-api/README.md#positionmonitor).

---

## Observability

- **`MetricsService`** (`aletheia-observability`) is the single facade the rest of the engine calls into — `recordSignal`, `recordOrderPlaced`, `recordNewsBlackout`, `recordCandle`, plus gauges for open positions, kill-switch state, stream connectivity, realised P&L, and tick count. Nothing outside this module touches Micrometer directly.
- **`MetricsUpdater`** (`aletheia-api`) samples live component state (`PricingStream.isRunning()`, `KillSwitch.isActive()`, `OrderManager.openPositionCount()`, summed `realisedPnl()` across all orders) every 5 seconds and pushes it into the gauges.
- Metrics are exposed at `/actuator/prometheus` (Spring Boot Actuator), scraped by Prometheus every 15 seconds, and visualised in a pre-provisioned Grafana dashboard (`grafana/provisioning/dashboards/aletheia-dashboard.json`) at `localhost:3000`.

---

## Getting Started

### Prerequisites

- Java 21+
- Docker & Docker Compose
- An OANDA practice account + API token

### 1. Start the infrastructure

```bash
./scripts/start-infra.sh
# or directly:
docker compose -f docker/docker-compose.dev.yml up -d
```

This launches TimescaleDB (host port **5433**, container port 5432), Redis, Prometheus, Grafana, and Adminer. Check status any time with `./scripts/status.sh`.

### 2. Configure credentials

```bash
export OANDA_API_KEY="your-practice-api-key"
export OANDA_ACCOUNT_ID="101-004-XXXXXXX-XXX"
```

Streaming and REST base URLs default to OANDA's **fxpractice** endpoints in `aletheia-api/src/main/resources/application.properties`.

### 3. Build

```bash
./mvnw clean install
```

This compiles all modules and runs the full test suite.

---

## Running a Backtest

Configure the run in `aletheia-backtest/src/main/java/com/aletheia/backtest/RunBacktest.java` (instrument, SMT pair, date range, risk settings), then use the helper script:

```bash
./scripts/run-backtest.sh [optional-log-name]
```

which builds (skipping tests), runs the backtest, tees output to a timestamped log file, and prints the combined summary. Equivalent manually:

```bash
./mvnw clean install -DskipTests -q
./mvnw exec:java -pl aletheia-backtest -Dexec.jvmArgs="-Xmx12g"
```

The runner downloads the required Dukascopy tick data (including `DOLLAR_IDX` with a 3-month lead-in for structure) — checking its two-tier cache first — aggregates candles, loads the economic calendar, replays the strategy with per-candle SMT and TP1/TP2 management, and prints a full performance report plus a per-trade log.

> A full-year, multi-pair backtest processes tens of millions of ticks and can take a long time on a cold cache — the disk cache under `data/cache/` makes repeat runs over the same instrument/date-range near-instant. Start with a one-week or one-month range to validate configuration.

---

## Running the Live Engine

```bash
./scripts/run-engine.sh
```

which checks that TimescaleDB is up and OANDA credentials are set before running:

```bash
./mvnw spring-boot:run -pl aletheia-api
```

On startup the engine:

1. Wires the full pipeline (stream → aggregator → repositories → live signal loop → position monitor → metrics).
2. Connects to the OANDA pricing stream for the trade set **plus** any configured SMT-partner-only instruments, and begins persisting ticks.
3. Warm-starts the **DXY feed** from an on-disk cache if one exists and is recent enough (so trading can resume almost immediately after a restart instead of re-seeding 90 days); otherwise seeds it fresh in the background.
4. Warms up in-memory candle buffers from the database.
5. On every LTF candle close (default `MIN_5`) during a killzone, for each *traded* instrument, evaluates the five pillars and — on a valid signal — sizes, places, and hands the order to `PositionMonitor` for lifecycle management.
6. Every 10 seconds, `PositionMonitor` reconciles local order state against OANDA's actual open trades and drives TP1/breakeven.

Until the DXY feed has data, the dollar bias is `NEUTRAL` and the engine deliberately takes no trades (flat is safer than wrong) — this only affects a true cold start with no cache.

---

## Admin & Monitoring Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/health` | Liveness — stream status, tick count, kill-switch state |
| `GET` | `/admin/status` | Engine status: kill-switch state/reason/time, open positions, pending & total orders |
| `GET` | `/admin/positions` | Detailed list of open positions (entry, SL, TP1/TP2, units, grade, killzone, realised P&L) |
| `GET` | `/admin/calendar` | Cached economic events, for sanity-checking timezone alignment and cache freshness |
| `POST` | `/admin/kill-switch?reason=...` | Emergency shutdown — cancel & close everything |
| `GET` | `/actuator/health`, `/actuator/prometheus` | Spring Boot Actuator — generic health + metrics scrape endpoint |

```bash
# Check health
curl http://localhost:8080/health

# Emergency stop
curl -X POST "http://localhost:8080/admin/kill-switch?reason=manual"
```

Grafana (`localhost:3000`, admin/admin), Prometheus (`localhost:9090`), and Adminer (`localhost:8081`) are available from the dev stack.

---

## Configuration Reference

Selected keys from `aletheia-api/src/main/resources/application.properties` (all overridable via environment variables in the usual Spring way):

| Key | Default | Meaning |
|-----|---------|---------|
| `trading.instruments` | `EUR_USD,GBP_USD,AUD_USD,USD_JPY` | Instruments actually traded |
| `trading.smt-partners` | `NZD_USD` | Streamed/buffered *only* for SMT correlation — never traded directly |
| `trading.smt-pairs` | `EUR_USD:GBP_USD,GBP_USD:EUR_USD,AUD_USD:NZD_USD` | Explicit `TRADED:PARTNER` SMT pairings; an unlisted instrument trades A-grade only |
| `trading.htf-timeframe` / `trading.ltf-timeframe` | `HOUR_1` / `MIN_5` | Structure timeframe / trigger (evaluation) timeframe |
| `trading.risk-percentage` | `0.01` | Fraction of account balance risked per trade |
| `trading.max-open-positions` | `6` | Concurrent open positions cap |
| `trading.tp1-multiple` / `trading.tp2-multiple` | `2.0` / `3.0` | TP1/TP2 targets, in R-multiples |
| `trading.order-expiry-check-seconds` | `60` | How often `OrderExpiryService` sweeps pending orders |
| `trading.dxy.enabled` | `true` | Real Dukascopy DXY feed vs. synthetic EUR/USD-inverse fallback |
| `trading.dxy.refresh-minutes` | `120` | DXY feed refresh cadence |
| `trading.dxy.cache-file` | `data/cache/dxy_feed.csv` | DXY feed's warm-start disk cache |
| `trading.calendar.csv-path` | `data/calendar_current.csv` | Calendar-service fallback CSV — **note:** the shipped file at this path uses a different column schema than `CsvCalendarLoader` expects (see [Known Limitations](#known-limitations--gotchas)) |
| `calendar.service.url` | `https://aletheia-calendar-service.onrender.com` | Primary economic-calendar HTTP source |
| `trading.calendar.refresh-cron` | `0 0 6 * * *` | Daily calendar refresh (06:00) |

See each module's README for the config keys it specifically consumes, and note in [`aletheia-api`](aletheia-api/README.md) two keys present in the properties file but not actually read by any `@Value` (`trading.dxy-instrument`, `trading.calendar.enabled`) — likely dead config.

---

## Design Principles

These are the conventions the codebase is deliberately built around — worth following if you're extending it:

1. **Scaled longs everywhere prices flow.** `PriceScale.toScaled`/`toDouble` are the only conversion points; a `double` price never survives past the boundary where it entered (OANDA JSON parsing, Dukascopy pip conversion, or a formatted string sent back out). This eliminates an entire class of floating-point P&L bugs.
2. **The strategy is data-source-agnostic.** `SignalAggregator`, every detector, and `MarketContext` never know whether they're being fed a live rolling buffer or a historical `List<Candle>` slice. The only thing that differs between backtest and live is who builds the `MarketContext` and what happens after `evaluate()` returns a signal.
3. **No look-ahead, enforced structurally.** Both `BacktestEngine.runInternal`/`runWithLiveSmt` build every candle window with an explicit `!c.time().isAfter(now)` filter — it's not a convention that could quietly slip, it's baked into how the visible-candle lists are constructed each iteration.
4. **Interfaces at every external seam.** `BrokerExecutor` hides OANDA from `OrderManager`/`PositionMonitor`/`LiveSignalService`; `PricingStream` hides the transport from everything downstream of it; `CalendarDataSource` hides HTTP-vs-CSV from `EconomicCalendarService`; `TickListener`/`CandleListener` hide "is this live or backtest data" from every consumer. Each interface exists because a second implementation was explicitly anticipated (a FIX-based broker, a different calendar source), not as speculative abstraction.
5. **Fail flat, not wrong.** A missing DXY feed returns `NEUTRAL` bias rather than falling back silently to a worse signal; an empty economic calendar loudly warns that news protection is off rather than pretending no news exists; a failed backtest disk-cache read falls back to downloading rather than crashing. The system is built to degrade toward *not trading* under uncertainty.
6. **Dependency direction is one-way and enforced by the module graph**, not just convention — `aletheia-core` has zero dependencies, `aletheia-strategy` depends only on `aletheia-core` (not even on `aletheia-calendar`, despite gating on a news-blackout boolean — that boolean is computed by the caller and handed in via `MarketContext`), and only `aletheia-api` is allowed to know about everything.
7. **Immutable data, single-writer mutation.** Every record that crosses a thread boundary (`Tick`, `Candle`, signals) is immutable. The one deliberately mutable object (`CandleAggregator`'s in-progress `OpenCandle`) is documented as mutated by exactly one thread.
8. **Every trade-management rule that exists live has a backtest mirror**, and vice versa — the TP1/TP2/breakeven math in `SimulatedTrade` matches `ManagedOrder`/`PositionMonitor` deliberately, specifically so backtest results are a fair prediction of live behavior rather than an idealized upper bound.

---

## Project Status & Roadmap

| Milestone | Description | Status |
|-----------|-------------|--------|
| **M0** | Project foundation, module structure, CI, Docker | ✅ Complete |
| **M1** | Live market data (OANDA stream, aggregation, Dukascopy loader) | ✅ Complete |
| **M2** | ICT strategy engine (FVG, OB, structure, killzones, USDX, Judas, aggregator) | ✅ Complete |
| **M3** | SMT divergence + A+ grading, now with explicit per-instrument pairings | ✅ Complete |
| **M4** | Economic calendar / news guard, with HTTP service + CSV fallback | ✅ Complete |
| **M5** | Backtesting engine, metrics, per-candle SMT, disk-cached historical data | ✅ Complete |
| **M6** | Live execution (risk, orders, circuit breaker, kill switch, expiry) | ✅ Complete |
| **M7** | Cloud & DevOps — live pipeline, admin/health endpoints, disk-cached DXY warm-start, Prometheus/Grafana observability | ✅ Complete |
| **M8** | Two-stage TP1/TP2 trade management with live broker reconciliation (`PositionMonitor`) | ✅ Complete |
| **M9** | Extended paper-trading validation on OANDA practice, live-vs-backtest comparison | 🔨 In progress |

**Recently completed**
- `PositionMonitor` — 10-second reconciliation loop matching broker trades to `ManagedOrder`s via OANDA `clientExtensions` IDs, driving TP1 partial-close/breakeven and booking OANDA's authoritative realised P&L.
- `SimulatedTrade`/`BacktestEngine` rewritten to mirror the exact live two-stage TP1/TP2 model, with per-candle (not precomputed) SMT divergence.
- Explicit `trading.smt-pairs` configuration, replacing the old "the other configured instrument is automatically the SMT partner" assumption — now supports SMT-partner-only streamed instruments (e.g. `NZD_USD` for `AUD_USD`) and instruments with no partner at all (`USD_JPY`, A-grade only).
- `DxyFeedService` disk-cache warm-start, so a restart doesn't require re-seeding 90 days of DXY history before trading can resume.
- `MetricsService`/`MetricsUpdater` and a provisioned Grafana dashboard for production observability.

**Next up**
- Extended paper-trading run comparing live results against the backtest under the new TP1/TP2 model (M9).
- Reconcile the `data/calendar_current.csv` schema mismatch noted below.

---

## Testing

The project is built test-first using JUnit 5 and AssertJ. Detectors are validated against fabricated candle sequences representing exact ICT scenarios; execution and reconciliation paths are validated through full lifecycle simulations; calendar fallback behavior is validated by pointing both sources at unreachable/nonexistent locations and confirming a safe, non-throwing degradation.

```bash
# Full suite
./mvnw clean install

# A single module
./mvnw test -pl aletheia-strategy

# A single test class
./mvnw test -pl aletheia-strategy -Dtest=FairValueGapDetectorTest
```

CI (`.github/workflows/ci.yml`) runs `./mvnw clean verify -Dspring.profiles.active=test` on every push to `main`/`develop`/`feature/*` and every PR to `main`, uploading the JaCoCo coverage report as a build artifact.

---

## Known Limitations & Gotchas

- **`data/calendar_current.csv` schema mismatch** — `CalendarLoaderService` defaults its CSV fallback path to this file, but the file uses columns `scheduled_time_utc,name,currency,impact,source` while `CsvCalendarLoader` expects `date,time,currency,impact,event`. Every row is silently skipped if this file is ever actually needed as a fallback. The other calendar CSVs in `data/` (`calendar_2023.csv`, etc.) use the correct schema. Worth fixing before relying on the CSV fallback in anger.
- **Two dead/unused config keys** in `application.properties`: `trading.dxy-instrument` and `trading.calendar.enabled` are set but not read by any `@Value` in `aletheia-api` (the real keys are `trading.dxy.instrument` and there's no equivalent enable/disable flag for the calendar loader at all).
- **`DukascopyHistoryLoader` has no on-disk cache of its own** — `aletheia-backtest`'s `BacktestRunner` adds a disk-cache layer *around* it (keyed by instrument/date-range CSVs under `data/cache/`), but calling the loader directly (as `DxyFeedService` does) re-downloads every time.
- **Forex Factory scraper fragility** — `ForexFactoryHtmlParser` depends entirely on FF's current CSS class names and infers the year from a caller-supplied reference date rather than parsing it from the page, so a page spanning a year boundary would mis-year January rows. It isn't currently wired into the live calendar fallback chain (that's the HTTP calendar service / CSV loader) — it exists as a standalone, independently tested component.
- **DXY timeframe proxies** — both live and backtest approximate Monthly/Weekly/Daily USDX structure using Daily/4-hour/1-hour DXY candles, since real monthly/weekly candles would need years of lead-in data.
- **Practice only** — the system currently targets OANDA's practice environment. Live-money trading would require additional operational safeguards, monitoring, and a formal validation period.

---

## Disclaimer

Aletheia is a personal engineering project built to explore algorithmic trading, systems design, and test-driven development. It is **not financial advice** and is **not a guarantee of profit**. Trading foreign exchange carries substantial risk. Backtested performance does not predict future results. Use at your own risk.

---

*Built with Java, Spring Boot, and a great deal of TDD.*
