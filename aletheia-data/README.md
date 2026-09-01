# aletheia-data

Market data ingestion, aggregation, and persistence — the layer that turns a live OANDA stream or a pile of historical Dukascopy ticks into the `Candle`s every other module works with.

---

## Where this sits

```
aletheia-core
      ▲
aletheia-data   ── consumed by aletheia-api (live) and aletheia-backtest (historical)
```

Depends only on `aletheia-core`. No other module in the reactor imports `com.aletheia.data` except `aletheia-api` and `aletheia-backtest` — this module sits directly above the domain model and directly below the two "runner" layers.

**Dependencies** (`pom.xml`): `aletheia-core`; `spring-boot-starter-jdbc` (explicitly chosen for `JdbcTemplate` + HikariCP pooling **without Hibernate/JPA** — hand-written SQL throughout); `postgresql` driver (TimescaleDB is Postgres underneath); `okhttp3` (the OANDA streaming connection); `jackson-databind` (OANDA JSON parsing); `commons-compress` + `org.tukaani:xz` (LZMA decompression of Dukascopy `.bi5` files).

---

## The sink abstraction: `TickListener` / `CandleListener`

Two one-method functional interfaces are the seams this whole module is built around:

```java
interface TickListener   { void onTick(Tick tick); }
interface CandleListener { void onCandleClosed(Candle candle); }
```

The same two interfaces are reused for **live streaming, historical backtest loading, and the DXY synthetic feed** — `CandleAggregator` implements `TickListener` and *is* a source of `CandleListener` events; `TickRepository` implements `TickListener` to persist; `CandleRepository` implements `CandleListener` to persist. Because `TickRepository` is routinely constructed with `jdbc == null` and subclassed to override `onTick`/`flush()`, it doubles as a generic "sink" — `aletheia-backtest`'s `BacktestRunner` and `aletheia-api`'s `DxyFeedService` both reuse `DukascopyHistoryLoader`'s download/decompress/rate-limit logic purely by redirecting its output into an in-memory `CandleAggregator` instead of a database, with zero changes to the loader itself.

`PricingStream` is the broker-agnostic live-feed interface (`addListener`, `start()`, `stop()`, `isRunning()`, `tickCount()`) — `OandaPricingStream` is today's only implementation, and the interface's own javadoc frames it explicitly as one member of a planned family (e.g. a FIX-based cTrader stream).

---

## Live ingestion: OANDA

**`OandaConfig`** — plain immutable value holder (`apiKey`, `accountId`, `streamUrl`), deliberately not a Spring component so it's trivially testable with fake values. `buildStreamUrl(instruments...)` builds `{streamUrl}/accounts/{accountId}/pricing/stream?instruments=A%2CB%2CC` per OANDA's v3 contract. `fromEnv()` reads `OANDA_API_KEY`/`OANDA_ACCOUNT_ID`/`OANDA_STREAM_URL`, defaulting to the **practice** stream host.

**`OandaTickParser`** — parses one line of OANDA's chunked-JSON stream. Only `"type":"PRICE"` messages produce a `Tick`; `"HEARTBEAT"` (sent ~every 5s) and anything unparseable return `Optional.empty()`. Reads `bids[0].price`/`asks[0].price`, converts to `double`, and immediately scales via `PriceScale.toScaled(...)` — this is the only place a raw OANDA price touches a `double`. Any exception is caught, logged, and treated as "no tick" — a bad line never crashes the stream.

**`OandaPricingStream implements PricingStream`** — not a websocket; OANDA's **chunked HTTP streaming** endpoint, read line-by-line via OkHttp with an infinite read timeout (the connection is meant to stay open indefinitely) and a 30s connect timeout.

- **Auth**: `Authorization: Bearer {apiKey}` + `Accept-Datetime-Format: RFC3339`.
- **Threading**: `start()` spawns one daemon thread (`"oanda-pricing-stream"`) and returns immediately.
- **Reconnect**: exponential backoff, 1s → doubling → capped at 60s, reset to 1s on any successful reconnect. `stop()`'s interrupt breaks the loop cleanly rather than retrying.
- **Fan-out**: each parsed tick calls every registered `TickListener`, each wrapped in its own try/catch so one bad listener can't take down the stream or the others. `TradingEngineConfig` registers both `CandleAggregator` and `TickRepository` directly on the same stream — every tick is aggregated *and* persisted in the same call, on the streaming thread.
- **State**: `listeners` is `CopyOnWriteArrayList` (safe to register from any thread mid-stream); `running` is `AtomicBoolean`.

