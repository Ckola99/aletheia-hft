package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.Timeframe;

/**
 * A confirmed SMT Divergence signal.
 *
 * This is produced when two correlated instruments diverge at their
 * swing extremes — one makes a new high/low and the other doesn't follow.
 *
 * @param type            BULLISH or BEARISH
 * @param pair            which instrument pair was compared
 * @param timeframe       which timeframe the swings were detected on
 * @param trapSwing       the swing on instrumentB that made the false extreme
 * @param confirmingSwing the swing on instrumentA that FAILED to follow
 * @param killzone        which killzone this divergence occurred in
 */
public record SmtDivergenceSignal(
		SmtType type,
		SmtPair pair,
		Timeframe timeframe,
		SwingPoint trapSwing,
		SwingPoint confirmingSwing,
		KillzoneWindow killzone) {

	/**
	 * The instrument to trade — the one that held its level.
	 * For bullish SMT: EUR/USD held its low → trade EUR/USD long.
	 */
	public String instrumentToTrade() {
		return pair.instrumentA();
	}

	/**
	 * The instrument that created the false move.
	 * For bullish SMT: GBP/USD made the lower low (the trap).
	 */
	public String judasInstrument() {
		return pair.instrumentB();
	}
}
