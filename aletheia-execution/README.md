# aletheia-execution

Order lifecycle, position sizing, and risk controls — everything between "a `TradeSignal` was validated" and "a position is open, managed, and eventually closed," expressed broker-agnostically.

---

## Where this sits

```
aletheia-core
      ▲
aletheia-execution   ── consumed by aletheia-api (PositionMonitor drives this module's
                         state machine against live broker reconciliation)
```

**Dependencies**: `aletheia-core` and `aletheia-strategy` (for `TradeSignal`/`MarketBias`). This module knows nothing about OANDA specifically — `OandaOrderExecutor` is the one class that does, and everything else in the module is written against the `BrokerExecutor` interface.

---

## `BrokerExecutor` — the broker-agnostic seam

```java
interface BrokerExecutor {
    Optional<String> placeLimitOrder(ManagedOrder order);
    boolean closeTrade(String tradeId, long units);
    boolean closeTradeAll(String tradeId);
    boolean modifyStopLoss(String tradeId, long newSlPrice, String instrument);
    Optional<Double> getAccountBalance();
    boolean cancelOrder(String orderId);

    List<BrokerTrade> getOpenTrades();
    Optional<Long> getCurrentPrice(String instrument);
    Optional<Double> getRealizedPnl(String tradeId);
}
```

`OrderManager`, `KillSwitch`, and `OrderExpiryService` (this module) plus `LiveSignalService` and `PositionMonitor` (`aletheia-api`) all depend on this interface, never on a concrete broker. `OandaOrderExecutor` is today's implementation — the javadoc explicitly frames a future FIX-based `CTraderFixExecutor` as the reason this interface exists at all. All methods return a value rather than throwing, so callers can react gracefully (e.g. `KillSwitch` keeps closing other positions even if one close call fails).

The three read-only methods (`getOpenTrades`, `getCurrentPrice`, `getRealizedPnl`) exist specifically to support `PositionMonitor`'s reconciliation loop in `aletheia-api` — they were added alongside that feature and never mutate broker state.

```java
record BrokerTrade(String brokerTradeId, String clientId, String instrument,
                    long currentUnits, long openPrice)
```

`clientId` is the OANDA `clientExtensions.id` we set at order-placement time — it's how `PositionMonitor` matches a broker-reported open trade back to the `ManagedOrder` that created it, without guessing.

---

## `ManagedOrder` — the order/position state machine

Tracks one trade from signal generation through full close.

```
PENDING → FILLED → PARTIAL → CLOSED
              ↘ CANCELLED / FAILED
```

(`OrderState` is an enum in this module — `com.aletheia.execution.OrderState` — with six values: `PENDING`, `FILLED`, `PARTIAL`, `CLOSED`, `CANCELLED`, `FAILED`.)

Constructed from a `TradeSignal` + computed `stopLoss`/`tp1`/`tp2`/`totalUnits`. Key fields and their lifecycle:

