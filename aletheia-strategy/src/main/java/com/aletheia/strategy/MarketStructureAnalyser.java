package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.MarketBias;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.SwingType;

import java.util.List;

/**
 * Analyses market structure to determine the directional bias.
 *
 * ICT MARKET STRUCTURE RULES:
 *
 * BULLISH: The last two swing highs form a Higher High (HH)
 * AND the last two swing lows form a Higher Low (HL).
 * → Only look for buy setups.
 *
 * BEARISH: The last two swing highs form a Lower High (LH)
 * AND the last two swing lows form a Lower Low (LL).
 * → Only look for sell setups.
 *
 * NEUTRAL: Anything else — mixed signals, not enough data,
 * or structure is transitioning.
 * → No trade. Wait for clarity.
 *
 * This analyser uses SwingPointDetector to find the swings,
 * then compares the most recent ones to classify the structure.
 *
 * USAGE:
 * MarketStructureAnalyser analyser = new MarketStructureAnalyser(5);
 * StructureResult result = analyser.analyse(candles);
 * if (result.bias() == MarketBias.BULLISH) {
 * // look for buy setups
 * }
 */
public class MarketStructureAnalyser {

	private final SwingPointDetector swingDetector;

	/**
	 * @param lookback swing point lookback — passed to SwingPointDetector
	 */
	public MarketStructureAnalyser(int lookback) {
		this.swingDetector = new SwingPointDetector(lookback);
	}

	/**
	 * Creates an analyser with default lookback of 5.
	 */
	public MarketStructureAnalyser() {
		this(5);
	}

	/**
	 * Analyses market structure from a sequence of candles.
	 *
	 * @param candles candles in chronological order (oldest first).
	 *                Needs enough candles to produce at least 2 swing highs
	 *                and 2 swing lows (typically 30+ candles).
	 * @return the structure analysis result
	 */
	public StructureResult analyse(List<Candle> candles) {
		// Step 1: Find all swing points
		List<SwingPoint> allSwings = swingDetector.detect(candles);

		// Step 2: Separate into highs and lows
		List<SwingPoint> highs = allSwings.stream()
				.filter(SwingPoint::isHigh)
				.toList();

		List<SwingPoint> lows = allSwings.stream()
				.filter(SwingPoint::isLow)
				.toList();

		// Need at least 2 highs and 2 lows to compare
		if (highs.size() < 2 || lows.size() < 2) {
			return new StructureResult(MarketBias.NEUTRAL, allSwings,
					"Insufficient swing points: " + highs.size() + " highs, "
							+ lows.size() + " lows");
		}

		// Step 3: Get the two most recent of each
		SwingPoint prevHigh = highs.get(highs.size() - 2);
		SwingPoint lastHigh = highs.get(highs.size() - 1);
		SwingPoint prevLow = lows.get(lows.size() - 2);
		SwingPoint lastLow = lows.get(lows.size() - 1);

		// Step 4: Compare to classify structure
		boolean higherHigh = lastHigh.price() > prevHigh.price();
		boolean higherLow = lastLow.price() > prevLow.price();
		boolean lowerHigh = lastHigh.price() < prevHigh.price();
		boolean lowerLow = lastLow.price() < prevLow.price();

		// Step 5: Determine bias
		if (higherHigh && higherLow) {
			return new StructureResult(MarketBias.BULLISH, allSwings,
					"HH: " + prevHigh.price() + " → " + lastHigh.price()
							+ ", HL: " + prevLow.price() + " → " + lastLow.price());
		}

		if (lowerHigh && lowerLow) {
			return new StructureResult(MarketBias.BEARISH, allSwings,
					"LH: " + prevHigh.price() + " → " + lastHigh.price()
							+ ", LL: " + prevLow.price() + " → " + lastLow.price());
		}

		// Mixed — one condition met but not both
		String reason;
		if (higherHigh && lowerLow) {
			reason = "Mixed: HH but LL (expanding range)";
		} else if (lowerHigh && higherLow) {
			reason = "Mixed: LH but HL (contracting range)";
		} else if (higherHigh) {
			reason = "Partial bullish: HH but equal lows";
		} else if (lowerLow) {
			reason = "Partial bearish: LL but equal highs";
		} else {
			reason = "Equal highs and lows — consolidation";
		}

		return new StructureResult(MarketBias.NEUTRAL, allSwings, reason);
	}

	/**
	 * The result of a market structure analysis.
	 *
	 * Contains the bias, the swing points that were detected (useful for
	 * other components like SmtDivergenceDetector), and a human-readable
	 * reason explaining why this bias was chosen (useful for logging and
	 * debugging — "why did the system say BEARISH here?").
	 *
	 * @param bias   BULLISH, BEARISH, or NEUTRAL
	 * @param swings all swing points detected (both highs and lows)
	 * @param reason human-readable explanation of the classification
	 */
	public record StructureResult(
			MarketBias bias,
			List<SwingPoint> swings,
			String reason) {
	}
}
