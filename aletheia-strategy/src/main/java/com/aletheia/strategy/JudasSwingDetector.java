package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;

import java.util.List;
import java.util.Optional;

/**
 * Detects Judas Swing entry setups on LTF candles.
 *
 * A Judas Swing is a three-part pattern:
 * 1. SWEEP — price moves AGAINST the HTF bias, taking out a prior swing point
 * 2. DISPLACEMENT — a strong candle reverses in the TRUE direction
 * 3. FVG — the displacement creates a Fair Value Gap = your entry zone
 *
 * PRE-CONDITIONS (checked before scanning):
 * - HTF bias must be directional (BULLISH or BEARISH, not NEUTRAL)
 * - Must be inside a Killzone (London Open or NY Open)
 * - Enough LTF candles to detect swings and patterns
 *
 * USAGE:
 * Optional<JudasSwingSignal> signal = detector.detect(
 * MarketBias.BULLISH, // HTF says "look for buys"
 * KillzoneWindow.LONDON_OPEN, // we're in London session
 * ltfCandles // recent seconds-chart candles
 * );
 *
 * signal.ifPresent(s -> {
 * // Place a limit buy at s.idealEntry() with SL below s.sweepPrice()
 * });
 */
public class JudasSwingDetector {

	private final SwingPointDetector swingDetector;
	private final FairValueGapDetector fvgDetector;
	private final int atrPeriod;
	private final double displacementMultiplier;

	/**
	 * @param swingLookback          lookback for swing detection on LTF
	 * @param atrPeriod              ATR period for displacement measurement
	 * @param displacementMultiplier how strong the displacement must be (× ATR)
	 */
	public JudasSwingDetector(int swingLookback, int atrPeriod,
			double displacementMultiplier) {
		this.swingDetector = new SwingPointDetector(swingLookback);
		this.fvgDetector = new FairValueGapDetector();
		this.atrPeriod = atrPeriod;
		this.displacementMultiplier = displacementMultiplier;
	}

	/**
	 * Creates a detector with default settings.
	 * Swing lookback=3 (LTF is noisy — smaller lookback),
	 * ATR period=20, displacement=1.5×
	 */
	public JudasSwingDetector() {
		this(3, 20, 1.5);
	}

	/**
	 * Scans LTF candles for a Judas Swing setup.
	 *
	 * @param htfBias  the directional bias from the higher timeframe
	 * @param killzone the current killzone window
	 * @param candles  LTF candles in chronological order (oldest first)
	 * @return a signal if a valid Judas Swing is found, empty otherwise
	 */
	public Optional<JudasSwingSignal> detect(MarketBias htfBias,
			KillzoneWindow killzone,
			List<Candle> candles) {

		// ── Pre-condition checks ────────────────────────────────────
		if (htfBias == MarketBias.NEUTRAL)
			return Optional.empty();
		if (!killzone.isActive())
			return Optional.empty();
		if (candles == null || candles.size() < atrPeriod + 10)
			return Optional.empty();

		// ── Step 1: Find swing points on the LTF ────────────────────
		List<SwingPoint> swings = swingDetector.detect(candles);

		if (htfBias == MarketBias.BULLISH) {
			return detectBullishJudas(candles, swings, killzone);
		} else {
			return detectBearishJudas(candles, swings, killzone);
		}
	}

