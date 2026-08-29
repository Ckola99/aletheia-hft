package com.aletheia.backtest;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.PriceScale;
import com.aletheia.strategy.SignalGrade;
import com.aletheia.strategy.TradeSignal;

import java.time.Instant;

/**
 * A single simulated trade in the backtest, with partial-take-profit and
 * breakeven trade management that mirrors the live rules documented in
 * execution.ManagedOrder.
 *
 * TRADE MANAGEMENT MODEL (matches live ManagedOrder):
 *
 * TP1 at {@code tp1RiskMultiple}R (e.g. 2R):
 * - close {@code tp1CloseFraction} of the position (e.g. 70%)
 * - move the stop to breakeven (entry) on the remainder
 * TP2 at {@code tp2RiskMultiple}R (e.g. 3R):
 * - close the runner (the remaining 30%)
 * Stop loss:
 * - before TP1: full position exits at -1R
 * - after TP1: runner exits at breakeven (0 on the runner)
 *
 * FOUR OUTCOMES (with 70/30 at 2R/3R):
 * - Full loss : SL before TP1 -> -1.0R
 * - Breakeven scratch : TP1 hit, runner stopped at BE -> +1.4R
 * - Full win : TP1 hit, runner reaches TP2 -> +2.3R
 *
 * CONSERVATIVE AMBIGUITY RULE:
 * Within a single candle we only see high/low, not the path. If a candle's
 * range spans BOTH a profit target AND the active stop, we cannot know which
 * was touched first. We assume the WORSE outcome (stop first) so the backtest
 * never flatters itself. At 5m/15m timeframes this is rare, but it keeps the
 * numbers honest.
 *
 * P&L ACCOUNTING:
 * P&L is tracked as a fraction-weighted R multiple across the legs, then
 * converted to pips for reporting so results stay comparable to the old
 * single-TP runs. "Pips" here means: (weighted R) * (risk distance in pips).
 */
public class SimulatedTrade {

	private final String instrument;
	private final MarketBias direction;
	private final long entryPrice;
	private final long initialStop; // original protective stop
	private final long tp1; // first target (partial close)
	private final long tp2; // second target (runner)
	private final long riskDistance; // |entry - initialStop| in scaled units
	private final Instant entryTime;
	private final SignalGrade grade;
	private final KillzoneWindow killzone;

	private final double tp1CloseFraction; // e.g. 0.70

	// ── Mutable lifecycle state ────────────────────────────────────────
	private boolean tp1Hit = false; // has the partial closed?
	private long currentStop; // moves to breakeven after TP1
	private boolean closed = false; // fully closed?
	private Instant exitTime;
	private Long exitPrice; // representative exit (last leg) for logging

	// Realised R, weighted by the fraction of the position each leg closed.
	private double realisedR = 0.0;

	/**
	 * @param signal           the triggering signal (entry, bias, instrument)
	 * @param initialStop      protective stop (sweep +/- buffer), scaled long
	 * @param tp1              first target price (scaled long)
	 * @param tp2              second/runner target price (scaled long)
	 * @param tp1CloseFraction fraction closed at TP1 (e.g. 0.70)
	 */
	public SimulatedTrade(TradeSignal signal, long initialStop,
			long tp1, long tp2, double tp1CloseFraction) {
		this.instrument = signal.instrument();
		this.direction = signal.bias();
		this.entryPrice = signal.idealEntry();
		this.initialStop = initialStop;
		this.tp1 = tp1;
		this.tp2 = tp2;
		this.riskDistance = Math.abs(signal.idealEntry() - initialStop);
		this.entryTime = signal.generatedAt();
		this.grade = signal.grade();
		this.killzone = signal.killzone();
		this.tp1CloseFraction = tp1CloseFraction;
		this.currentStop = initialStop;
	}

