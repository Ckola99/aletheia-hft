package com.aletheia.execution;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.strategy.SignalGrade;
import com.aletheia.strategy.TradeSignal;

import java.time.Instant;
import java.util.UUID;

/**
 * A trade managed by the OrderManager throughout its lifecycle.
 *
 * This tracks everything about a trade from signal generation to close:
 * - The original signal that triggered it
 * - Position sizing from RiskManager
 * - Current state (PENDING → FILLED → PARTIAL → CLOSED)
 * - OANDA order/trade IDs (assigned when OANDA confirms)
 * - P&L tracking
 *
 * PARTIAL TAKE PROFIT STRATEGY:
 * When TP1 is hit:
 * 1. Close 70% of the position (lock in profit)
 * 2. Move SL to breakeven (entry price — zero risk on remainder)
 * 3. Let the remaining 30% run to TP2 (or trail SL)
 *
 * This is a core ICT money management principle — secure profit early,
 * let winners run with zero risk.
 */
public class ManagedOrder {

	private final String id; // internal UUID
	private final String instrument;
	private final MarketBias direction;
	private final long entryPrice; // planned entry (scaled long)
	private final long stopLoss; // original SL
	private final long tp1; // first take profit (partial close)
	private final long tp2; // second take profit (runner)
	private final long totalUnits; // full position size from RiskManager
	private final SignalGrade grade;
	private final KillzoneWindow killzone;
	private final Instant signalTime;

	private OrderState state;
	private String oandaOrderId; // OANDA's order ID (set after placing)
	private String oandaTradeId; // OANDA's trade ID (set after fill)
	private long filledPrice; // actual fill price (may differ from entry)
	private Instant filledTime;
	private long remainingUnits; // decreases after partial close
	private long currentSl; // may move to breakeven after TP1
	private Instant closedTime;
	private long closedPrice;
	private double realisedPnl; // accumulated P&L from partial and full closes

	public ManagedOrder(TradeSignal signal, long stopLoss,
			long tp1, long tp2, long totalUnits) {
		this.id = UUID.randomUUID().toString();
		this.instrument = signal.instrument();
		this.direction = signal.bias();
		this.entryPrice = signal.idealEntry();
		this.stopLoss = stopLoss;
		this.tp1 = tp1;
		this.tp2 = tp2;
		this.totalUnits = totalUnits;
		this.grade = signal.grade();
		this.killzone = signal.killzone();
		this.signalTime = signal.generatedAt();

		this.state = OrderState.PENDING;
		this.remainingUnits = totalUnits;
		this.currentSl = stopLoss;
	}

	// ── State Transitions ───────────────────────────────────────────

	/**
	 * Called when OANDA confirms the order was filled.
	 */
	public void onFilled(String oandaTradeId, long filledPrice, Instant filledTime) {
		this.state = OrderState.FILLED;
		this.oandaTradeId = oandaTradeId;
		this.filledPrice = filledPrice;
		this.filledTime = filledTime;
	}

	/**
	 * Called when TP1 is hit — close 70% and move SL to breakeven.
	 *
	 * @param closePrice the price TP1 was hit at
	 * @param pnl        the realised P&L from closing 70%
	 */
	public void onPartialClose(long closePrice, double pnl) {
		this.state = OrderState.PARTIAL;
		long closedUnits = (long) (totalUnits * 0.70);
		this.remainingUnits = totalUnits - closedUnits;
		this.currentSl = filledPrice; // move SL to breakeven
		this.realisedPnl += pnl;
	}

	/**
	 * Called when the trade is fully closed (TP2, SL, or manual close).
	 */
	public void onFullClose(long closePrice, Instant closeTime, double pnl) {
		this.state = OrderState.CLOSED;
		this.closedPrice = closePrice;
		this.closedTime = closeTime;
		this.remainingUnits = 0;
		this.realisedPnl += pnl;
	}

	/**
	 * Called when the order is cancelled before filling.
	 */
	public void onCancelled() {
		this.state = OrderState.CANCELLED;
	}

	/**
	 * Called when OANDA rejects the order.
	 */
	public void onFailed(String reason) {
		this.state = OrderState.FAILED;
	}

	// ── Queries ─────────────────────────────────────────────────────

	public boolean isOpen() {
		return state == OrderState.FILLED || state == OrderState.PARTIAL;
	}

	public boolean isClosed() {
		return state == OrderState.CLOSED;
	}

	public boolean isPending() {
		return state == OrderState.PENDING;
	}

	public boolean isPartial() {
		return state == OrderState.PARTIAL;
	}

	/**
	 * The number of units to close when TP1 is reached (70%).
	 */
	public long tp1CloseUnits() {
		return (long) (totalUnits * 0.70);
	}

	/**
	 * The number of units remaining for the runner (30%).
	 */
	public long runnerUnits() {
		return totalUnits - tp1CloseUnits();
	}

	// ── Getters ─────────────────────────────────────────────────────

	public String id() {
		return id;
	}

	public String instrument() {
		return instrument;
	}

	public MarketBias direction() {
		return direction;
	}

	public long entryPrice() {
		return entryPrice;
	}

	public long stopLoss() {
		return stopLoss;
	}

	public long currentSl() {
		return currentSl;
	}

	public long tp1() {
		return tp1;
	}

	public long tp2() {
		return tp2;
	}

	public long totalUnits() {
		return totalUnits;
	}

	public long remainingUnits() {
		return remainingUnits;
	}

	public SignalGrade grade() {
		return grade;
	}

	public KillzoneWindow killzone() {
		return killzone;
	}

	public Instant signalTime() {
		return signalTime;
	}

	public OrderState state() {
		return state;
	}

	public String oandaOrderId() {
		return oandaOrderId;
	}

	public void setOandaOrderId(String id) {
		this.oandaOrderId = id;
	}

	public String oandaTradeId() {
		return oandaTradeId;
	}

	public long filledPrice() {
		return filledPrice;
	}

	public Instant filledTime() {
		return filledTime;
	}

	public double realisedPnl() {
		return realisedPnl;
	}

	public Instant closedTime() {
		return closedTime;
	}

	public long closedPrice() {
		return closedPrice;
	}
}
