package com.aletheia.strategy;

import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for SignalAggregator.
 *
 * Strategy: build a "passing" context where all 5 pillars succeed,
 * then break one pillar at a time to verify each gate works independently.
 */
class SignalAggregatorTest {

	// Custom detector settings matching our test data sizes
	private final SignalAggregator aggregator = new SignalAggregator(
			new FairValueGapDetector(),
			new OrderBlockDetector(5, 1.5),
			new JudasSwingDetector(2, 5, 1.5));

	private long htfTimeCounter = 1_000_000_000L;
	private long ltfTimeCounter = 2_000_000_000L;

	private Candle htfCandle(long open, long high, long low, long close) {
		htfTimeCounter += 900; // 15 min
		return new Candle(Instant.ofEpochSecond(htfTimeCounter),
				"EUR_USD", Timeframe.MIN_15, open, high, low, close, 100L);
	}

	private Candle ltfCandle(long open, long high, long low, long close) {
		ltfTimeCounter += 5; // 5 seconds
		return new Candle(Instant.ofEpochSecond(ltfTimeCounter),
				"EUR_USD", Timeframe.SECONDS_5, open, high, low, close, 50L);
	}

	/**
	 * Builds HTF candles that contain a BULLISH FVG.
	 * The FVG detector needs 3 candles where prev.high < next.low.
	 * We also need enough candles for the OB detector (atrPeriod + 2 = 7).
	 */
	private List<Candle> htfCandlesWithBullishFvg() {
		List<Candle> candles = new ArrayList<>();
		// Normal candles for ATR baseline
		for (int i = 0; i < 6; i++) {
			candles.add(htfCandle(108_100L, 108_200L, 108_050L, 108_150L));
		}
		// Three candles forming a bullish FVG
		// prev.high=108200, next.low=108400 → gap from 108200 to 108400
		candles.add(htfCandle(108_100L, 108_200L, 108_050L, 108_150L)); // prev
		candles.add(htfCandle(108_160L, 108_500L, 108_150L, 108_480L)); // impulse
		candles.add(htfCandle(108_470L, 108_600L, 108_400L, 108_550L)); // next
		return candles;
	}

	/**
	 * Builds LTF candles containing a bullish Judas Swing pattern.
	 * Reuses the same pattern from JudasSwingDetectorTest.
	 */
	private List<Candle> ltfCandlesWithBullishJudas() {
		List<Candle> candles = new ArrayList<>();

		// Normal candles for ATR (range ~100 each)
		candles.add(ltfCandle(108_200L, 108_280L, 108_180L, 108_250L));
		candles.add(ltfCandle(108_250L, 108_320L, 108_220L, 108_300L));
		candles.add(ltfCandle(108_300L, 108_370L, 108_270L, 108_350L));
		candles.add(ltfCandle(108_350L, 108_420L, 108_310L, 108_400L));
		candles.add(ltfCandle(108_400L, 108_470L, 108_360L, 108_440L));

		// Swing low forms at 108_100
		candles.add(ltfCandle(108_440L, 108_450L, 108_300L, 108_320L));
		candles.add(ltfCandle(108_320L, 108_330L, 108_150L, 108_180L));
		candles.add(ltfCandle(108_180L, 108_200L, 108_100L, 108_130L)); // swing low
		candles.add(ltfCandle(108_130L, 108_250L, 108_120L, 108_230L));
		candles.add(ltfCandle(108_230L, 108_350L, 108_200L, 108_320L));

		// Sweep below swing low
		candles.add(ltfCandle(108_320L, 108_340L, 108_200L, 108_220L));
		candles.add(ltfCandle(108_220L, 108_230L, 108_050L, 108_080L)); // sweep

		// Displacement (bullish, body > 1.5× ATR)
		candles.add(ltfCandle(108_090L, 108_360L, 108_080L, 108_350L));

		// FVG candle (low > sweep candle high → gap exists)
		candles.add(ltfCandle(108_340L, 108_420L, 108_340L, 108_400L));

		candles.add(ltfCandle(108_400L, 108_450L, 108_380L, 108_430L));

		return candles;
	}