---

## `CandleAggregator implements TickListener`

The heart of the module — turns a tick stream into OHLCV candles at multiple timeframes **simultaneously**, and is reused unmodified for both live and historical/backtest aggregation (`HistoricalCandleBuilder` and `DxyFeedService` both wrap it in-memory).

**Timeframes built** (hardcoded in `ACTIVE_TIMEFRAMES`, not configurable): `SECONDS_5, MIN_1, MIN_5, MIN_15, HOUR_1, HOUR_4, DAILY`. Note `Timeframe` also defines `SECONDS_15, SECONDS_30, MIN_30, WEEKLY, MONTHLY` — the aggregator simply doesn't build those.

**State**: `Map<CandleKey, OpenCandle>` (`ConcurrentHashMap`, keyed by `(instrument, timeframe)`), one open (mutable) candle per key at a time. `OpenCandle` is a private mutable builder — deliberately mutable to avoid a per-tick allocation, converted to an immutable `Candle` record only on close.

**Close decision** (`onTick`), for every active timeframe:
1. No open candle for this key → open one.
2. Same calendar period as the currently-open candle → `update()` in place (adjust high/low/close using `tick.mid()`, `volume++`).
3. A later period → close the *existing* candle (notify every `CandleListener`, each wrapped in try/catch), then open a fresh candle starting with the current tick.

**Important**: there's no timer. A candle only closes when a *later* tick arrives — an illiquid period leaves the candle open indefinitely until the next relevant tick.

**Period alignment** (`calculatePeriodStart`, unit-tested, calendar-aligned not arrival-aligned):
- `DAILY` → floors to midnight UTC.
- `HOUR_4` → floors to `00/04/08/12/16/20:00 UTC`.
- everything else → epoch-seconds floored to a multiple of `Timeframe.toSeconds()`.

`getOpenCandle(instrument, timeframe)` exposes the currently-forming candle as an immutable snapshot (or `null`), for "where is price right now within the open bar" use cases.

---

## Historical ingestion: Dukascopy

**`Bi5TickParser`** — parses **already-decompressed** `.bi5` binary tick data. Fixed 20-byte records, big-endian: `int32 msOffsetSinceHourStart, int32 askRawPips, int32 bidRawPips, float32 askVolumeLots, float32 bidVolumeLots` (volumes are read but discarded). Raw ints are interpreted via an instrument-specific `pointValue()` and converted through `PriceScale.toScaled(...)` — a no-op round-trip for most 5dp pairs, but necessary wherever Dukascopy's point value differs from Aletheia's own scale. Ticks with `askRaw==0 && bidRaw==0` are skipped as padding. Also carries `toOandaInstrument`/`toDukascopySymbol` translators (`EURUSD ↔ EUR_USD`, with `XAUUSD`/`DOLLARIDXUSD` special-cased).

**`DukascopyHistoryLoader`** — orchestrates day-by-day, hour-by-hour download → decompress → parse → persist.

- **URL**: `https://datafeed.dukascopy.com/datafeed/{SYMBOL}/{yyyy}/{MM-1}/{dd}/{HH}h_ticks.bi5` — **month is zero-indexed** (January = `00`), called out in comments as an easy bug source.
- **Fetch**: Java's built-in `HttpClient`, `User-Agent: Aletheia-HFT/1.0`. A 404 is treated as "no data this hour" (normal for weekends/no-liquidity) and returns `null`; any other non-200 throws.
- **Decompress**: `LZMACompressorInputStream` from Commons Compress, 4KB chunks.
- **Rate limiting**: a flat `Thread.sleep(100)` between every hourly request (not adaptive), specifically to avoid Dukascopy IP-blocking.
- **Iteration**: walks day-by-day; **weekends are skipped entirely**; per-hour failures are caught and counted (`filesFailed++`) without aborting the run.
- **No on-disk cache of its own** — every call re-downloads. (The disk-cache layer you see in `aletheia-backtest`/`DxyFeedService` is built *around* this loader by its callers, not inside it.)
- Pushes every parsed tick through `tickRepository.onTick(tick)` (the same `TickListener` path live ticks use), flushing once per day plus a final flush.

