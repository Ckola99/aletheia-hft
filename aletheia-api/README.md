# aletheia-api

The Spring Boot application. This is the assembler: it wires every other module into a running live-trading pipeline, exposes REST admin/health endpoints, and hosts the scheduled tasks (order-expiry sweeps, calendar refresh, DXY feed refresh, metrics sampling, position reconciliation). **No trading logic lives here** — this module's job is wiring, scheduling, and translating between the domain modules and the outside world (HTTP, the clock).

---

## Where this sits

```
aletheia-core, aletheia-strategy, aletheia-data, aletheia-calendar,
aletheia-execution, aletheia-backtest, aletheia-observability
      ▲
aletheia-api   ── the only module that depends on all of the above
```

**Dependencies** (`pom.xml`): every sibling module, plus `spring-boot-starter-web`, `spring-boot-starter-jdbc`, `postgresql`, `okhttp3`. Build produces a fat/repackaged jar via `spring-boot-maven-plugin` (`java -jar` deployable).

---

## `AletheiaApplication` — entry point

```java
@SpringBootApplication(scanBasePackages = "com.aletheia")
@EnableScheduling
public class AletheiaApplication { public static void main(String[] args) { ... } }
```

The explicit `scanBasePackages` matters — without it, Spring would only scan `com.aletheia.api` and miss every `@Component`/`@Service` in the other modules (`LiveSignalService`, `PositionMonitor`, `MetricsService`, etc.). `@EnableScheduling` is what makes every `@Scheduled` method in the module actually fire.

---

## `TradingEngineConfig` — the root wiring

A `@Configuration` class defining the live dependency graph as `@Bean` methods. In wiring order:

| Bean | Constructed from |
|---|---|
| `OandaConfig` | `oanda.api-key`, `oanda.account-id`, `oanda.stream-url` |
| `CandleAggregator`, `TickRepository` (`tick.batch-size`), `CandleRepository` | — |
| `FairValueGapDetector`, `OrderBlockDetector` (`trading.ob-atr-period`=14, `trading.ob-displacement`=2.0), `JudasSwingDetector` (`trading.judas-lookback`=3, `trading.judas-atr-period`=20, `trading.judas-displacement`=2.5) | `@Value` |
| `SignalAggregator` | the three detectors above |
| `KillzoneService`, `EconomicCalendarService`, `UsdxBiasEngine(3)` (hardcoded), `SwingPointRegistry(3, 50)` (hardcoded), `SmtDivergenceDetector` | — |
| `RiskManager` (`trading.risk-percentage`=0.01) | — |
| `OrderManager` | riskManager, `trading.max-open-positions`=6, `trading.tp1-multiple`=2.0, `trading.tp2-multiple`=3.0, `trading.sl-buffer`=20 |
| `BrokerExecutor` (concretely `OandaOrderExecutor`) | oanda credentials + `oanda.base-url` |
| `KillSwitch`, `OrderExpiryService` | orderManager + executor (+ killzoneService for expiry) |
| `PricingStream` (concretely `OandaPricingStream`) | see below |

The `pricingStream()` bean is where the pipeline is wired **imperatively**, not just constructed. As of the current SMT-partner support, it also merges in stream-only instruments:

```java
Set<String> streamInstruments = trading.instruments ∪ trading.smt-partners;
OandaPricingStream stream = new OandaPricingStream(oandaConfig, streamInstruments);
stream.addListener(candleAggregator);       // ticks → candles
stream.addListener(tickRepository);         // ticks → persisted
candleAggregator.addCandleListener(candleRepository);  // closed candles → persisted
```

So an SMT-partner-only instrument like `NZD_USD` (streamed and candle-aggregated so `AUD_USD` can compute divergence against it) never gets its own trade evaluation — that gate is enforced later, in `LiveSignalService`, not here. The stream is wired but **not started** in this bean — `TradingEngineRunner.run()` calls `.start()` once the full Spring context is up.

`DxyFeedService`, `LiveSignalService`, `PositionMonitor`, `CalendarLoaderService`, `MetricsUpdater`, `AdminController`, `HealthController`, `TradingEngineRunner` are **not** `@Bean`s here — they're `@Component`/`@RestController`, picked up by component scan, with constructor injection resolving them against the beans defined above.

