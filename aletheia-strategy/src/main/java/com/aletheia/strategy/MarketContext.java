package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * All the market data the SignalAggregator needs to evaluate a setup.
 *
 * This is a snapshot of the current market state at one moment in time.
 * The aggregator reads from this — it never fetches data itself.
 * This makes it easy to test: construct a MarketContext with known
 * values and verify the aggregator's decision.
 *
 * In production, a service builds this context from live data every
 * time a new candle closes. In tests, you construct it directly.
 *
 * @param now          the current time
 * @param instrument   which pair we're evaluating e.g. "EUR_USD"
 * @param killzone     the current killzone window
 * @param usdxBias     the USDX directional bias analysis
 * @param htfCandles   recent HTF (15min) candles for PD array detection
 * @param ltfCandles   recent LTF (seconds) candles for Judas Swing detection
 * @param newsBlackout true if a high-impact event is within ±15 minutes
 * @param smtSignal    SMT Divergence signal if detected, empty otherwise.
 *                     When present, the signal grade upgrades from A to A+.
 */
public record MarketContext(
		Instant now,
		String instrument,
		KillzoneWindow killzone,
		UsdxBias usdxBias,
		List<Candle> htfCandles,
		List<Candle> ltfCandles,
		boolean newsBlackout,
		Optional<SmtDivergenceSignal> smtSignal) {

	/**
	 * Convenience constructor without SMT signal — for backwards compatibility
	 * with existing tests and code that doesn't use SMT yet.
	 */
	public MarketContext(Instant now, String instrument, KillzoneWindow killzone,
			UsdxBias usdxBias, List<Candle> htfCandles,
			List<Candle> ltfCandles, boolean newsBlackout) {
		this(now, instrument, killzone, usdxBias, htfCandles, ltfCandles,
				newsBlackout, Optional.empty());
	}
}
