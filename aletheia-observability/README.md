# aletheia-observability

A one-class module: the Micrometer/Prometheus metrics facade the rest of the engine reports through, so nothing else in the codebase needs to know it uses Micrometer specifically.

---

## Where this sits

```
aletheia-core
      ▲
aletheia-observability   ── consumed by aletheia-api (MetricsUpdater samples state
                             into it; LiveSignalService/PositionMonitor call it
                             directly at the moment something happens)
```

**Dependencies** (`pom.xml`): `aletheia-core`; `spring-boot-starter-actuator` (adds the `/actuator/health` and `/actuator/prometheus` endpoints automatically); `micrometer-registry-prometheus` (bridges Java metrics to the text format Prometheus scrapes).

---

## `MetricsService` — the only class here

```java
@Component
public class MetricsService {
    public MetricsService(MeterRegistry registry) { ... }
    // counters
    void recordSignal(String grade, String instrument);
    void recordOrderPlaced(String instrument, String direction);
    void recordNewsBlackout(String instrument);
    void recordCandle(String instrument, String timeframe);
    void recordTick(String instrument);
    // gauges
    void setOpenPositions(int n);
    void setKillSwitchActive(boolean active);
    void setStreamConnected(boolean connected);
    void setPnlPips(double pips);
    void setTickCount(long count);
}
```

### Why this class exists (dependency inversion, stated explicitly in its own javadoc)

The rest of the engine should not know or care that Aletheia happens to use Micrometer. It calls clean, domain-specific methods here, and *only this file* touches `MeterRegistry`. Swap Micrometer for something else later and only this file changes — the same discipline `BrokerExecutor` applies to hiding OANDA from the execution layer.

### How a metric reaches a dashboard

```
engine code → MetricsService → MeterRegistry (Micrometer)
           → /actuator/prometheus (text endpoint, auto-exposed by Actuator)
           → Prometheus scrapes every 15s
           → Grafana queries Prometheus
```

### Counters vs. gauges

- **Counters** (`recordSignal`, `recordOrderPlaced`, `recordNewsBlackout`, `recordCandle`, `recordTick`) are monotonic — they only go up. Each is built with `Counter.builder(name).tags(...).register(registry).increment()`; Micrometer de-duplicates meters by name+tags, so repeated calls with the same tag values return the *same* underlying counter rather than creating duplicates. Prometheus derives rates from these (e.g. "signals per hour").
- **Gauges** (`openPositions`, `killSwitchActive`, `streamConnected`, `lastPnlPips`, `tickCount`) are live snapshots — they go up and down. Implemented by binding a Micrometer gauge to an `AtomicInteger`/`AtomicLong` once, in the constructor (`registry.gauge("aletheia.open.positions", openPositions)`); Micrometer reads the atomic's current value on every scrape, so the rest of the code just mutates the atomic via the `set*` methods and never touches the registry again.

### Tags

Counters are tagged with dimensions like `{grade, instrument}` or `{instrument, direction}` so a single metric name can be sliced per-pair or per-grade in Grafana. `safe(String)` normalizes `null`/blank tag values to `"unknown"` — Micrometer requires non-null tag values, and this is the one guard that keeps a stray `null` from throwing at the metrics layer instead of just showing up as an odd label.

---

## Metric names published

| Name | Type | Tags |
|---|---|---|
| `aletheia.signals` | Counter | `grade`, `instrument` |
| `aletheia.orders.placed` | Counter | `instrument`, `direction` |
| `aletheia.news.blackouts` | Counter | `instrument` |
| `aletheia.candles` | Counter | `instrument`, `timeframe` |
| `aletheia.ticks` | Counter | `instrument` |
| `aletheia.open.positions` | Gauge | — |
| `aletheia.kill.switch.active` | Gauge | — (1/0) |
| `aletheia.stream.connected` | Gauge | — (1/0) |
| `aletheia.realized.pnl` | Gauge | — (USD, rounded) |
| `aletheia.ticks.received` | Gauge | — |

---

## Consumers

- **`aletheia-api`**'s `MetricsUpdater` samples `PricingStream.isRunning()`, `KillSwitch.isActive()`, `OrderManager.openPositionCount()`, `PricingStream.tickCount()`, and the sum of `realisedPnl()` across all orders every 5 seconds and pushes them into the gauges here.
- **`aletheia-api`**'s `LiveSignalService` calls `recordCandle`, `recordNewsBlackout`, `recordSignal`, and `recordOrderPlaced` directly at the moment each of those events happens, rather than waiting for a poll.

A pre-provisioned Grafana dashboard (`grafana/provisioning/dashboards/aletheia-dashboard.json`, at the repo root) visualizes these metrics; see the root README's [Observability](../README.md#observability) section for how to reach it.