---

## `TradingEngineRunner` — boot + the order-expiry sweep

`@Component @Profile("!test")` (excluded under the `test` Spring profile, so tests never touch OANDA), implements `CommandLineRunner`.

- `run(...)` — Spring calls this once, after the entire context is built: `pricingStream.start()`. This is the actual "go live" trigger.
- `@Scheduled(fixedDelayString = "${trading.order-expiry-check-seconds:60}000")` — **every 60 seconds** by default. Guarded by `!killSwitch.isActive()`; delegates to `orderExpiryService.checkAndExpire(now)`.
- `@PreDestroy shutdown()` — on Ctrl+C/shutdown: stops the stream first (no more ticks), then flushes `tickRepository` (persists anything still buffered).

This class's only "loop" duties are starting the stream once and the 60-second expiry sweep — the actual signal-generation loop lives entirely in `LiveSignalService`, driven by candle-close events, not by anything scheduled here.

---

## `LiveSignalService` — the live signal-generation loop

`@Component implements CandleListener`. The live counterpart to `BacktestEngine`'s per-candle evaluation.

### Instrument roles (this is the part worth understanding before touching config)

- **Trade set** (`trading.instruments`) — instruments actually evaluated and traded.
- **SMT-partner-only set** (`trading.smt-partners`) — streamed and buffered *solely* so a traded instrument can compute SMT divergence against them; never evaluated for their own trades. Current default: `NZD_USD`, buffered only because `AUD_USD` needs it.
- **Explicit SMT pairings** (`trading.smt-pairs`, format `TRADED:PARTNER`, comma-separated) — e.g. `EUR_USD:GBP_USD,GBP_USD:EUR_USD,AUD_USD:NZD_USD`. A traded instrument with **no** entry here trades **A-grade only** (no SMT upgrade path) — `USD_JPY` in the current default config, since it has no correlated partner configured.

This replaced an earlier, simpler assumption ("the SMT partner is just the other configured traded instrument") — that assumption breaks once you trade more than two instruments, which is why the explicit mapping exists now.

### Buffers

Rolling in-memory `Deque<Candle>` per `"INSTRUMENT:TIMEFRAME"` key, capped per timeframe (`MIN_5`/`MIN_15`: 300, `HOUR_1`/`HOUR_4`: 400, `DAILY`: 500). `warmup()` (called from `@PostConstruct init()`) seeds every buffer from `CandleRepository.findRecent(...)`; on a fresh database this just seeds nothing and the service naturally won't trade until enough live candles accrue.

### Event flow (`onCandleClosed`, runs on the pricing-stream thread)

1. Every closed candle, any instrument/timeframe, increments a counter.
2. If the candle belongs to a tracked instrument (trade set + USDX source + SMT-partner-only set) and a tracked timeframe, append it to that buffer.
3. **Evaluation only triggers** if: the candle's timeframe is the configured LTF (default `MIN_5`) **and** the instrument is in the trade set **and** the kill switch is inactive. If all three hold, the real evaluation is dispatched onto a **separate single-threaded daemon executor** (`live-signal-eval`) — explicitly to avoid blocking tick/candle processing on the stream thread, and single-threaded so evaluations never overlap each other.

### `evaluate(instrument, now)` (runs on the `live-signal-eval` thread)

Mirrors the backtest loop step-for-step: classify killzone (bail early if inactive) → snapshot HTF/LTF buffers (require ≥10/≥30 candles) → build USDX bias → detect SMT against the *explicit* partner (if any) → check news blackout → build `MarketContext` → `signalAggregator.evaluate(ctx)`. On a signal: size off `executor.getAccountBalance()` (fallback `trading.default-balance`, default 100000), `orderManager.createOrder(...)`, then `executor.placeLimitOrder(...)` — **this class never calls the broker directly for order state**, only through `BrokerExecutor`, keeping it broker-agnostic. Metrics (`recordCandle`, `recordNewsBlackout`, `recordSignal`, `recordOrderPlaced`) are emitted inline at each of these points, not just sampled later.

### DXY bias — `buildUsdxBias(now)`

