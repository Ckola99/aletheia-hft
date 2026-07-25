package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.SwingPoint;

/**
 * A confirmed Judas Swing trade signal — the output of JudasSwingDetector.
 *
 * This record contains everything needed to place a trade:
 * - Which direction to trade (bias)
 * - Where to enter (the FVG zone)
 * - What was swept (the liquidity target)
 * - When it happened (killzone)
 * - How confident we are (grade)
 *
 * @param bias       BULLISH (buy) or BEARISH (sell)
 * @param instrument which pair to trade e.g. "EUR_USD"
 * @param entryZone  the FVG created by the displacement — entry area
 * @param sweptSwing the swing point that was swept (the liquidity target)
 * @param sweepPrice the exact price that went beyond the swing (the lowest
 *                   low for bullish, highest high for bearish)
 * @param killzone   which killzone the signal occurred in
 * @param grade      A_PLUS (with SMT) or A (standalone)
 */
public record JudasSwingSignal(
		MarketBias bias,
		String instrument,
		FairValueGap entryZone,
		SwingPoint sweptSwing,
		long sweepPrice,
		KillzoneWindow killzone,
		SignalGrade grade) {

	/**
	 * The ideal entry price — the midpoint of the FVG.
	 * A limit order placed here has the best fill probability
	 * while being inside the institutional zone.
	 */
	public long idealEntry() {
		return entryZone.midpoint();
	}
}
