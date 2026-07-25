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
 * Tests for UsdxBiasEngine.
 *
 * Each test builds candle sequences that produce known market structure
 * results (BULLISH, BEARISH, or NEUTRAL) on each timeframe, then verifies
 * the engine computes the correct consensus.
 */
class UsdxBiasEngineTest {

	// Use lookback=2 for manageable test data
	private final UsdxBiasEngine engine = new UsdxBiasEngine(2);

	private long timeCounter = 1_000_000_000L;

	private Candle candle(long open, long high, long low, long close, Timeframe tf) {
		timeCounter += 900;
		return new Candle(
				Instant.ofEpochSecond(timeCounter),
				"USDX",
				tf,
				open, high, low, close,
				100L);
	}

	/**
	 * Builds a candle sequence that produces BULLISH structure.
	 * Pattern: Swing Low 1 → Swing High 1 → Swing Low 2 (higher) → Swing High 2
	 * (higher)
	 */
	private List<Candle> bullishCandles(Timeframe tf) {
		List<Candle> candles = new ArrayList<>();
		// Down to Swing Low 1 (1000)
		candles.add(candle(1300L, 1350L, 1250L, 1280L, tf));
		candles.add(candle(1280L, 1300L, 1100L, 1120L, tf));
		candles.add(candle(1120L, 1150L, 1000L, 1050L, tf)); // swing low=1000
		candles.add(candle(1050L, 1200L, 1020L, 1180L, tf));
		candles.add(candle(1180L, 1300L, 1150L, 1280L, tf));
		// Up to Swing High 1 (1400)
		candles.add(candle(1280L, 1400L, 1260L, 1380L, tf)); // swing high=1400
		candles.add(candle(1380L, 1390L, 1300L, 1320L, tf));
		candles.add(candle(1320L, 1350L, 1280L, 1300L, tf));
		// Pullback to Swing Low 2 (1200 — higher than 1000)
		candles.add(candle(1300L, 1310L, 1200L, 1220L, tf)); // swing low=1200
		candles.add(candle(1220L, 1350L, 1210L, 1330L, tf));
		candles.add(candle(1330L, 1450L, 1310L, 1430L, tf));
		// Up to Swing High 2 (1600 — higher than 1400)
		candles.add(candle(1430L, 1600L, 1420L, 1580L, tf)); // swing high=1600
		candles.add(candle(1580L, 1590L, 1500L, 1520L, tf));
		candles.add(candle(1520L, 1550L, 1480L, 1500L, tf));
		return candles;
	}

	/**
	 * Builds a candle sequence that produces BEARISH structure.
	 * Pattern: Swing High 1 → Swing Low 1 → Swing High 2 (lower) → Swing Low 2
	 * (lower)
	 */
	private List<Candle> bearishCandles(Timeframe tf) {
		List<Candle> candles = new ArrayList<>();
		// Up to Swing High 1 (1600)
		candles.add(candle(1300L, 1350L, 1280L, 1340L, tf));
		candles.add(candle(1340L, 1500L, 1320L, 1480L, tf));
		candles.add(candle(1480L, 1600L, 1460L, 1580L, tf)); // swing high=1600
		candles.add(candle(1580L, 1590L, 1400L, 1420L, tf));
		candles.add(candle(1420L, 1450L, 1350L, 1370L, tf));
		// Down to Swing Low 1 (1200)
		candles.add(candle(1370L, 1380L, 1200L, 1220L, tf)); // swing low=1200
		candles.add(candle(1220L, 1300L, 1210L, 1280L, tf));
		candles.add(candle(1280L, 1350L, 1260L, 1330L, tf));
		// Bounce to Swing High 2 (1400 — lower than 1600)
		candles.add(candle(1330L, 1400L, 1310L, 1380L, tf)); // swing high=1400
		candles.add(candle(1380L, 1390L, 1300L, 1320L, tf));
		candles.add(candle(1320L, 1330L, 1250L, 1270L, tf));
		// Down to Swing Low 2 (1050 — lower than 1200)
		candles.add(candle(1270L, 1280L, 1050L, 1070L, tf)); // swing low=1050
		candles.add(candle(1070L, 1150L, 1060L, 1130L, tf));
		candles.add(candle(1130L, 1200L, 1100L, 1180L, tf));
		return candles;
	}