| Field | Set by | Meaning |
|---|---|---|
| `entryPrice` | constructor | `= signal.idealEntry()` — the exact price sent to the broker as the limit order's price |
| `currentSl` | constructor, then `onPartialClose` | Starts equal to `stopLoss`; moves to `filledPrice` (breakeven) once TP1 is hit |
| `filledPrice` | `onFilled(...)` | The broker's actual fill price |
| `remainingUnits` | `onPartialClose`/`onFullClose` | Shrinks as the position is partially or fully closed |
| `realisedPnl` | `onPartialClose`/`onFullClose` | **Accumulates** across every close event on this order — see [P&L accounting](#pl-accounting-realised-pnl-is-cumulative) below |

`tp1CloseUnits()` = 70% of `totalUnits`; `runnerUnits()` = the remaining 30%. State transitions (`onFilled`, `onPartialClose`, `onFullClose`, `onCancelled`, `onFailed`) are called exclusively by `PositionMonitor` (`aletheia-api`) in response to what the broker actually reports — this class itself never polls anything.

---

## `OrderManager` — order creation, capacity, and dedup

`createOrder(TradeSignal signal, double accountBalance)`:

1. Rejects if `openPositionCount() >= maxOpenPositions` (only counts `FILLED`/`PARTIAL` orders — pending orders don't count against the cap).
2. Rejects if `isDuplicateSignal(signal)` — same setup (entry and sweep price both within 5 pips, generated within 30 minutes of the last signal) already traded recently. This is the safeguard against the exact bug class of "the same FVG + sweep firing on every candle."
3. Computes SL from `sweepPrice ± slBufferScaled`, then `tp1`/`tp2` at `tp1RiskMultiple`/`tp2RiskMultiple` × the entry-to-SL risk distance (defaults `2.0R`/`3.0R`).
4. Sizes the position via `RiskManager.calculatePositionSize(...)`.
5. Builds and stores a `ManagedOrder`, records the signal for the next duplicate check.

`openPositions()`, `pendingOrders()`, `allOrders()`, `findById(...)`, `findByOandaTradeId(...)` are the read APIs `PositionMonitor`, `AdminController`, and `KillSwitch` all use.

---

## `RiskManager` — fixed-percentage position sizing

```java
long calculatePositionSize(double accountBalance, long entryPrice, long stopLoss,
                            String instrument, MarketBias direction)
```

`riskAmount = accountBalance × riskPercentage` (default 1%, validated to be within `(0%, 10%]` at construction). `units = round(riskAmount / slDistancePrice)`, clamped to `[1_000, 10_000_000]` (0.01 to 100 standard lots). Worked example from the class javadoc: $10,000 balance, 1% risk = $100, 25-pip SL distance → 40,000 units, and `40,000 × 0.00250 = $100` checks out exactly. If the SL distance is 0, returns `0` (can't size an order with no risk distance).

---

## `KillSwitch` — the emergency stop

`activate(reason)`, idempotent (a second call while already active is a no-op returning `false`):

1. Cancels every pending order (`executor.cancelOrder(...)` for each, then `order.onCancelled()`).
2. Closes every open position (`executor.closeTradeAll(...)` for each, then `order.onFullClose(0, now, 0)`).
3. Logs a loud banner with reason and timestamp.

Once activated, **stays activated until the application restarts** — deliberately, so a human reviews what happened before trading resumes. `AdminController` exposes this via `POST /admin/kill-switch?reason=...`; `LiveSignalService` checks `killSwitch.isActive()` before evaluating a new candle; `MetricsUpdater` publishes its state as a gauge.

---

## `OrderExpiryService` — pending-order lifetime

`checkAndExpire(now)`, intended to run on a schedule (`aletheia-api`'s `TradingEngineRunner` calls it every 60 seconds by default):

A pending order expires if:
- We're now outside every killzone (`KillzoneWindow.NONE`), **or**
- We're in a *different* killzone than the one the order was placed in (a London-Open setup is not valid during NY Open), **or**
- The order is older than 3 hours regardless (a hardcoded safety net).

The rationale, from the class javadoc: an ICT setup relies on institutional activity specific to the killzone it formed in; outside that window price movement is "retail noise," and a stale limit order could otherwise fill hours later, in a different session, against a market structure that's since changed entirely. Expired orders are cancelled broker-side (if an ID was ever assigned) and marked `onCancelled()` locally.

`aletheia-backtest`'s `BacktestEngine` reimplements this exact same rule for its own resting-order simulation, specifically to keep backtest fill rates comparable to what live would actually achieve.

---

## `OandaOrderExecutor implements BrokerExecutor`

The OANDA-specific implementation. All calls go through a small internal HTTP helper (`executeGet`/`executePost`/`executePut`) built on OkHttp.

**Order placement** (`placeLimitOrder`) — `POST /accounts/{id}/orders`, `type: LIMIT`, `timeInForce: GTC`, with `clientExtensions.id` set to `order.id()` (this is the ID `PositionMonitor` later matches broker trades back against) and `takeProfitOnFill` set to **TP2** — the position's stop-loss and take-profit-on-fill are the *full-position* protective levels; TP1's partial-close is managed separately, live, by `PositionMonitor`, not by a second OANDA-side order.

**Circuit breaker**: after 3 failures within 60 seconds, `circuitOpen` becomes `true` and every subsequent call (order placement, closes, modifications) is blocked and logged, until 60 seconds pass with no new failures, at which point it auto-resets. A deliberately simple hand-rolled implementation rather than pulling in Resilience4j, "to keep dependencies minimal and the logic transparent" (the class's own comment — note `resilience4j-spring-boot3` *is* declared in the root `pom.xml`'s dependency management, but this class doesn't use it).

**Reconciliation reads**:
- `getOpenTrades()` → `GET /accounts/{id}/openTrades`, mapping each broker trade's `clientExtensions.id` back to `BrokerTrade.clientId` (missing extensions → `null`).
- `getCurrentPrice(instrument)` → `GET /accounts/{id}/pricing?instruments=...`, midpoint of `closeoutBid`/`closeoutAsk`.
- `getRealizedPnl(tradeId)` → `GET /accounts/{id}/trades/{tradeId}`, reading the `realizedPL` field.

All price/unit strings from OANDA are parsed defensively (`try/catch` around `Double.parseDouble`/`Long.parseLong`, returning `0` on failure rather than throwing) since a malformed field shouldn't crash reconciliation.

### P&L accounting: realised P&L is cumulative

This is the one subtlety worth internalizing before touching this code: **OANDA's `realizedPL` on a trade is cumulative** — it grows as the trade partially, then fully, closes. `ManagedOrder.realisedPnl` also **accumulates** whatever is fed into it via `onPartialClose`/`onFullClose`. So every caller that books P&L from OANDA (`PositionMonitor`, specifically) must compute the **increment** since it last booked:

```java
double increment = broker.getRealizedPnl(tradeId).get() - order.realisedPnl();
order.onPartialClose(closePrice, increment);   // or onFullClose
```

Feeding the *total* instead of the increment would double-count every subsequent close on the same trade. See [`aletheia-api`'s `PositionMonitor`](../aletheia-api/README.md#positionmonitor) for where this actually happens.

---

## Summary: the live trade-management flow this module enables

```
TradeSignal (from aletheia-strategy)
        │
        ▼
OrderManager.createOrder()  ── RiskManager sizes it, dedup/capacity checked
        │
        ▼
BrokerExecutor.placeLimitOrder()  ── OANDA LIMIT order, tagged with clientExtensions.id
        │
        ▼
ManagedOrder (PENDING)
        │
   [PositionMonitor, every 10s, in aletheia-api]
        │
        ├─ fills detected  → onFilled()             → FILLED
        ├─ TP1 price hit   → onPartialClose()        → PARTIAL (SL → breakeven)
        └─ broker-side close → onFullClose()          → CLOSED
```

Everything above the `PositionMonitor` line lives in this module and is fully testable without a broker or a scheduler; everything at and below it is `aletheia-api`'s job.