	/**
	 * Builds a USDX bias that says BULLISH dollar → BEARISH EUR/USD.
	 * Wait — we want to BUY EUR/USD, so we need BEARISH dollar.
	 */
	private UsdxBias bullishPairBias() {
		// USDX BEARISH → EUR/USD BULLISH (dollar weak = euro strong)
		return new UsdxBias(
				MarketBias.BEARISH, // USDX direction
				ConfidenceLevel.HIGH,
				MarketBias.BEARISH, // monthly
				MarketBias.BEARISH, // weekly
				MarketBias.BEARISH // daily
		);
	}

	/**
	 * Builds a full passing MarketContext — all 5 pillars should pass.
	 */
	private MarketContext passingContext() {
		return new MarketContext(
				Instant.now(),
				"EUR_USD",
				KillzoneWindow.LONDON_OPEN, // Pillar 2: active killzone
				bullishPairBias(), // Pillar 1: tradeable USDX bias
				htfCandlesWithBullishFvg(), // Pillar 4: HTF has a bullish FVG
				ltfCandlesWithBullishJudas(), // Pillar 5: LTF has Judas Swing
				false // Pillar 3: no news blackout
		);
	}

	// ── ALL PILLARS PASS ────────────────────────────────────────────

	@Test
	void generates_signal_when_all_pillars_pass() {
		MarketContext ctx = passingContext();

		Optional<TradeSignal> signal = aggregator.evaluate(ctx);

		assertThat(signal).isPresent();

		TradeSignal s = signal.get();
		assertThat(s.bias()).isEqualTo(MarketBias.BULLISH);
		assertThat(s.instrument()).isEqualTo("EUR_USD");
		assertThat(s.killzone()).isEqualTo(KillzoneWindow.LONDON_OPEN);
		assertThat(s.entryZone()).isNotNull();
		assertThat(s.idealEntry()).isGreaterThan(0);
		assertThat(s.grade()).isEqualTo(SignalGrade.A);
	}

	// ── PILLAR 1: USDX Bias ────────────────────────────────────────

	@Test
	void rejects_when_usdx_bias_is_neutral() {
		UsdxBias neutralBias = new UsdxBias(
				MarketBias.NEUTRAL, ConfidenceLevel.LOW,
				MarketBias.BULLISH, MarketBias.BEARISH, MarketBias.NEUTRAL);

		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				neutralBias,
				htfCandlesWithBullishFvg(),
				ltfCandlesWithBullishJudas(),
				false);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	@Test
	void rejects_when_usdx_confidence_is_low() {
		UsdxBias lowConfidence = new UsdxBias(
				MarketBias.BEARISH, ConfidenceLevel.LOW,
				MarketBias.BEARISH, MarketBias.BULLISH, MarketBias.NEUTRAL);

		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				lowConfidence,
				htfCandlesWithBullishFvg(),
				ltfCandlesWithBullishJudas(),
				false);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	// ── PILLAR 2: Killzone ──────────────────────────────────────────

	@Test
	void rejects_outside_killzone() {
		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.NONE, // outside all killzones
				bullishPairBias(),
				htfCandlesWithBullishFvg(),
				ltfCandlesWithBullishJudas(),
				false);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	// ── PILLAR 3: News Clearance ────────────────────────────────────

	@Test
	void rejects_during_news_blackout() {
		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				bullishPairBias(),
				htfCandlesWithBullishFvg(),
				ltfCandlesWithBullishJudas(),
				true // NEWS BLACKOUT ACTIVE
		);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	// ── PILLAR 4: HTF PD Array ──────────────────────────────────────

