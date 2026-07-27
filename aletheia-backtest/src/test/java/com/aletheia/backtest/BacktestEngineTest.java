package com.aletheia.backtest;

import com.aletheia.core.Candle;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import com.aletheia.strategy.ConfidenceLevel;
import com.aletheia.strategy.UsdxBias;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for BacktestEngine.
 *
 * Each test constructs a complete scenario with enough data for
 * the strategy to work and verifies the backtest produces the
 * expected results.
 */
class BacktestEngineTest {

	// R:R 2.0, 20-unit SL buffer, max 2 open trades
	private final BacktestEngine engine = new BacktestEngine(2.0, 20L, 2);

	/**
	 * Creates a time during London Open killzone.
	 * London Open: 02:00-05:00 EST
	 * In July, EST = EDT = UTC-4
	 * So 03:00 EDT = 07:00 UTC
	 */
	private Instant londonOpenTime(int minuteOffset) {
		ZonedDateTime base = ZonedDateTime.of(2023, 7, 10, 7, 0, 0, 0,
				ZoneId.of("UTC"));
		return base.plusMinutes(minuteOffset).toInstant();
	}

	private Candle htfCandle(Instant time, long open, long high, long low, long close) {
		return new Candle(time, "EUR_USD", Timeframe.MIN_15,
				open, high, low, close, 100L);
	}

	private Candle ltfCandle(Instant time, long open, long high, long low, long close) {
		return new Candle(time, "EUR_USD", Timeframe.SECONDS_5,
				open, high, low, close, 50L);
	}

	private UsdxBias bearishDollar() {
		return new UsdxBias(
				MarketBias.BEARISH, ConfidenceLevel.HIGH,
				MarketBias.BEARISH, MarketBias.BEARISH, MarketBias.BEARISH);
	}

	/**
	 * HTF candles with a bullish FVG.
	 * Need enough candles for the OB detector (ATR period).
	 */
	private List<Candle> buildHtfCandles() {
		List<Candle> candles = new ArrayList<>();
		// Normal candles establishing baseline
		for (int i = 0; i < 8; i++) {
			Instant time = londonOpenTime(-120 + i * 15); // starting 2 hours before
			candles.add(htfCandle(time, 108_100L, 108_200L, 108_050L, 108_150L));
		}
		// Bullish FVG: prev.high < next.low
		Instant fvgTime = londonOpenTime(-5);
		candles.add(htfCandle(fvgTime, 108_100L, 108_200L, 108_050L, 108_150L));
		candles.add(htfCandle(londonOpenTime(0), 108_160L, 108_500L, 108_150L, 108_480L));
		candles.add(htfCandle(londonOpenTime(15), 108_470L, 108_600L, 108_400L, 108_550L));
		return candles;
	}

	/**
	 * LTF candles during London Open with a bullish Judas Swing pattern.
	 */
	private List<Candle> buildLtfCandles() {
		List<Candle> candles = new ArrayList<>();
		int offset = 0;

		// Warmup candles (range ~100 each for ATR)
		for (int i = 0; i < 20; i++) {
			candles.add(ltfCandle(londonOpenTime(offset),
					108_200L, 108_280L, 108_180L, 108_250L));
			offset++;
		}

		// Swing low forms at 108_100
		candles.add(ltfCandle(londonOpenTime(offset++), 108_250L, 108_260L, 108_200L, 108_220L));
		candles.add(ltfCandle(londonOpenTime(offset++), 108_220L, 108_230L, 108_150L, 108_170L));
		candles.add(ltfCandle(londonOpenTime(offset++), 108_170L, 108_180L, 108_100L, 108_120L)); // swing low
		candles.add(ltfCandle(londonOpenTime(offset++), 108_120L, 108_250L, 108_110L, 108_230L));
		candles.add(ltfCandle(londonOpenTime(offset++), 108_230L, 108_350L, 108_200L, 108_320L));

		// Sweep below swing low
		candles.add(ltfCandle(londonOpenTime(offset++), 108_320L, 108_340L, 108_200L, 108_220L));
		candles.add(ltfCandle(londonOpenTime(offset++), 108_220L, 108_230L, 108_050L, 108_080L)); // sweep

		// Displacement (bullish)
		candles.add(ltfCandle(londonOpenTime(offset++), 108_090L, 108_360L, 108_080L, 108_350L));

		// FVG candle
		candles.add(ltfCandle(londonOpenTime(offset++), 108_340L, 108_420L, 108_340L, 108_400L));
		candles.add(ltfCandle(londonOpenTime(offset++), 108_400L, 108_450L, 108_380L, 108_430L));

		// Price continues up (should hit TP for a winning trade)
		for (int i = 0; i < 10; i++) {
			long base = 108_430L + i * 30;
			candles.add(ltfCandle(londonOpenTime(offset++),
					base, base + 50, base - 20, base + 40));
		}

		return candles;
	}

