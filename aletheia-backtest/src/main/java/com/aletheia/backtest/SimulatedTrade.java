package com.aletheia.backtest;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.PriceScale;
import com.aletheia.strategy.SignalGrade;
import com.aletheia.strategy.TradeSignal;
import com.aletheia.core.KillzoneWindow;

import java.time.Instant;

/**
 * A single simulated trade in the backtest.
 *
 * Tracks the full lifecycle: entry → stop loss or take profit → exit.
 * Mutable because the trade updates as price moves — the exit
 * fields are null until the trade closes.
 *
 * After the backtest completes, all trades are collected into the
 * BacktestResult for performance analysis.
 */
public class SimulatedTrade {

	private final String instrument;
	private final MarketBias direction;
	private final long entryPrice;
	private final long stopLoss;
	private final long takeProfit;
	private final Instant entryTime;
	private final SignalGrade grade;
	private final KillzoneWindow killzone;

	// These are set when the trade exits
	private Long exitPrice;
	private Instant exitTime;
	private boolean stopped; // true if hit SL, false if hit TP

	public SimulatedTrade(TradeSignal signal, long stopLoss, long takeProfit) {
		this(signal, signal.idealEntry(), stopLoss, takeProfit);
	}

	/**
	 * @param entryPrice the actual booked fill price, which may differ from
	 *                   signal.idealEntry() once spread is applied to the fill
	 *                   (see BacktestEngine.effectiveEntry). Keeping this
	 *                   separate from idealEntry is what makes P&L match the
	 *                   SL/TP distances that were computed off the same
	 *                   spread-adjusted price.
	 */
	public SimulatedTrade(TradeSignal signal, long entryPrice, long stopLoss, long takeProfit) {
		this.instrument = signal.instrument();
		this.direction = signal.bias();
		this.entryPrice = entryPrice;
		this.stopLoss = stopLoss;
		this.takeProfit = takeProfit;
		this.entryTime = signal.generatedAt();
		this.grade = signal.grade();
		this.killzone = signal.killzone();
	}

	/**
	 * Checks if the given price triggers stop loss or take profit.
	 *
	 * For a BULLISH trade (long):
	 * - Price drops to or below stopLoss → stopped out (loss)
	 * - Price rises to or above takeProfit → target hit (win)
	 *
	 * For a BEARISH trade (short):
	 * - Price rises to or above stopLoss → stopped out (loss)
	 * - Price drops to or below takeProfit → target hit (win)
	 *
	 * @param high the candle's high price (scaled long)
	 * @param low  the candle's low price (scaled long)
	 * @param time when this candle closed
	 * @return true if the trade was closed by this candle
	 */

	public boolean checkExit(long high, long low, Instant time) {
		if (isOpen()) {
			if (direction == MarketBias.BULLISH) {
				// Long: SL hit if low touches or breaks below stopLoss
				if (low <= stopLoss) {
					close(stopLoss, time, true);
					return true;
				}
				// Long: TP hit if high touches or breaks above takeProfit
				if (high >= takeProfit) {
					close(takeProfit, time, false);
					return true;
				}
			} else {
				// Short: SL hit if high touches or breaks above stopLoss
				if (high >= stopLoss) {
					close(stopLoss, time, true);
					return true;
				}
				// Short: TP hit if low touches or breaks below takeProfit
				if (low <= takeProfit) {
					close(takeProfit, time, false);
					return true;
				}
			}
		}
		return false;
	}

	private void close(long price, Instant time, boolean wasStopped) {
		this.exitPrice = price;
		this.exitTime = time;
		this.stopped = wasStopped;
	}

	/**
	 * P&L in scaled price units (not pips, not dollars).
	 * For BULLISH: exit - entry (positive if price went up)
	 * For BEARISH: entry - exit (positive if price went down)
	 */
	public long pnlScaled() {
		if (!isClosed())
			return 0;
		if (direction == MarketBias.BULLISH) {
			return exitPrice - entryPrice;
		} else {
			return entryPrice - exitPrice;
		}
	}

	/**
	 * P&L in pips.
	 * One pip for EUR/USD = 10 scaled units (see PriceScale.onePip).
	 */
	public double pnlPips() {
		long pip = PriceScale.onePip(instrument);
		return (double) pnlScaled() / pip;
	}

	/**
	 * Risk in scaled units: distance from entry to stop loss.
	 */
	public long riskScaled() {
		return Math.abs(entryPrice - stopLoss);
	}

	/**
	 * Reward-to-Risk ratio achieved by this trade.
	 * Positive for winners, negative for losers.
	 */
	public double rewardRiskRatio() {
		long risk = riskScaled();
		if (risk == 0)
			return 0;
		return (double) pnlScaled() / risk;
	}

	public boolean isOpen() {
		return exitPrice == null;
	}

	public boolean isClosed() {
		return exitPrice != null;
	}

	public boolean isWin() {
		return isClosed() && pnlScaled() > 0;
	}

	public boolean isLoss() {
		return isClosed() && pnlScaled() < 0;
	}

	public boolean wasStopped() {
		return stopped;
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

	public long takeProfit() {
		return takeProfit;
	}

	public Instant entryTime() {
		return entryTime;
	}

	public SignalGrade grade() {
		return grade;
	}

	public Long exitPrice() {
		return exitPrice;
	}

	public Instant exitTime() {
		return exitTime;
	}

	public KillzoneWindow killzone() {
		return killzone;
	}
}