	@Test
	void rejects_when_no_htf_pd_array() {
		// HTF candles with NO FVG and NO Order Block
		// Just flat, boring candles — no institutional imbalance
		List<Candle> flatHtfCandles = new ArrayList<>();
		htfTimeCounter = 3_000_000_000L;
		for (int i = 0; i < 10; i++) {
			flatHtfCandles.add(htfCandle(108_200L, 108_220L, 108_180L, 108_200L));
		}

		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				bullishPairBias(),
				flatHtfCandles, // no PD arrays here
				ltfCandlesWithBullishJudas(),
				false);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	// ── PILLAR 5: Judas Swing ───────────────────────────────────────

	@Test
	void rejects_when_no_judas_swing_on_ltf() {
		// LTF candles with no sweep or displacement — just ranging
		List<Candle> flatLtfCandles = new ArrayList<>();
		ltfTimeCounter = 4_000_000_000L;
		for (int i = 0; i < 30; i++) {
			flatLtfCandles.add(ltfCandle(108_200L, 108_220L, 108_180L, 108_200L));
		}

		MarketContext ctx = new MarketContext(
				Instant.now(), "EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				bullishPairBias(),
				htfCandlesWithBullishFvg(),
				flatLtfCandles, // no Judas Swing here
				false);

		assertThat(aggregator.evaluate(ctx)).isEmpty();
	}

	// ── SIGNAL CONTENTS ─────────────────────────────────────────────

	@Test
	void signal_contains_full_context_for_execution() {
		MarketContext ctx = passingContext();

		TradeSignal s = aggregator.evaluate(ctx).get();

		// Everything the execution engine needs is present
		assertThat(s.usdxBias()).isNotNull();
		assertThat(s.judasSignal()).isNotNull();
		assertThat(s.generatedAt()).isNotNull();
		assertThat(s.sweepPrice()).isGreaterThan(0);

		// Entry zone should be a bullish FVG
		assertThat(s.entryZone().bias()).isEqualTo(PdArray.Bias.BULLISH);
		assertThat(s.entryZone().upper()).isGreaterThan(s.entryZone().lower());
	}

	// ── SMT INTEGRATION ─────────────────────────────────────────────

	@Test
	void signal_grade_is_A_without_smt() {
		MarketContext ctx = passingContext();

		TradeSignal s = aggregator.evaluate(ctx).get();

		// No SMT signal in context → grade should be A
		assertThat(s.grade()).isEqualTo(SignalGrade.A);
		assertThat(s.isSmtConfirmed()).isFalse();
		assertThat(s.smtSignal()).isEmpty();
	}

	@Test
	void signal_grade_upgrades_to_A_PLUS_with_smt() {
		// Create an SMT divergence signal
		SmtDivergenceSignal smt = new SmtDivergenceSignal(
				SmtType.BULLISH,
				SmtPair.EUR_GBP,
				com.aletheia.core.Timeframe.MIN_15,
				new com.aletheia.core.SwingPoint(
						Instant.now(), "GBP_USD",
						com.aletheia.core.SwingType.LOW, 133_050L,
						com.aletheia.core.Timeframe.MIN_15),
				new com.aletheia.core.SwingPoint(
						Instant.now(), "EUR_USD",
						com.aletheia.core.SwingType.LOW, 108_150L,
						com.aletheia.core.Timeframe.MIN_15),
				KillzoneWindow.LONDON_OPEN);

		// Build context WITH the SMT signal
		MarketContext ctx = new MarketContext(
				Instant.now(),
				"EUR_USD",
				KillzoneWindow.LONDON_OPEN,
				bullishPairBias(),
				htfCandlesWithBullishFvg(),
				ltfCandlesWithBullishJudas(),
				false,
				Optional.of(smt) // ← SMT present!
		);

		Optional<TradeSignal> signal = aggregator.evaluate(ctx);

		assertThat(signal).isPresent();

		TradeSignal s = signal.get();
		assertThat(s.grade()).isEqualTo(SignalGrade.A_PLUS);
		assertThat(s.isSmtConfirmed()).isTrue();
		assertThat(s.smtSignal()).isPresent();
		assertThat(s.smtSignal().get().type()).isEqualTo(SmtType.BULLISH);
	}
}
