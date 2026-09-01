# aletheia-calendar

The economic-news guard — computes the ±15-minute high-impact-news blackout that gates Pillar 3 of the five-pillar signal system, and the data sources that feed it.

---

## Where this sits

```
aletheia-core
      ▲
aletheia-calendar   ── consumed by aletheia-backtest and aletheia-api
                        (NOT by aletheia-strategy — see below)
```

**Dependencies** (`pom.xml`): `aletheia-core`; `spring-boot-starter` (though the `@Service`/`@Scheduled` annotations that actually drive refresh live in `aletheia-api`'s `CalendarLoaderService`, not in this module); `postgresql` (declared, for persisting scraped events — not used directly by any class here); `jsoup` (Forex Factory HTML parsing); `okhttp3` (the calendar-service HTTP client); `jackson-databind` (JSON parsing).

Important: **`aletheia-strategy` does not depend on this module.** `SignalAggregator` only reads a pre-computed `boolean newsBlackout` off `MarketContext` — the actual `EconomicCalendarService.isNewsBlackout(...)` call is made one layer up, by `BacktestEngine` and `LiveSignalService`, both of which import this module directly.

---

## `CalendarDataSource` — the swappable-source interface

```java
interface CalendarDataSource {
    List<EconomicEvent> fetch(LocalDate from, LocalDate to) throws CalendarFetchException;
}
```

The rationale, straight from the javadoc: Forex Factory can block scraping (Cloudflare), so the actual source needs to be swappable without touching the consumer. Two implementations exist in this module (`HttpCalendarSource`, `CsvCalendarLoader`); the orchestration that chains them lives in `aletheia-api`'s `CalendarLoaderService` (see below), not here.

`CalendarFetchException` is a plain checked exception — its contract, honored throughout, is: callers catch it, log it, and keep operating on stale cached data rather than crash.

---

## `EconomicCalendarService` — the hot-path blackout engine

This is the actual guard consulted on every signal evaluation. Holds `private volatile List<EconomicEvent> events = List.of();` — `volatile` gives cross-thread visibility (a scheduler thread writes via `loadEvents()`, the trading thread reads via `isNewsBlackout()`) without needing a lock, since reads are just a scan over an immutable list. Deliberately pure in-memory: no DB, no network, safe to call thousands of times per second.

### `isNewsBlackout(Instant now, String instrument)` — exact logic

```java
Set<String> affected = getAffectedCurrencies(instrument);
return events.stream()
        .filter(EconomicEvent::isHighImpact)                 // MEDIUM/LOW never block
        .filter(event -> affected.contains(event.currency()))
        .anyMatch(event -> {
            Instant windowStart = event.scheduledTime().minus(15, MINUTES);
            Instant windowEnd   = event.scheduledTime().plus(15, MINUTES);
            return !now.isBefore(windowStart) && !now.isAfter(windowEnd);  // inclusive both ends
        });
```

### Currency mapping — `getAffectedCurrencies(instrument)`

```
EUR_USD                            -> {EUR, USD}   // EITHER currency's high-impact news blocks it
GBP_USD                            -> {GBP, USD}
XAU_USD                            -> {USD}
NAS100_USD, US30_USD, SPX500_USD   -> {USD}
default (unrecognized instrument)  -> {USD}
```

So `EUR_USD` is blocked by a USD event (NFP) **or** a EUR event (ECB rate decision) — either currency's high-impact news triggers the blackout. An instrument not explicitly listed still gets USD-news protection via the default branch, but nothing else.

`nextHighImpactEvent()` mirrors the same filter plus `scheduledTime().isAfter(now)`, relying on `loadEvents()` having pre-sorted the list. `cacheSize()` exposes `events.size()` — zero is the signal that the scraper/loader may have failed. `allEvents()` returns the raw list, used by `AdminController`'s `/admin/calendar` endpoint.

**Fail-open behavior worth knowing**: if `events` is empty (nothing ever loaded, or every source failed), `isNewsBlackout()` returns `false` — an empty calendar means no blackout is ever enforced. This is a deliberate consequence of never letting a refresh failure destroy previously-good cached data, but it does mean a persistently-failing calendar pipeline silently removes the news guard rather than blocking trades.

---

## `CsvCalendarLoader` — CSV fallback source

Implements `CalendarDataSource`. `fetch()` reads every line, skips the header, filters to `[from, to]` inclusive (bounds converted via `America/New_York`, with `to.plusDays(1)` as the exclusive upper edge). Format: `date,time,currency,impact,event`, split with `line.split(",", 5)` so the event-name field may itself contain commas.

- `date`: `yyyy-MM-dd`; `time`: `HH:mm`, both interpreted in **America/New_York**.
- `impact`: `HIGH`/`RED` → `HIGH`; `MEDIUM`/`MED`/`ORANGE` → `MEDIUM`; anything else (including `LOW`/`YELLOW`) → `LOW`.

Any line that fails to parse is skipped individually and logged (`[CsvCalendarLoader] Skipping line: ...`) — not fatal to the whole load, mirroring the HTML parser's row-level fault tolerance.

**⚠️ Schema gotcha**: the file `aletheia-api` actually defaults its fallback path to (`data/calendar_current.csv`) uses columns `scheduled_time_utc,name,currency,impact,source` — **a different schema than this loader expects**. Every row in that specific file will fail to parse and get silently skipped if the CSV fallback is ever actually exercised in production. The other CSVs under `data/` (`calendar_2023.csv`, `calendar_2024.csv`, etc.) use the correct `date,time,currency,impact,event` schema and are the ones the test fixtures and backtest runs actually rely on.

---

## `ForexFactoryHtmlParser` — HTML scraper (parsing only)

Pure parser — `parse(html, referenceDate)` — deliberately separated from HTTP fetching for testability and resilience to Cloudflare blocking. **Not currently wired into the live calendar fallback chain** (that's `HttpCalendarSource`/`CsvCalendarLoader`, orchestrated in `aletheia-api`); it exists as a standalone, independently tested component.

Expected structure: `table.calendar__table > tr.calendar__row > td.calendar__{date,time,currency,impact,event}`, selected via Jsoup CSS selectors. **Date inheritance**: Forex Factory only stamps the date on a day's first row; the parser tracks `currentDate` across rows. Rows with `"All Day"`/`"Tentative"`/blank time are skipped. `parseImpact` does a case-insensitive substring match on the impact cell's HTML (`"red"`/`"high"` → HIGH, `"orange"`/`"medium"` → MEDIUM, else LOW) — crude but resilient to exact class-name drift.

**Fragility, by design intent vs. reality**:
- Depends entirely on FF's current CSS class names — any markup redesign silently drops rows rather than erroring.
- The **year is never scraped from the page** — it always uses the caller-supplied `referenceDate.getYear()`, so a page spanning a Dec 31 → Jan 1 boundary would mis-year the January rows.
- Per-row parsing exceptions are caught, logged, and skipped — resilient, but silent (no alert that events were lost).

---

## `HttpCalendarSource` — the primary production source

Implements `CalendarDataSource`. Talks to a **separate, standalone "Aletheia Calendar Service"** (`calendar.service.url`, default in production `https://aletheia-calendar-service.onrender.com`) — not to Forex Factory directly. Per its javadoc, that external service itself ingests from official sources and Forex Factory and re-serves via `GET /calendar?from=&to=&impact=`; this class only ever requests `impact=HIGH`.

The HTTP call is injected as `Function<String,String>` for testability; production uses a default OkHttp client with a 60s call timeout. On any failure (non-2xx, exception), it logs and the underlying call returns `null` — but `fetch()` turns a `null` body into a **thrown** `CalendarFetchException`, never an empty list. This is the critical fail-safe design: an empty list would be indistinguishable from "genuinely no events scheduled," which would silently disable the news guard. A thrown exception forces the caller to explicitly fall back instead.

JSON parsing translates the service's `name` field into `EconomicEvent.eventName`; malformed elements are skipped individually, not fatal to the batch.

---

## The fallback chain (orchestrated in `aletheia-api`, not here)

`CalendarLoaderService` (in `aletheia-api`) wires `primarySource = HttpCalendarSource` + `fallbackSource = CsvCalendarLoader`, and on `load()`:

```
events = tryFetch("calendar service", primarySource, from, to)
if empty: events = tryFetch("CSV fallback", fallbackSource, from, to)
if still empty: WARN loudly, return without touching the cache (stale data survives)
else: calendarService.loadEvents(events)
```

`tryFetch` catches any exception, logs it, and returns an empty list — which is what drives the cascade from HTTP → CSV → the loud "News-blackout protection is NOT active" warning. See [`aletheia-api`](../aletheia-api/README.md#calendarloaderservice) for the full retry/scheduling detail (startup retries every 5 minutes up to 6 attempts, daily refresh at 06:00).

---

## Consumers

- **`aletheia-backtest`**'s `BacktestEngine` owns its own `EconomicCalendarService` instance (exposed via `calendarService()`), and `BacktestRunner` calls `engine.calendarService().loadEvents(...)` to seed historical events from a CSV before a run.
- **`aletheia-api`**'s `LiveSignalService` holds an injected `EconomicCalendarService` and calls `isNewsBlackout(now, instrument)` on every evaluation, feeding the result into `MarketContext`.
- **`aletheia-api`**'s `CalendarLoaderService` owns the fetch/fallback/retry orchestration described above.
