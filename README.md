# Aletheia HFT

**An algorithmic forex trading engine implementing the ICT (Inner Circle Trader) methodology as deterministic, test-driven code.**

Aletheia ingests live market data, detects institutional trading setups (Fair Value Gaps, Order Blocks, market structure shifts, liquidity sweeps, SMT divergence), validates them against a five-pillar gatekeeper, and executes managed trades through the OANDA v3 REST API — with full backtesting, risk management, and emergency controls.

> **Status:** Core engine complete and validated. Live pipeline wired and running against the OANDA practice account. Currently finishing the live signal-generation loop and real-time dollar-index feed ahead of full paper-trading validation.

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
- [Risk & Execution](#risk--execution)
- [Getting Started](#getting-started)
- [Running a Backtest](#running-a-backtest)
- [Running the Live Engine](#running-the-live-engine)
- [Admin & Monitoring Endpoints](#admin--monitoring-endpoints)
- [Project Status & Roadmap](#project-status--roadmap)
- [Testing](#testing)
- [Known Limitations](#known-limitations)

---

## What It Does

Aletheia turns discretionary ICT chart-reading into a fully automated system:

- **Streams** live tick data from OANDA and **aggregates** it into multi-timeframe candles in real time.
- **Loads** years of historical tick data from Dukascopy for backtesting.
- **Detects** ICT price structures: Fair Value Gaps, Order Blocks, swing points, market structure (HH/HL/LH/LL), Judas Swings, and SMT divergence across correlated pairs.
- **Determines** directional bias from the US Dollar Index across multiple timeframes.
- **Filters** trades through killzone timing, economic-news blackouts, and a strict confluence gate.
- **Sizes** positions by fixed-percentage risk, **executes** limit orders with stop-loss and take-profit, and **manages** them through partial take-profits and breakeven stops.
- **Protects** capital with a circuit breaker, order-expiry logic, and an emergency kill switch.
- **Backtests** the entire strategy against historical data with realistic spread simulation and full performance metrics.

Everything is built **test-first** — 240+ unit and integration tests cover every detector, every pillar, and every execution path.

---

## The Strategy in Brief

Aletheia implements a specific, well-defined ICT model. It only takes a trade when **all** of the following align:

1. **Dollar bias is clear** — the US Dollar Index shows agreeing structure across Monthly, Weekly, and Daily timeframes. Since EUR/USD, GBP/USD, etc. move inversely to the dollar, this sets the directional filter.
2. **We are inside a killzone** — London Open (02:00–05:00 EST) or New York Open (07:00–10:00 EST), when institutional activity peaks.
3. **No high-impact news** is within ±15 minutes (NFP, FOMC, CPI, central-bank rate decisions).
4. **Price is at a higher-timeframe PD Array** — an unfilled Fair Value Gap or Order Block on the 1-hour chart.
5. **A Judas Swing fires** on the lower timeframe — price sweeps a prior swing (grabbing liquidity), then displaces aggressively in the true direction, leaving a Fair Value Gap that becomes the entry zone.

When a second correlated pair confirms the setup through **SMT divergence**, the signal is upgraded from grade **A** to grade **A+** (the highest-confidence tier).

---

## Architecture

Aletheia is a **Maven multi-module** project. Each module has a single responsibility and depends only on the layers beneath it, keeping the strategy logic completely decoupled from data sources and execution venues.

```
                       ┌──────────────────────────────┐
                       │        aletheia-api           │  Spring Boot app:
                       │  wiring, REST endpoints,       │  live pipeline,
                       │  live signal loop, DXY feed    │  admin & health
                       └───────────────┬──────────────┘
             ┌───────────────┬─────────┼─────────┬────────────────┐
             ▼               ▼         ▼         ▼                ▼
   ┌──────────────┐ ┌──────────────┐ ┌────────┐ ┌──────────────┐ ┌───────────────┐
   │ aletheia-    │ │ aletheia-    │ │ aleth- │ │ aletheia-    │ │ aletheia-     │
   │ execution    │ │ backtest     │ │ eia-   │ │ calendar     │ │ observability │
   │ orders, risk │ │ replay engine│ │ strat- │ │ news guard   │ │ metrics       │
   │ kill switch  │ │ metrics      │ │ egy    │ │ FF scraper   │ │               │
   └──────┬───────┘ └──────┬───────┘ └───┬────┘ └──────┬───────┘ └───────────────┘
          │                │             │             │
          └────────────────┴──────┬──────┴─────────────┘
                                   ▼
                         ┌──────────────────┐
                         │  aletheia-data    │  OANDA stream, candle
                         │  streaming, agg,  │  aggregation, Dukascopy
                         │  persistence      │  loader, repositories
                         └────────┬─────────┘
                                  ▼
                         ┌──────────────────┐
                         │  aletheia-core    │  Domain records: Tick,
                         │  domain model     │  Candle, Timeframe,
                         │  (no dependencies)│  SwingPoint, PriceScale…
                         └──────────────────┘
```

**Design principles**

- **The strategy never knows whether it is live or backtesting.** The same `SignalAggregator` and detectors run identically over a historical candle list or a live stream. Only the data source and execution target differ.
- **No look-ahead bias.** At every point in a backtest, the engine sees only candles that had closed at that moment.
- **Immutability by default.** Ticks and closed candles are immutable records; only actively-building candles are mutable, and only on one thread.
- **Deterministic and testable.** No mocking frameworks — components are tested with fabricated data that represents exact ICT scenarios.

---

## Module Breakdown

| Module | Responsibility | Key Components |
|--------|----------------|----------------|
| **aletheia-core** | Pure domain model, zero dependencies | `Tick`, `Candle`, `Timeframe`, `SwingPoint`, `MarketBias`, `KillzoneWindow`, `ImpactLevel`, `EconomicEvent`, `PriceScale` |
| **aletheia-data** | Market data ingestion & storage | `OandaPricingStream`, `OandaTickParser`, `CandleAggregator`, `TickRepository`, `CandleRepository`, `Bi5TickParser`, `DukascopyHistoryLoader` |
| **aletheia-strategy** | The ICT engine | `FairValueGapDetector`, `OrderBlockDetector`, `SwingPointDetector`, `MarketStructureAnalyser`, `KillzoneService`, `UsdxBiasEngine`, `JudasSwingDetector`, `SmtDivergenceDetector`, `SignalAggregator` |
| **aletheia-calendar** | Economic-news guard | `EconomicCalendarService`, `ForexFactoryHtmlParser`, `CsvCalendarLoader`, `CalendarDataSource` |
| **aletheia-execution** | Order & risk management | `RiskManager`, `OrderManager`, `ManagedOrder`, `OandaOrderExecutor`, `KillSwitch`, `OrderExpiryService` |
| **aletheia-backtest** | Historical replay & metrics | `BacktestEngine`, `BacktestRunner`, `SimulatedTrade`, `PerformanceMetrics`, `HistoricalCandleBuilder`, `SyntheticUsdxBuilder` |
| **aletheia-observability** | Metrics/monitoring plumbing | Prometheus/metrics integration |
| **aletheia-api** | Spring Boot application | `TradingEngineConfig`, `TradingEngineRunner`, `LiveSignalService`, `DxyFeedService`, `AdminController`, `HealthController` |

---

## Technology Stack

**Language & Build**
- Java 21
- Maven (multi-module, wrapper-pinned for reproducible builds)

**Frameworks & Libraries**
- Spring Boot — application container, dependency injection, REST, scheduling
- OkHttp — OANDA REST API client
- Jackson — JSON parsing
- Jsoup — Forex Factory HTML calendar parsing
- Apache Commons Compress + XZ — LZMA decompression of Dukascopy `.bi5` tick files
- JUnit 5 + AssertJ — testing

**Data & Infrastructure**
- TimescaleDB (PostgreSQL + time-series extension) — tick and candle storage
- Redis — caching layer
- Prometheus + Grafana — metrics and dashboards
- Adminer — database inspection
- Docker Compose — local development stack

**External Services**
- **OANDA v3** (practice) — live pricing stream + order execution
- **Dukascopy** — historical tick data (forex pairs + `DOLLAR_IDX` dollar index)
- **Forex Factory** — economic calendar (with CSV fallback)

---

## Data Flow

**Live mode**

```
OANDA stream ──► OandaTickParser ──► CandleAggregator ──► CandleRepository (TimescaleDB)
                                            │
                                            ├──► TickRepository (batched writes)
                                            │
                                            └──► LiveSignalService (on each MIN_5 close)
                                                       │
                                                       ├─ USDX bias (real DXY feed)
                                                       ├─ SMT divergence check
                                                       ├─ killzone + news gate
                                                       ▼
                                                 SignalAggregator.evaluate()
                                                       │  (signal?)
                                                       ▼
                                                 OrderManager ──► OandaOrderExecutor ──► OANDA
```

**Backtest mode**

```
Dukascopy .bi5 ──► Bi5TickParser ──► HistoricalCandleBuilder ──► BacktestEngine
   (LZMA)                                                              │
                                                          same strategy engine,
                                                          simulated fills + spread
                                                                       ▼
                                                              PerformanceMetrics
```

The **`CandleAggregator`** is shared by both paths — the exact same aggregation code builds candles from live ticks and from historical ticks, guaranteeing that what you backtest is what you trade.

---

## The Five-Pillar Signal System

The `SignalAggregator` is the gatekeeper. It only emits a `TradeSignal` if **every** pillar passes; each pillar logs a rejection reason for debugging.

| Pillar | Question | Component |
|--------|----------|-----------|
| **1. USDX Bias** | Is the dollar's direction clear and tradeable? | `UsdxBiasEngine` (Monthly/Weekly/Daily consensus → HIGH/MEDIUM/LOW confidence) |
| **2. Killzone** | Are we in London Open or NY Open? | `KillzoneService` (EST/EDT-aware) |
| **3. News Clearance** | Is there no high-impact event within ±15 min? | `EconomicCalendarService` |
| **4. HTF PD Array** | Is price at a 1-hour FVG or Order Block matching our bias? | `FairValueGapDetector`, `OrderBlockDetector` |
| **5. Judas Swing** | Sweep + displacement + FVG on the 5-min chart? | `JudasSwingDetector` |

**A+ upgrade:** if `SmtDivergenceDetector` finds a divergence between the traded pair and its correlated partner (e.g. EUR/USD vs GBP/USD) at the same moment, the signal grade is promoted from **A** to **A+**.

---

## Backtesting

The backtest engine performs a walk-forward replay with no look-ahead bias, realistic **spread simulation** (default 1.5 pips), a **cooldown** between trades, and **duplicate-setup rejection**. It reports the full professional metric set:

- Net P&L (pips), Gross Profit/Loss
- Win Rate, Profit Factor
- Maximum Drawdown
- Sharpe Ratio
- Average Winner R:R
- A vs A+ grade breakdown

### Representative results — full year 2023, real DXY bias, tuned HOUR_1/MIN_5 settings

| Metric | EUR/USD | GBP/USD |
|--------|---------|---------|
| Total Trades | 389 | 397 |
| Win Rate | 32.4% | 30.7% |
| Net P&L | +1,554 pips | +1,422 pips |
| Profit Factor | 1.90 | 1.65 |
| Max Drawdown | 129.5 pips | 141.9 pips |

Moving from the initial 15-minute/1-minute configuration with a synthetic dollar proxy to **1-hour/5-minute timeframes with real Dukascopy DXY data** cut trade frequency by ~70% and roughly doubled per-trade quality — a June 2023 sample produced a **67% combined win rate across 9 trades**, with all GBP/USD trades graded A+ via working SMT divergence.

> ICT strategies are intentionally low win-rate / high R:R. At a 3:1 reward-to-risk ratio the breakeven win rate is only 25%, so a 30–35% win rate is comfortably profitable; the tuning work focused on selectivity and quality rather than raw hit-rate.

---

## Risk & Execution

- **Position sizing** — `RiskManager` risks a fixed percentage (default 1%) of live account balance per trade. Lot size is derived from the stop-loss distance so the dollar risk is constant regardless of setup.
- **Trade management** — `OrderManager` places limit orders with SL/TP, enforces a maximum concurrent-position count, and rejects duplicate setups (same entry/sweep within a price and time window).
- **Partial take-profit** — on TP1, 70% of the position closes and the stop moves to breakeven; the 30% runner targets TP2 with zero remaining risk.
- **Order expiry** — `OrderExpiryService` cancels unfilled limit orders once their killzone ends, since an ICT setup is only valid within the session that formed it.
- **Circuit breaker** — `OandaOrderExecutor` stops sending orders after 3 failures in 60 seconds, auto-resetting after a quiet period.
- **Kill switch** — `KillSwitch` cancels all pending orders and closes all open positions immediately, then halts trading until a manual restart. Triggerable via REST.

---

## Getting Started

### Prerequisites

- Java 21+
- Docker & Docker Compose
- An OANDA practice account + API token

### 1. Start the infrastructure

```bash
docker compose -f docker/docker-compose.dev.yml up -d
```

This launches TimescaleDB (port 5433), Redis, Prometheus, Grafana, and Adminer.

### 2. Configure credentials

Export your OANDA practice credentials (or place them in a local env file):

```bash
export OANDA_API_KEY="your-practice-api-key"
export OANDA_ACCOUNT_ID="101-004-XXXXXXX-XXX"
```

Streaming and REST base URLs default to OANDA's **fxpractice** endpoints in `application.properties`.

### 3. Build

```bash
./mvnw clean install
```

This compiles all modules and runs the full test suite.

---

## Running a Backtest

Configure the run in `RunBacktest.java` (instrument, SMT pair, date range, risk settings), then:

```bash
./mvnw clean install -DskipTests
./mvnw exec:java -pl aletheia-backtest -Dexec.jvmArgs="-Xmx12g"
```

The runner downloads the required Dukascopy tick data (including `DOLLAR_IDX` with a 3-month lead-in for structure), aggregates candles, loads the economic calendar, replays the strategy, and prints a full performance report plus a per-trade log.

> A full-year, two-pair backtest processes tens of millions of ticks and can take several hours, dominated by the Dukascopy download. Start with a one-week or one-month range to validate configuration. Pipe output to a file with `2>&1 | tee results.txt` for review.

---

## Running the Live Engine

```bash
./mvnw spring-boot:run -pl aletheia-api
```

On startup the engine:

1. Wires the full pipeline (stream → aggregator → repositories → live signal loop).
2. Connects to the OANDA pricing stream and begins persisting ticks.
3. Seeds the **real DXY feed** in the background (90 days from Dukascopy) for the dollar bias.
4. Warms up in-memory candle buffers from the database.
5. On every 5-minute candle close during a killzone, evaluates the five pillars and — on a valid signal — sizes, places, and manages the order.

Until the DXY feed has seeded, the dollar bias is `NEUTRAL` and the engine deliberately takes no trades (flat is safer than wrong).

---

## Admin & Monitoring Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| `GET` | `/health` | Liveness — stream status, tick count, kill-switch state |
| `GET` | `/admin/status` | Engine status, open positions, pending & total orders |
| `GET` | `/admin/positions` | Detailed list of open positions |
| `POST` | `/admin/kill-switch?reason=...` | Emergency shutdown — cancel & close everything |

```bash
# Check health
curl http://localhost:8080/health

# Emergency stop
curl -X POST "http://localhost:8080/admin/kill-switch?reason=manual"
```

Grafana (`localhost:3000`), Prometheus (`localhost:9090`), and Adminer (`localhost:8081`) are available from the dev stack.

---

## Project Status & Roadmap

| Milestone | Description | Status |
|-----------|-------------|--------|
| **M0** | Project foundation, module structure, CI, Docker | ✅ Complete |
| **M1** | Live market data (OANDA stream, aggregation, Dukascopy loader) | ✅ Complete |
| **M2** | ICT strategy engine (FVG, OB, structure, killzones, USDX, Judas, aggregator) | ✅ Complete |
| **M3** | SMT divergence + A+ grading | ✅ Complete |
| **M4** | Economic calendar / news guard | ✅ Complete |
| **M5** | Backtesting engine, metrics, real-DXY tuning | ✅ Complete |
| **M6** | Live execution (risk, orders, circuit breaker, kill switch, expiry) | ✅ Complete |
| **M7** | Cloud & DevOps — live pipeline wiring, admin/health endpoints, production Dockerfile | 🔨 In progress |
| **M8** | Paper-trading validation on OANDA practice | 🔨 In progress |

**Recently completed**
- Live signal-generation loop (`LiveSignalService`) — event-driven evaluation on 5-minute candle closes, off the pricing-stream thread.
- Real-time DXY feed (`DxyFeedService`) — scheduled Dukascopy pull for an authentic multi-timeframe dollar bias, replacing the synthetic proxy.
- Full live-pipeline validation against the practice account: streaming, persistence, health/admin endpoints, and kill switch all verified end-to-end.

**Next up**
- Confirm the DXY seed lands and drives live signals end-to-end.
- CI pipeline updates and Grafana dashboards (remaining M7).
- Extended paper-trading run comparing live results against the backtest (M8).

---

## Testing

The project is built test-first with **240+ tests** across all modules, using JUnit 5 and AssertJ. Detectors are validated against fabricated candle sequences representing exact ICT scenarios; execution paths are validated through full lifecycle simulations.

```bash
# Full suite
./mvnw clean install

# A single module
./mvnw test -pl aletheia-strategy

# A single test class
./mvnw test -pl aletheia-strategy -Dtest=FairValueGapDetectorTest
```

---

## Known Limitations

- **DXY source latency** — the live dollar index is pulled from Dukascopy, which publishes with a lag, so the bias is a slightly delayed but structurally accurate higher-timeframe input. If an OANDA-native dollar index is available on your account, it can replace this feed for real-time bias (the feed is isolated behind `DxyFeedService` for exactly this reason).
- **Backtest DXY timeframe proxies** — short backtests approximate Monthly/Weekly/Daily structure using Daily/4-hour/1-hour candles due to limited history windows.
- **Practice only** — the system currently targets OANDA's practice environment. Live-money trading would require additional operational safeguards, monitoring, and a formal validation period.

---

## Disclaimer

Aletheia is a personal engineering project built to explore algorithmic trading, systems design, and test-driven development. It is **not financial advice** and is **not a guarantee of profit**. Trading foreign exchange carries substantial risk. Backtested performance does not predict future results. Use at your own risk.

---

*Built with Java, Spring Boot, and a great deal of TDD.*
