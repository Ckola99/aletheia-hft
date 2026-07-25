package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;

import java.time.Instant;

/**
 * A fully validated trade signal — the output of SignalAggregator.
 *
 * This record only exists if ALL five pillars passed. If you have
 * a TradeSignal object, it is safe to trade. The aggregator has
 * already verified bias, killzone, news, PD array, and Judas Swing.
 *
 * The execution engine (Milestone 6) receives this and:
 * 1. Calculates position size via RiskManager
 * 2. Places a limit order at the idealEntry price
 * 3. Sets stop loss below the sweep price
 * 4. Sets TP1 and TP2 at opposing PD arrays
 *
 * @param bias        BULLISH (buy) or BEARISH (sell)
 * @param instrument  which pair e.g. "EUR_USD"
 * @param entryZone   the FVG to enter at
 * @param idealEntry  midpoint of the FVG — where to place the limit order
 * @param sweepPrice  where the Judas Swing swept to — stop loss goes beyond
 *                    this
 * @param killzone    which session this signal fired in
 * @param grade       A_PLUS (SMT confirmed) or A (standalone)
 * @param usdxBias    the full USDX analysis for logging/debugging
 * @param judasSignal the full Judas Swing signal for logging/debugging
 * @param generatedAt when this signal was generated
 */
public record TradeSignal(
		MarketBias bias,
		String instrument,
		FairValueGap entryZone,
		long idealEntry,
		long sweepPrice,
		KillzoneWindow killzone,
		SignalGrade grade,
		UsdxBias usdxBias,
		JudasSwingSignal judasSignal,
		Instant generatedAt) {
}