	@Test
	void runs_backtest_and_produces_result() {
		List<Candle> htf = buildHtfCandles();
		List<Candle> ltf = buildLtfCandles();
		UsdxBias bias = bearishDollar(); // bearish dollar → bullish EUR/USD

		BacktestResult result = engine.run("EUR_USD", htf, ltf, bias);

		// Should complete without errors
		assertThat(result).isNotNull();
		assertThat(result.trades()).isNotNull();

		System.out.println("Trades: " + result.trades().size());
		System.out.println("Signals generated: " + result.signalsGenerated());
		System.out.println("Signals rejected: " + result.signalsRejected());

		result.printReport();
	}

	@Test
	void simulated_trade_tracks_win_correctly() {
		// Manual trade test: enter long at 108_300, SL at 108_200, TP at 108_500
		com.aletheia.strategy.TradeSignal mockSignal = new com.aletheia.strategy.TradeSignal(
				MarketBias.BULLISH, "EUR_USD",
				new com.aletheia.strategy.FairValueGap(
						com.aletheia.strategy.PdArray.Bias.BULLISH,
						108_350L, 108_250L,
						Instant.now(), Timeframe.SECONDS_5),
				108_300L, 108_050L,
				com.aletheia.core.KillzoneWindow.LONDON_OPEN,
				com.aletheia.strategy.SignalGrade.A,
				bearishDollar(),
				new com.aletheia.strategy.JudasSwingSignal(
						MarketBias.BULLISH, "EUR_USD",
						new com.aletheia.strategy.FairValueGap(
								com.aletheia.strategy.PdArray.Bias.BULLISH,
								108_350L, 108_250L,
								Instant.now(), Timeframe.SECONDS_5),
						new com.aletheia.core.SwingPoint(
								Instant.now(), "EUR_USD",
								com.aletheia.core.SwingType.LOW, 108_100L,
								Timeframe.SECONDS_5),
						108_050L,
						com.aletheia.core.KillzoneWindow.LONDON_OPEN,
						com.aletheia.strategy.SignalGrade.A),
				java.util.Optional.empty(),
				Instant.now());

		SimulatedTrade trade = new SimulatedTrade(mockSignal, 108_200L, 108_500L);

		assertThat(trade.isOpen()).isTrue();

		// Price moves up but doesn't hit TP yet
		assertThat(trade.checkExit(108_450L, 108_280L, Instant.now())).isFalse();
		assertThat(trade.isOpen()).isTrue();

		// Price hits TP
		assertThat(trade.checkExit(108_520L, 108_400L, Instant.now())).isTrue();
		assertThat(trade.isClosed()).isTrue();
		assertThat(trade.isWin()).isTrue();
		assertThat(trade.pnlScaled()).isEqualTo(108_500L - 108_300L); // 200

		System.out.println("P&L pips: " + trade.pnlPips());
		System.out.println("R:R: " + trade.rewardRiskRatio());
	}