	/**
	 * Detects a BULLISH Judas Swing:
	 * 1. Find prior swing lows (liquidity targets)
	 * 2. Check if a recent candle swept below a swing low
	 * 3. After the sweep, look for a bullish displacement
	 * 4. Check if that displacement created a bullish FVG
	 */
	private Optional<JudasSwingSignal> detectBullishJudas(
			List<Candle> candles,
			List<SwingPoint> swings,
			KillzoneWindow killzone) {

		// Get swing lows — these are the liquidity targets
		List<SwingPoint> swingLows = swings.stream()
				.filter(SwingPoint::isLow)
				.toList();

		if (swingLows.isEmpty())
			return Optional.empty();

		// Look at the most recent candles for a sweep pattern
		// We scan backwards from the end to find the most recent setup
		// Start from the end minus a small buffer (need candles after sweep for FVG)
		for (int i = candles.size() - 3; i >= atrPeriod; i--) {
			Candle sweepCandle = candles.get(i);

			// Does this candle's low go below any prior swing low?
			Optional<SwingPoint> sweptSwing = findSweptSwingLow(
					sweepCandle, swingLows);

			if (sweptSwing.isEmpty())
				continue;

			// ── SWEEP FOUND ─────────────────────────────────────────
			// Now look for displacement AFTER the sweep candle

			for (int j = i + 1; j < candles.size() - 1; j++) {
				Candle displacementCandle = candles.get(j);

				// Must be a bullish candle (the reversal)
				if (!displacementCandle.isBullish())
					continue;

				// Must be a strong move (displacement)
				long atr = AtrCalculator.calculate(candles, j - 1, atrPeriod);
				if (atr == 0)
					continue;

				long threshold = (long) (atr * displacementMultiplier);
				if (displacementCandle.bodySize() <= threshold)
					continue;

				// ── DISPLACEMENT FOUND ──────────────────────────────
				// Now check if this area has an FVG

				// Get a window of candles around the displacement for FVG detection
				int fvgStart = Math.max(0, i);
				int fvgEnd = Math.min(candles.size(), j + 3);
				List<Candle> fvgWindow = candles.subList(fvgStart, fvgEnd);

				List<FairValueGap> fvgs = fvgDetector.detect(fvgWindow);

				// Find a BULLISH FVG that formed at or after the displacement
				Optional<FairValueGap> entryFvg = fvgs.stream()
						.filter(f -> f.bias() == PdArray.Bias.BULLISH)
						.filter(f -> !f.time().isBefore(displacementCandle.time()))
						.findFirst();

				// If no bullish FVG near the displacement, check if the
				// displacement itself created one
				if (entryFvg.isEmpty()) {
					entryFvg = fvgs.stream()
							.filter(f -> f.bias() == PdArray.Bias.BULLISH)
							.findFirst();
				}

				if (entryFvg.isPresent()) {
					// ── COMPLETE JUDAS SWING FOUND ───────────────────
					return Optional.of(new JudasSwingSignal(
							MarketBias.BULLISH,
							sweepCandle.instrument(),
							entryFvg.get(),
							sweptSwing.get(),
							sweepCandle.low(), // the actual sweep price
							killzone,
							SignalGrade.A // A grade — SMT upgrades to A+ later
					));
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * Detects a BEARISH Judas Swing — mirror of bullish:
	 * 1. Find prior swing highs (buy-side liquidity targets)
	 * 2. Check if a recent candle swept above a swing high
	 * 3. After the sweep, look for a bearish displacement
	 * 4. Check if that displacement created a bearish FVG
	 */
	private Optional<JudasSwingSignal> detectBearishJudas(
			List<Candle> candles,
			List<SwingPoint> swings,
			KillzoneWindow killzone) {

		List<SwingPoint> swingHighs = swings.stream()
				.filter(SwingPoint::isHigh)
				.toList();

		if (swingHighs.isEmpty())
			return Optional.empty();

		for (int i = candles.size() - 3; i >= atrPeriod; i--) {
			Candle sweepCandle = candles.get(i);

			Optional<SwingPoint> sweptSwing = findSweptSwingHigh(
					sweepCandle, swingHighs);

			if (sweptSwing.isEmpty())
				continue;

			for (int j = i + 1; j < candles.size() - 1; j++) {
				Candle displacementCandle = candles.get(j);

				if (!displacementCandle.isBearish())
					continue;

				long atr = AtrCalculator.calculate(candles, j - 1, atrPeriod);
				if (atr == 0)
					continue;

				long threshold = (long) (atr * displacementMultiplier);
				if (displacementCandle.bodySize() <= threshold)
					continue;

				int fvgStart = Math.max(0, i);
				int fvgEnd = Math.min(candles.size(), j + 3);
				List<Candle> fvgWindow = candles.subList(fvgStart, fvgEnd);

				List<FairValueGap> fvgs = fvgDetector.detect(fvgWindow);

				Optional<FairValueGap> entryFvg = fvgs.stream()
						.filter(f -> f.bias() == PdArray.Bias.BEARISH)
						.filter(f -> !f.time().isBefore(displacementCandle.time()))
						.findFirst();

				if (entryFvg.isEmpty()) {
					entryFvg = fvgs.stream()
							.filter(f -> f.bias() == PdArray.Bias.BEARISH)
							.findFirst();
				}

				if (entryFvg.isPresent()) {
					return Optional.of(new JudasSwingSignal(
							MarketBias.BEARISH,
							sweepCandle.instrument(),
							entryFvg.get(),
							sweptSwing.get(),
							sweepCandle.high(),
							killzone,
							SignalGrade.A));
				}
			}
		}

		return Optional.empty();
	}

	/**
	 * Checks if the given candle swept below any prior swing low.
	 *
	 * "Swept" means the candle's LOW went below the swing low's price.
	 * The sweep takes out stop losses that were resting below the swing.
	 *
	 * We only consider swings that formed BEFORE this candle
	 * (you can't sweep a swing that doesn't exist yet).
	 */
	private Optional<SwingPoint> findSweptSwingLow(
			Candle sweepCandle, List<SwingPoint> swingLows) {

		return swingLows.stream()
				// Swing must have formed before the sweep candle
				.filter(sw -> sw.time().isBefore(sweepCandle.time()))
				// Sweep candle's low must be below the swing's price
				.filter(sw -> sweepCandle.low() < sw.price())
				// Take the most recent swing that was swept
				.reduce((first, second) -> second);
	}

	/**
	 * Checks if the given candle swept above any prior swing high.
	 * Mirror of findSweptSwingLow.
	 */
	private Optional<SwingPoint> findSweptSwingHigh(
			Candle sweepCandle, List<SwingPoint> swingHighs) {

		return swingHighs.stream()
				.filter(sw -> sw.time().isBefore(sweepCandle.time()))
				.filter(sw -> sweepCandle.high() > sw.price())
				.reduce((first, second) -> second);
	}
}