---

## Persistence

**`TickRepository implements TickListener`** — writes to the TimescaleDB `ticks` hypertable via `JdbcOperations`, no ORM: `INSERT INTO ticks (time, instrument, bid, ask) VALUES (?, ?, ?, ?)`.

- **Batched**: buffers in a `synchronized` in-memory list; once `buffer.size() >= batchSize` (constructor param, wired as `tick.batch-size`, default 100), one `jdbc.batchUpdate(...)` round-trip flushes and clears the buffer. Rationale in comments: at 20–50 ticks/sec, per-tick inserts would cost 100–250ms/sec in round trips; batching amortizes that.
- **Sink mode**: constructing with `jdbc == null` makes `flush()` a no-op — exploited by `BacktestRunner` and `DxyFeedService`, which subclass `TickRepository` purely to redirect `onTick` into an in-memory `CandleAggregator`.

**`CandleRepository implements CandleListener`** — writes to the `candles` hypertable, one synchronous `jdbc.update(...)` per closed candle (no batching — candle closes are infrequent compared to ticks). `timeframe` is stored as the enum's `.name()` string.

- `findRecent(instrument, timeframe, limit)` — most-recent-first query, reversed to chronological order before returning (Java 21 `SequencedCollection.reversed()`). This is what `LiveSignalService.warmup()` uses to backfill rolling buffers at startup.
- `findBetween(instrument, timeframe, from, to)` — inclusive range query, ascending order.

### Database schema (`sql/init/01_schema.sql`, applied automatically on first `docker compose up`)

```sql
ticks    (time, instrument, bid BIGINT, ask BIGINT)                          -- hypertable, no PK
candles  (time, instrument, timeframe, open/high/low/close BIGINT, volume)   -- hypertable
```

Both tables store prices as `BIGINT`, confirming the scaled-long discipline all the way down to the database — never `DOUBLE`/`NUMERIC` for a price. (The schema also defines `economic_events` and `trades` tables, owned conceptually by `aletheia-calendar` and `aletheia-execution` respectively, not this module.)

---

## Consumers

**`aletheia-api`**:
- `TradingEngineConfig` wires `OandaPricingStream → {CandleAggregator, TickRepository}` and `CandleAggregator → CandleRepository`, but does **not** call `.start()` — that's `TradingEngineRunner`'s job on application boot.
- `HealthController` reports `pricingStream.isRunning()`/`.tickCount()` for `/health`.
- `LiveSignalService` implements `CandleListener`, registers on `CandleAggregator`, and warms its rolling buffers from `CandleRepository.findRecent(...)`.
- `DxyFeedService` reuses `CandleAggregator` + `DukascopyHistoryLoader` + a null-DB `TickRepository` entirely offline to build the DXY candle feed.

**`aletheia-backtest`**:
- `HistoricalCandleBuilder` wraps `CandleAggregator` in-memory to turn a `List<Tick>` (or a streamed one-at-a-time feed) into candles per timeframe — explicitly documented as "the exact same `CandleAggregator` that live trading uses."
- `BacktestRunner` streams historical ticks straight into aggregation without ever holding the full tick list or persisting it, plus its own in-process cache keyed by `instrument|start|end`.

---

## Design notes worth knowing before you touch this module

1. **Doubles never survive past the boundary.** OANDA JSON parsing and Dukascopy pip conversion are the only two places a `double` price appears; both convert to a scaled `long` immediately via `PriceScale`.
2. **No ORM, by explicit choice** — hand-written SQL against TimescaleDB hypertables via Spring JDBC, documented in the pom.xml comments.
3. **Calendar-aligned candle boundaries**, not session-relative or first-tick-relative — a `MIN_15` candle always starts on a `:00/:15/:30/:45` boundary in UTC-epoch terms, regardless of when the first tick for that period actually arrived.
4. **`OandaPricingStream` is long-poll-style HTTP, not a websocket** — OkHttp with an infinite read timeout and manual exponential backoff, no external retry library.
5. **`DukascopyHistoryLoader` re-downloads on every call** — any caching is the caller's responsibility.