	/**
	 * Advance the trade against one candle's high/low.
	 *
	 * Processes at most the events that could occur this candle, applying the
	 * conservative stop-first rule when a candle spans both a target and the
	 * active stop. Returns true when the trade becomes fully closed.
	 */
	public boolean checkExit(long high, long low, Instant time) {
		if (closed) {
			return false;
		}

		boolean bullish = (direction == MarketBias.BULLISH);

		// Determine what this candle touched, relative to the ACTIVE stop.
		boolean stopTouched = bullish ? (low <= currentStop) : (high >= currentStop);
		boolean tp1Touched = !tp1Hit && (bullish ? (high >= tp1) : (low <= tp1));
		boolean tp2Touched = tp1Hit && (bullish ? (high >= tp2) : (low <= tp2));

		// ── Phase 1: position still whole (TP1 not yet hit) ────────────
		if (!tp1Hit) {
			if (stopTouched && tp1Touched) {
				// Ambiguous: candle spans both TP1 and the stop.
				// Conservative rule -> assume stop hit first: full -1R loss.
				closeFully(currentStop, time, -1.0);
				return true;
			}
			if (stopTouched) {
				// Clean full stop before any partial: -1R on whole position.
				closeFully(currentStop, time, -1.0);
				return true;
			}
			if (tp1Touched) {
				// Partial close at TP1: bank the closed fraction at its R,
				// then move stop to breakeven for the runner.
				double tp1R = rMultipleAt(tp1); // e.g. +2.0
				realisedR += tp1CloseFraction * tp1R; // e.g. 0.70 * 2 = 1.4
				tp1Hit = true;
				currentStop = entryPrice; // breakeven
				// Do NOT return — the SAME candle might also reach TP2 or the
				// (new) breakeven stop. Fall through to phase 2 below.
			} else {
				// Nothing happened this candle.
				return false;
			}
		}

		// ── Phase 2: runner active (TP1 already hit, stop at breakeven) ─
		// Recompute touches against the runner's world for THIS candle.
		boolean runnerStopTouched = bullish ? (low <= currentStop) : (high >= currentStop);
		boolean runnerTp2Touched = bullish ? (high >= tp2) : (low <= tp2);

		if (runnerStopTouched && runnerTp2Touched) {
			// Ambiguous on the runner: assume breakeven stop first (0 on runner).
			double runnerFraction = 1.0 - tp1CloseFraction;
			realisedR += runnerFraction * 0.0; // breakeven, adds 0
			closeFully(currentStop, time, realisedR);
			return true;
		}
		if (runnerTp2Touched) {
			double runnerFraction = 1.0 - tp1CloseFraction;
			double tp2R = rMultipleAt(tp2); // e.g. +3.0
			realisedR += runnerFraction * tp2R; // e.g. 0.30 * 3 = 0.9
			closeFully(tp2, time, realisedR);
			return true;
		}
		if (runnerStopTouched) {
			// Runner stopped at breakeven: contributes 0. Trade done.
			closeFully(currentStop, time, realisedR);
			return true;
		}

		// Runner still open, TP1 already banked. Not fully closed yet.
		return false;
	}

	/**
	 * The signed R multiple achieved if the position closed at {@code price},
	 * relative to entry and the ORIGINAL risk distance.
	 */
	private double rMultipleAt(long price) {
		if (riskDistance == 0) {
			return 0.0;
		}
		long move = (direction == MarketBias.BULLISH)
				? (price - entryPrice)
				: (entryPrice - price);
		return (double) move / (double) riskDistance;
	}

	private void closeFully(long price, Instant time, double totalR) {
		this.closed = true;
		this.exitPrice = price;
		this.exitTime = time;
		this.realisedR = totalR;
	}

	// ── P&L / reporting ────────────────────────────────────────────────

	/**
	 * Total realised R for the whole trade (fraction-weighted across legs).
	 * e.g. full win = +2.3R, breakeven scratch = +1.4R, full loss = -1.0R.
	 */
	public double realisedR() {
		return realisedR;
	}

	/**
	 * P&L in pips: realised R multiplied by the risk distance expressed in pips.
	 * This keeps the reported unit ("pips") consistent with earlier single-TP
	 * runs while correctly accounting for the partial-close weighting.
	 */
	public double pnlPips() {
		if (!closed) {
			return 0.0;
		}
		long pip = PriceScale.onePip(instrument);
		double riskPips = (double) riskDistance / pip;
		return realisedR * riskPips;
	}

	/**
	 * P&L in scaled price units, reconstructed from realised R and risk.
	 * Provided for compatibility with any callers expecting a scaled figure.
	 */
	public long pnlScaled() {
		if (!closed) {
			return 0;
		}
		return Math.round(realisedR * riskDistance);
	}

	public double rewardRiskRatio() {
		return realisedR; // realised R IS the reward-to-risk achieved
	}

	// ── State queries ──────────────────────────────────────────────────

	public boolean isOpen() {
		return !closed;
	}

	public boolean isClosed() {
		return closed;
	}

	/** A win is any net-positive realised R (includes breakeven scratches). */
	public boolean isWin() {
		return closed && realisedR > 0.0;
	}

	public boolean isLoss() {
		return closed && realisedR < 0.0;
	}

	/** True if the partial at TP1 was reached (useful for stats). */
	public boolean reachedTp1() {
		return tp1Hit;
	}

	// ── Getters ────────────────────────────────────────────────────────

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
		return initialStop;
	}

	public long currentStop() {
		return currentStop;
	}

	public long tp1() {
		return tp1;
	}

	public long tp2() {
		return tp2;
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
