package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.SwingPoint;
import com.aletheia.core.Timeframe;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Detects SMT Divergence between two correlated instruments.
 *
 * SMT DIVERGENCE RULES:
 *
 * BULLISH (both instruments should be making lows together):
 * instrumentB (GBP/USD) makes a LOWER LOW → LL confirmed
 * instrumentA (EUR/USD) makes a HIGHER LOW → fails to follow
 * → Divergence! Smart money is defending EUR/USD.
 * → Trade EUR/USD LONG.
 *
 * BEARISH (both instruments should be making highs together):
 * instrumentB (GBP/USD) makes a HIGHER HIGH → HH confirmed
 * instrumentA (EUR/USD) makes a LOWER HIGH → fails to follow
 * → Divergence! Smart money is distributing EUR/USD.
 * → Trade EUR/USD SHORT.
 *
 * TEMPORAL ALIGNMENT:
 * The swing points being compared must have formed within the same
 * session — max 30 minutes apart. If EUR/USD's swing was 2 hours ago
 * and GBP/USD's swing is now, that's not a meaningful comparison.
 *
 * USAGE:
 * Optional<SmtDivergenceSignal> smt = detector.detect(
 * SmtPair.EUR_GBP,
 * Timeframe.MIN_15,
 * swingRegistry,
 * KillzoneWindow.LONDON_OPEN
 * );
 */
public class SmtDivergenceDetector {

	// Maximum time separation between two swings for them to be
	// considered "simultaneous" — 30 minutes
	private static final Duration MAX_SWING_SEPARATION = Duration.ofMinutes(30);

	/**
	 * Evaluates whether SMT Divergence exists between the two instruments
	 * in the given pair, at the given timeframe.
	 *
	 * @param pair      which instruments to compare
	 * @param timeframe which timeframe to check swings on
	 * @param registry  the swing point registry (holds recent swings for all
	 *                  instruments)
	 * @param killzone  current killzone (must be active)
	 * @return a signal if divergence is detected, empty otherwise
	 */
	public Optional<SmtDivergenceSignal> detect(
			SmtPair pair,
			Timeframe timeframe,
			SwingPointRegistry registry,
			KillzoneWindow killzone) {

		// Must be inside a killzone
		if (!killzone.isActive())
			return Optional.empty();

		// Get swings for both instruments
		List<SwingPoint> swingsA = registry.getSwings(pair.instrumentA(), timeframe);
		List<SwingPoint> swingsB = registry.getSwings(pair.instrumentB(), timeframe);

		// Try bullish SMT first, then bearish
		Optional<SmtDivergenceSignal> bullish = detectBullishSmt(
				pair, timeframe, swingsA, swingsB, killzone);
		if (bullish.isPresent())
			return bullish;

		return detectBearishSmt(pair, timeframe, swingsA, swingsB, killzone);
	}

	/**
	 * BULLISH SMT:
	 * instrumentB makes a Lower Low → instrumentA fails to follow (Higher Low)
	 *
	 * We compare the two most recent swing lows of each instrument.
	 */
	private Optional<SmtDivergenceSignal> detectBullishSmt(
			SmtPair pair, Timeframe timeframe,
			List<SwingPoint> swingsA, List<SwingPoint> swingsB,
			KillzoneWindow killzone) {

		// Get the two most recent swing LOWS from each instrument
		List<SwingPoint> lowsA = getMostRecentByType(swingsA, true, 2);
		List<SwingPoint> lowsB = getMostRecentByType(swingsB, true, 2);

		// Need at least 2 swing lows from each instrument to compare
		if (lowsA.size() < 2 || lowsB.size() < 2)
			return Optional.empty();

		// Most recent swing lows (index 0 = second most recent, index 1 = most recent)
		SwingPoint prevLowA = lowsA.get(0);
		SwingPoint lastLowA = lowsA.get(1);
		SwingPoint prevLowB = lowsB.get(0);
		SwingPoint lastLowB = lowsB.get(1);

		// Check temporal alignment: the most recent lows must be close in time
		if (!isTemporallyAligned(lastLowA, lastLowB))
			return Optional.empty();

		// instrumentB makes a Lower Low: lastLowB.price < prevLowB.price
		boolean bMakesLowerLow = lastLowB.price() < prevLowB.price();

		// instrumentA FAILS to make a Lower Low: lastLowA.price >= prevLowA.price
		boolean aFailsToFollow = lastLowA.price() >= prevLowA.price();

		if (bMakesLowerLow && aFailsToFollow) {
			return Optional.of(new SmtDivergenceSignal(
					SmtType.BULLISH,
					pair,
					timeframe,
					lastLowB, // the trap swing — GBP/USD's lower low
					lastLowA, // the confirming swing — EUR/USD held higher
					killzone));
		}

		return Optional.empty();
	}

	/**
	 * BEARISH SMT:
	 * instrumentB makes a Higher High → instrumentA fails to follow (Lower High)
	 */
	private Optional<SmtDivergenceSignal> detectBearishSmt(
			SmtPair pair, Timeframe timeframe,
			List<SwingPoint> swingsA, List<SwingPoint> swingsB,
			KillzoneWindow killzone) {

		List<SwingPoint> highsA = getMostRecentByType(swingsA, false, 2);
		List<SwingPoint> highsB = getMostRecentByType(swingsB, false, 2);

		if (highsA.size() < 2 || highsB.size() < 2)
			return Optional.empty();

		SwingPoint prevHighA = highsA.get(0);
		SwingPoint lastHighA = highsA.get(1);
		SwingPoint prevHighB = highsB.get(0);
		SwingPoint lastHighB = highsB.get(1);

		if (!isTemporallyAligned(lastHighA, lastHighB))
			return Optional.empty();

		// instrumentB makes a Higher High
		boolean bMakesHigherHigh = lastHighB.price() > prevHighB.price();

		// instrumentA FAILS to make a Higher High
		boolean aFailsToFollow = lastHighA.price() <= prevHighA.price();

		if (bMakesHigherHigh && aFailsToFollow) {
			return Optional.of(new SmtDivergenceSignal(
					SmtType.BEARISH,
					pair,
					timeframe,
					lastHighB, // the trap — GBP/USD's higher high
					lastHighA, // the confirmation — EUR/USD held lower
					killzone));
		}

		return Optional.empty();
	}

	/**
	 * Gets the N most recent swing points of a specific type (high or low).
	 *
	 * @param swings all swings for an instrument
	 * @param lows   true for swing lows, false for swing highs
	 * @param count  how many to return
	 * @return the most recent N swings of the requested type, sorted oldest first
	 */
	private List<SwingPoint> getMostRecentByType(List<SwingPoint> swings,
			boolean lows, int count) {
		return swings.stream()
				.filter(s -> lows ? s.isLow() : s.isHigh())
				.sorted(Comparator.comparing(SwingPoint::time))
				.toList()
				.reversed() // most recent first
				.stream()
				.limit(count) // take N most recent
				.sorted(Comparator.comparing(SwingPoint::time)) // back to chronological
				.toList();
	}

	/**
	 * Checks whether two swing points formed close enough in time
	 * to be considered "simultaneous" for SMT comparison.
	 *
	 * Max separation: 30 minutes. If EUR/USD's swing was hours ago
	 * and GBP/USD's is fresh, they are not related.
	 */
	private boolean isTemporallyAligned(SwingPoint a, SwingPoint b) {
		Duration separation = Duration.between(a.time(), b.time()).abs();
		return separation.compareTo(MAX_SWING_SEPARATION) <= 0;
	}
}