**Real Dukascopy-sourced DXY is the default, primary path today** (`trading.dxy.enabled=true` by default) — not a future upgrade, despite some of the class's own older comment blocks still framing it that way. If the feed is enabled but hasn't seeded data yet, the method returns `NEUTRAL` rather than falling back to the inferior synthetic proxy ("flat is safer than wrong"). Only when the feed is explicitly **disabled** does it fall back to inverting `trading.usdx-source` (default `EUR_USD`) candles across Month/Week/Day proxies.

---

## `DxyFeedService` — the live dollar-index feed

`@Component`, runs on its own daemon `ScheduledExecutorService` (never Spring's scheduler, so it can't block or be blocked by anything else) named `"dxy-feed"`.

### Warm-start disk cache (the newest addition to this class)

On `@PostConstruct init()`, before scheduling anything, `tryLoadCache()` attempts to load `trading.dxy.cache-file` (default `data/cache/dxy_feed.csv`) — a flat CSV of previously-aggregated `DAILY`/`HOUR_4`/`HOUR_1` candles. If the newest cached candle is younger than `lookbackDays` (90 default), the feed is considered "seeded" immediately from disk, and the first scheduled refresh only needs to fetch the **gap** between the cache and now (`loadedFromCache` flag, consumed exactly once) instead of re-pulling the full 90-day lookback. If there's no cache, or it's too stale, the service falls back to the original cold-seed behavior. A cache load failure of any kind (missing file, parse error) is caught and logged, leaving the service in the cold-seed state — the disk cache is purely additive and never a hard dependency.

Every successful `refresh()` re-writes the cache (`writeCache()`), so a restart after any period of successful operation gets a warm start.

### Refresh cadence