	/**
	 * Builds a candle sequence that produces NEUTRAL structure.
	 * Not enough candles for the analyser to find 2 highs and 2 lows.
	 */
	private List<Candle> neutralCandles(Timeframe tf) {
		// Just 5 flat candles — not enough swing points for a directional call
		List<Candle> candles = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			candles.add(candle(1300L, 1350L, 1250L, 1300L, tf));
		}
		return candles;
	}

	// ── CONSENSUS TESTS ─────────────────────────────────────────────

	@Test
	void high_confidence_when_all_three_agree_bullish() {
		UsdxBias bias = engine.compute(
				bullishCandles(Timeframe.MONTHLY),
				bullishCandles(Timeframe.WEEKLY),
				bullishCandles(Timeframe.DAILY));

		assertThat(bias.direction()).isEqualTo(MarketBias.BULLISH);
		assertThat(bias.confidence()).isEqualTo(ConfidenceLevel.HIGH);
		assertThat(bias.isTradeable()).isTrue();

		// USDX bullish → EUR/USD is bearish
		assertThat(bias.biasForPair("EUR_USD")).isEqualTo(MarketBias.BEARISH);
	}

	@Test
	void high_confidence_when_all_three_agree_bearish() {
		UsdxBias bias = engine.compute(
				bearishCandles(Timeframe.MONTHLY),
				bearishCandles(Timeframe.WEEKLY),
				bearishCandles(Timeframe.DAILY));

		assertThat(bias.direction()).isEqualTo(MarketBias.BEARISH);
		assertThat(bias.confidence()).isEqualTo(ConfidenceLevel.HIGH);

		// USDX bearish → EUR/USD is bullish
		assertThat(bias.biasForPair("EUR_USD")).isEqualTo(MarketBias.BULLISH);
	}

	@Test
	void medium_confidence_when_two_agree() {
		UsdxBias bias = engine.compute(
				bullishCandles(Timeframe.MONTHLY),
				bullishCandles(Timeframe.WEEKLY),
				bearishCandles(Timeframe.DAILY) // daily disagrees
		);

		assertThat(bias.direction()).isEqualTo(MarketBias.BULLISH);
		assertThat(bias.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
		assertThat(bias.isTradeable()).isTrue();
	}

	@Test
	void medium_confidence_when_weekly_and_daily_agree() {
		UsdxBias bias = engine.compute(
				neutralCandles(Timeframe.MONTHLY), // monthly is neutral
				bearishCandles(Timeframe.WEEKLY),
				bearishCandles(Timeframe.DAILY));

		assertThat(bias.direction()).isEqualTo(MarketBias.BEARISH);
		assertThat(bias.confidence()).isEqualTo(ConfidenceLevel.MEDIUM);
	}

	@Test
	void low_confidence_when_none_agree() {
		UsdxBias bias = engine.compute(
				bullishCandles(Timeframe.MONTHLY),
				bearishCandles(Timeframe.WEEKLY),
				neutralCandles(Timeframe.DAILY));

		assertThat(bias.direction()).isEqualTo(MarketBias.NEUTRAL);
		assertThat(bias.confidence()).isEqualTo(ConfidenceLevel.LOW);
		assertThat(bias.isTradeable()).isFalse();
	}

	@Test
	void neutral_direction_returns_neutral_for_all_pairs() {
		UsdxBias bias = engine.compute(
				neutralCandles(Timeframe.MONTHLY),
				neutralCandles(Timeframe.WEEKLY),
				neutralCandles(Timeframe.DAILY));

		assertThat(bias.biasForPair("EUR_USD")).isEqualTo(MarketBias.NEUTRAL);
		assertThat(bias.biasForPair("GBP_USD")).isEqualTo(MarketBias.NEUTRAL);
		assertThat(bias.biasForPair("XAU_USD")).isEqualTo(MarketBias.NEUTRAL);
	}

	@Test
	void result_contains_individual_timeframe_biases() {
		UsdxBias bias = engine.compute(
				bullishCandles(Timeframe.MONTHLY),
				bearishCandles(Timeframe.WEEKLY),
				bullishCandles(Timeframe.DAILY));

		// We can inspect what each timeframe said individually
		assertThat(bias.monthlyBias()).isEqualTo(MarketBias.BULLISH);
		assertThat(bias.weeklyBias()).isEqualTo(MarketBias.BEARISH);
		assertThat(bias.dailyBias()).isEqualTo(MarketBias.BULLISH);
	}
}