	@Test
	void simulated_trade_tracks_loss_correctly() {
		com.aletheia.strategy.TradeSignal mockSignal = new com.aletheia.strategy.TradeSignal(
				MarketBias.BULLISH, "EUR_USD",
				new com.aletheia.strategy.FairValueGap(
						com.aletheia.strategy.PdArray.Bias.BULLISH,
						108_350L, 108_250L,
						Instant.now(), Timeframe.SECONDS_5),
				108_300L, 108_050L,
				com.aletheia.core.KillzoneWindow.LONDON_OPEN,
				com.aletheia.strategy.SignalGrade.A,
				bearishDollar(),
				new com.aletheia.strategy.JudasSwingSignal(
						MarketBias.BULLISH, "EUR_USD",
						new com.aletheia.strategy.FairValueGap(
								com.aletheia.strategy.PdArray.Bias.BULLISH,
								108_350L, 108_250L,
								Instant.now(), Timeframe.SECONDS_5),
						new com.aletheia.core.SwingPoint(
								Instant.now(), "EUR_USD",
								com.aletheia.core.SwingType.LOW, 108_100L,
								Timeframe.SECONDS_5),
						108_050L,
						com.aletheia.core.KillzoneWindow.LONDON_OPEN,
						com.aletheia.strategy.SignalGrade.A),
				java.util.Optional.empty(),
				Instant.now());

		SimulatedTrade trade = new SimulatedTrade(mockSignal, 108_200L, 108_500L);

		// Price drops and hits SL
		assertThat(trade.checkExit(108_280L, 108_190L, Instant.now())).isTrue();
		assertThat(trade.isClosed()).isTrue();
		assertThat(trade.isLoss()).isTrue();
		assertThat(trade.wasStopped()).isTrue();
		assertThat(trade.pnlScaled()).isEqualTo(108_200L - 108_300L); // -100

		System.out.println("P&L pips: " + trade.pnlPips());
	}

	@Test
	void performance_metrics_calculate_correctly() {
		// Create a mix of wins and losses manually
		com.aletheia.strategy.TradeSignal mockSignal = new com.aletheia.strategy.TradeSignal(
				MarketBias.BULLISH, "EUR_USD",
				new com.aletheia.strategy.FairValueGap(
						com.aletheia.strategy.PdArray.Bias.BULLISH,
						108_350L, 108_250L,
						Instant.now(), Timeframe.SECONDS_5),
				108_300L, 108_050L,
				com.aletheia.core.KillzoneWindow.LONDON_OPEN,
				com.aletheia.strategy.SignalGrade.A,
				bearishDollar(),
				new com.aletheia.strategy.JudasSwingSignal(
						MarketBias.BULLISH, "EUR_USD",
						new com.aletheia.strategy.FairValueGap(
								com.aletheia.strategy.PdArray.Bias.BULLISH,
								108_350L, 108_250L,
								Instant.now(), Timeframe.SECONDS_5),
						new com.aletheia.core.SwingPoint(
								Instant.now(), "EUR_USD",
								com.aletheia.core.SwingType.LOW, 108_100L,
								Timeframe.SECONDS_5),
						108_050L,
						com.aletheia.core.KillzoneWindow.LONDON_OPEN,
						com.aletheia.strategy.SignalGrade.A),
				java.util.Optional.empty(),
				Instant.now());

		// Trade 1: Win — entry 108300, TP hit at 108500 → +200
		SimulatedTrade win = new SimulatedTrade(mockSignal, 108_200L, 108_500L);
		win.checkExit(108_520L, 108_280L, Instant.now());

		// Trade 2: Loss — entry 108300, SL hit at 108200 → -100
		SimulatedTrade loss = new SimulatedTrade(mockSignal, 108_200L, 108_500L);
		loss.checkExit(108_310L, 108_190L, Instant.now());

		// Trade 3: Win — entry 108300, TP hit at 108500 → +200
		SimulatedTrade win2 = new SimulatedTrade(mockSignal, 108_200L, 108_500L);
		win2.checkExit(108_550L, 108_250L, Instant.now());

		PerformanceMetrics metrics = new PerformanceMetrics(List.of(win, loss, win2));

		assertThat(metrics.totalTrades()).isEqualTo(3);
		assertThat(metrics.wins()).isEqualTo(2);
		assertThat(metrics.losses()).isEqualTo(1);
		assertThat(metrics.winRate()).isCloseTo(66.7, within(0.1));
		assertThat(metrics.netPnlPips()).isGreaterThan(0); // net positive
		assertThat(metrics.profitFactor()).isGreaterThan(1.0); // profitable

		metrics.printReport();
	}
}