`scheduleWithFixedDelay(this::refresh, 0, refreshMinutes, MINUTES)` — first run at delay 0 (either the gap-catchup or the cold seed, per above), then every `trading.dxy.refresh-minutes` (default 120) thereafter, each time pulling only `trading.dxy.refresh-days` (default 5) and merging it over the tail (`merge()` — keeps existing candles strictly before the first refreshed candle's time, then appends everything refreshed; no duplicates).

### Mapping to the bias engine (identical to the backtest)

`DAILY → monthly proxy`, `HOUR_4 → weekly proxy`, `HOUR_1 → daily proxy`. `hasData()` = `seeded && !daily.isEmpty()` — the gate `LiveSignalService` checks before trusting the feed at all.

Publishing lag is an accepted, documented tradeoff: Dukascopy's most recent hour may be missing at any given moment, which is fine for a higher-timeframe *structural* bias but means this is never a sub-hour-fresh feed.

---

## `PositionMonitor` — live trade-management (Stage 4: reconcile + act + real P&L)

`@Component @Profile("!test")`, `@Scheduled(fixedDelay = 10_000)` — **every 10 seconds**, the live counterpart to `aletheia-backtest`'s `SimulatedTrade` logic, but driven by what the broker actually reports rather than a local price feed.

```java
void monitor() {
    List<BrokerTrade> brokerTrades = broker.getOpenTrades();
    Map<String, BrokerTrade> byClientId = ...;  // keyed by clientExtensions.id
    reconcileFills(byClientId);
    reconcileCloses(byClientId);
    manageTp1(byClientId);
}
```

1. **`reconcileFills`** — for every `PENDING` `ManagedOrder`, if a broker trade with a matching `clientId` now exists, call `order.onFilled(brokerTradeId, openPrice, now)`.
2. **`reconcileCloses`** — for every open (`FILLED`/`PARTIAL`) `ManagedOrder`, if it no longer appears in the broker's open-trades list (SL or TP2 hit broker-side), fetch OANDA's authoritative *cumulative* realised P&L and book only the **increment** since last booked (`total - order.realisedPnl()` — see [`aletheia-execution`](../aletheia-execution/README.md#p-l-accounting-realised-pnl-is-cumulative) for why this matters), then `order.onFullClose(...)`.
3. **`manageTp1`** — for every `FILLED` order (not yet partialled), fetch the current market price; if it's reached TP1, execute the partial: close 70% of the position first (`broker.closeTrade(...)`), *then* move the stop to breakeven (`broker.modifyStopLoss(...)`) — close-then-move-stop is deliberate ordering, so a failed partial-close never leaves the stop moved on an un-reduced position. If the stop move itself fails after a successful partial close, it's logged as a loud warning (the runner is now unprotected at breakeven) rather than retried automatically.

**Safety invariant**: only trades whose `clientId` matches a `ManagedOrder` this instance placed are ever touched — a manually-placed OANDA trade with no `clientExtensions` is invisible to this loop entirely.

---

## `CalendarLoaderService` — calendar wiring and the news-blackout warning

`@Component`. Constructs `HttpCalendarSource` (`calendar.service.url`) as primary and `CsvCalendarLoader` (`trading.calendar.csv-path`) as fallback, feeding the shared `EconomicCalendarService` both `LiveSignalService` and `AdminController` read from.

```
@PostConstruct init() → load() immediately
@Scheduled(fixedDelay=5min, initialDelay=5min) → retryUntilLoaded(), capped at 6 attempts (~30 min),
    only while cacheSize()==0 — handles a cold-starting calendar-service host
@Scheduled(cron = trading.calendar.refresh-cron, default "0 0 6 * * *") → scheduledRefresh(), daily
```

`load()`: fetch `[today-1, today+lookaheadDays]` (default 21 days) from the HTTP source; if empty, try the CSV fallback; if *that's* also empty, print the loud WARNING: economic calendar is EMPTY / News-blackout protection is NOT active banner and return without touching the cache — a failed refresh never clears previously-good cached events. See aletheia-calendar for the source-level detail, including the dual-schema CSV loader that auto-detects and parses both the calendar service's scheduled_time_utc,... format and the hand-authored date,time,... (EST-local) format.

Config keys present but not referenced by any @Value in this class: trading.calendar.url (the real primary-source key is calendar.service.url). trading.calendar.jblanked.* keys are also present in application.properties but not consumed here — if they're used at all, it's inside aletheia-calendar, not this wiring layer.
---

## `MetricsUpdater` — the metrics sampling loop

`@Component @Profile("!test")`, `@Scheduled(fixedDelay = 5_000)` — every 5 seconds, reads `pricingStream.isRunning()`, `killSwitch.isActive()`, `orderManager.openPositionCount()`, `pricingStream.tickCount()`, and the sum of `realisedPnl()` across `orderManager.allOrders()`, pushing each into `MetricsService`'s gauges. Its own javadoc explains the dependency direction deliberately: rather than making `aletheia-data`/`aletheia-execution` depend on `aletheia-observability`, this API-layer component reads state those modules already expose *publicly* and reports it outward — the core modules stay unaware they're being watched.

---

## `AdminController` / `HealthController` — REST surface

See the root README's [Admin & Monitoring Endpoints](../README.md#admin--monitoring-endpoints) table for the full endpoint list. Notable detail: `/admin/calendar` returns `cacheSize`, `nowUtc`, and every cached event formatted as `"<scheduledTime> | <currency> | <impact> | <eventName>"` — specifically useful for sanity-checking that the calendar's timestamps are aligned to the timezone you expect. `HealthController` is constructed against the `PricingStream` interface (not the concrete `OandaPricingStream`) — one more example of the broker/transport-agnostic wiring pattern this module is built around.

Separately, Spring Boot Actuator exposes `/actuator/health` and `/actuator/prometheus` (`management.endpoints.web.exposure.include=health,info,prometheus`) — a second, generic health/metrics surface alongside the app-specific `/health`.

---

## Full `@Scheduled` inventory

| Class | Method | Schedule |
|---|---|---|
| `TradingEngineRunner` | `checkOrderExpiry()` | every 60s (`trading.order-expiry-check-seconds`) |
| `CalendarLoaderService` | `retryUntilLoaded()` | every 5 min, capped at 6 attempts, only while cache is empty |
| `CalendarLoaderService` | `scheduledRefresh()` | daily, `trading.calendar.refresh-cron` (default 06:00) |
| `PositionMonitor` | `monitor()` | every 10s |
| `MetricsUpdater` | `sample()` | every 5s |
| `DxyFeedService` | `refresh()` | every `trading.dxy.refresh-minutes` (default 120 min) — **not** a Spring `@Scheduled`, its own daemon executor |

`LiveSignalService` has no scheduling at all — purely event-driven off `CandleAggregator.onCandleClosed`.

See the root README's [Configuration Reference](../README.md#configuration-reference) for the full `application.properties` key table.
