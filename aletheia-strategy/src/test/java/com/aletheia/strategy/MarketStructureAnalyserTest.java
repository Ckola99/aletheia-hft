package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for MarketStructureAnalyser.
 *
 * Each test constructs a candle sequence that produces a specific
 * market structure pattern and verifies the analyser classifies it correctly.
 *
 * Building realistic test data for market structure is more complex than
 * for FVGs because we need enough candles to produce multiple swing points,
 * and those swings need to form a recognisable pattern (HH/HL or LH/LL).
 */
class MarketStructureAnalyserTest {

	// Lookback=2 for simpler test data
	private final MarketStructureAnalyser analyser = new MarketStructureAnalyser(2);

	private long timeCounter = 1_000_000_000L;

	private Candle candle(long open, long high, long low, long close) {
		timeCounter += 900;
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"EUR_USD",
				Timeframe.MIN_15,
				open, high, low, close,
				100L);
	}

	/**
	 * Builds a bullish structure: price making higher highs and higher lows.
	 *
	 * The shape:
	 * low → high → low(higher) → high(higher) → ...
	 *
	 * We need at least 2 swing highs and 2 swing lows, each with
	 * 2 candles on each side (lookback=2). That requires a carefully
	 * constructed sequence.
	 */
	@Test
	void classifies_bullish_structure_with_hh_and_hl() {
		// Construct a clear uptrend pattern:
		// Swing Low 1 at 108_000, Swing High 1 at 108_400
		// Swing Low 2 at 108_200 (higher), Swing High 2 at 108_600 (higher)
		//
		List<Candle> candles = new ArrayList<>();

		// Phase 1: down to Swing Low 1 (108_000)
		candles.add(candle(108_300L, 108_350L, 108_250L, 108_280L));
		candles.add(candle(108_280L, 108_300L, 108_100L, 108_120L));
		candles.add(candle(108_120L, 108_150L, 108_000L, 108_050L)); // swing low=108000
		candles.add(candle(108_050L, 108_200L, 108_020L, 108_180L));
		candles.add(candle(108_180L, 108_300L, 108_150L, 108_280L));

		// Phase 2: up to Swing High 1 (108_400)
		candles.add(candle(108_280L, 108_400L, 108_260L, 108_380L)); // swing high=108400
		candles.add(candle(108_380L, 108_390L, 108_300L, 108_320L));
		candles.add(candle(108_320L, 108_350L, 108_280L, 108_300L));

		// Phase 3: pullback to Swing Low 2 (108_200 — higher than 108_000)
		candles.add(candle(108_300L, 108_310L, 108_200L, 108_220L)); // swing low=108200
		candles.add(candle(108_220L, 108_350L, 108_210L, 108_330L));
		candles.add(candle(108_330L, 108_450L, 108_310L, 108_430L));

		// Phase 4: up to Swing High 2 (108_600 — higher than 108_400)
		candles.add(candle(108_430L, 108_600L, 108_420L, 108_580L)); // swing high=108600
		candles.add(candle(108_580L, 108_590L, 108_500L, 108_520L));
		candles.add(candle(108_520L, 108_550L, 108_480L, 108_500L));

		MarketStructureAnalyser.StructureResult result = analyser.analyse(candles);

		System.out.println("Bias: " + result.bias());
		System.out.println("Reason: " + result.reason());
		System.out.println("Swings found: " + result.swings().size());

		assertThat(result.bias()).isEqualTo(MarketBias.BULLISH);
	}

	@Test
	void classifies_bearish_structure_with_lh_and_ll() {
		List<Candle> candles = new ArrayList<>();

		// Phase 1: up to Swing High 1 (108_600)
		candles.add(candle(108_300L, 108_350L, 108_280L, 108_340L));
		candles.add(candle(108_340L, 108_500L, 108_320L, 108_480L));
		candles.add(candle(108_480L, 108_600L, 108_460L, 108_580L)); // swing high=108600
		candles.add(candle(108_580L, 108_590L, 108_400L, 108_420L));
		candles.add(candle(108_420L, 108_450L, 108_350L, 108_370L));

		// Phase 2: down to Swing Low 1 (108_200)
		candles.add(candle(108_370L, 108_380L, 108_200L, 108_220L)); // swing low=108200
		candles.add(candle(108_220L, 108_300L, 108_210L, 108_280L));
		candles.add(candle(108_280L, 108_350L, 108_260L, 108_330L));

		// Phase 3: bounce to Swing High 2 (108_400 — lower than 108_600)
		candles.add(candle(108_330L, 108_400L, 108_310L, 108_380L)); // swing high=108400
		candles.add(candle(108_380L, 108_390L, 108_300L, 108_320L));
		candles.add(candle(108_320L, 108_330L, 108_250L, 108_270L));

		// Phase 4: down to Swing Low 2 (108_050 — lower than 108_200)
		// CRITICAL: candles AFTER the swing low must have HIGHER lows
		// so the swing detector confirms it as a swing low
		candles.add(candle(108_270L, 108_280L, 108_050L, 108_070L)); // swing low=108050
		candles.add(candle(108_070L, 108_150L, 108_060L, 108_130L)); // low=108060 > 108050 ✓
		candles.add(candle(108_130L, 108_200L, 108_100L, 108_180L)); // low=108100 > 108050 ✓

		MarketStructureAnalyser.StructureResult result = analyser.analyse(candles);

		System.out.println("Bias: " + result.bias());
		System.out.println("Reason: " + result.reason());
		System.out.println("Swings found: " + result.swings().size());
		result.swings().forEach(s -> System.out.println("  " + s.type() + " at " + s.price()));

		assertThat(result.bias()).isEqualTo(MarketBias.BEARISH);
	}

	@Test
	void classifies_neutral_when_structure_is_mixed() {
		// Higher highs but lower lows — expanding range, no clear trend
		List<Candle> candles = new ArrayList<>();

		// Swing Low 1 at 108_200
		candles.add(candle(108_350L, 108_400L, 108_300L, 108_320L));
		candles.add(candle(108_320L, 108_330L, 108_220L, 108_250L));
		candles.add(candle(108_250L, 108_260L, 108_200L, 108_230L)); // swing low=108200
		candles.add(candle(108_230L, 108_350L, 108_220L, 108_330L));
		candles.add(candle(108_330L, 108_400L, 108_310L, 108_380L));

		// Swing High 1 at 108_500
		candles.add(candle(108_380L, 108_500L, 108_370L, 108_480L)); // swing high=108500
		candles.add(candle(108_480L, 108_490L, 108_350L, 108_370L));
		candles.add(candle(108_370L, 108_380L, 108_250L, 108_270L));

		// Swing Low 2 at 108_100 (LOWER than 108_200 — bearish lows)
		candles.add(candle(108_270L, 108_280L, 108_100L, 108_120L)); // swing low=108100
		candles.add(candle(108_120L, 108_300L, 108_110L, 108_280L));
		candles.add(candle(108_280L, 108_450L, 108_270L, 108_430L));

		// Swing High 2 at 108_600 (HIGHER than 108_500 — bullish highs)
		candles.add(candle(108_430L, 108_600L, 108_420L, 108_580L)); // swing high=108600
		candles.add(candle(108_580L, 108_590L, 108_500L, 108_520L));
		candles.add(candle(108_520L, 108_550L, 108_480L, 108_510L));

		MarketStructureAnalyser.StructureResult result = analyser.analyse(candles);

		System.out.println("Bias: " + result.bias());
		System.out.println("Reason: " + result.reason());

		// HH but LL = mixed → NEUTRAL
		assertThat(result.bias()).isEqualTo(MarketBias.NEUTRAL);
	}

	@Test
	void returns_neutral_for_insufficient_data() {
		// Only 3 candles — not enough for any swing points
		List<Candle> candles = List.of(
				candle(108_100L, 108_200L, 108_050L, 108_180L),
				candle(108_180L, 108_300L, 108_170L, 108_280L),
				candle(108_280L, 108_400L, 108_270L, 108_380L));

		MarketStructureAnalyser.StructureResult result = analyser.analyse(candles);

		assertThat(result.bias()).isEqualTo(MarketBias.NEUTRAL);
		assertThat(result.reason()).contains("Insufficient");
	}

	@Test
	void result_contains_swing_points_for_downstream_use() {
		// Build enough candles for at least some swings
		List<Candle> candles = new ArrayList<>();

		candles.add(candle(108_300L, 108_350L, 108_250L, 108_280L));
		candles.add(candle(108_280L, 108_300L, 108_100L, 108_120L));
		candles.add(candle(108_120L, 108_150L, 108_000L, 108_050L));
		candles.add(candle(108_050L, 108_200L, 108_020L, 108_180L));
		candles.add(candle(108_180L, 108_300L, 108_150L, 108_280L));
		candles.add(candle(108_280L, 108_400L, 108_260L, 108_380L));
		candles.add(candle(108_380L, 108_390L, 108_300L, 108_320L));
		candles.add(candle(108_320L, 108_350L, 108_280L, 108_300L));
		candles.add(candle(108_300L, 108_310L, 108_200L, 108_220L));
		candles.add(candle(108_220L, 108_350L, 108_210L, 108_330L));
		candles.add(candle(108_330L, 108_450L, 108_310L, 108_430L));
		candles.add(candle(108_430L, 108_600L, 108_420L, 108_580L));
		candles.add(candle(108_580L, 108_590L, 108_500L, 108_520L));
		candles.add(candle(108_520L, 108_550L, 108_480L, 108_500L));

		MarketStructureAnalyser.StructureResult result = analyser.analyse(candles);

		// The result carries the swing points — SmtDivergenceDetector
		// and JudasSwingDetector will use these
		assertThat(result.swings()).isNotEmpty();
		System.out.println("Found " + result.swings().size() + " swing points");
	}
}
