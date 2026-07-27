package com.aletheia.backtest;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.core.Candle;
import com.aletheia.core.KillzoneWindow;
import com.aletheia.core.MarketBias;
import com.aletheia.core.Timeframe;
import com.aletheia.strategy.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Event-driven backtesting engine.
 *
 * DESIGN PRINCIPLE — The strategy doesn't know it's in a backtest.
 * The same SignalAggregator, the same detectors, the same logic.
 * The only difference: candles come from a historical list instead of
 * a live stream, and trades are simulated instead of sent to OANDA.
 *
 * HOW IT WORKS:
 * 1. Load HTF candles (MIN_15) for the instrument
 * 2. Load LTF candles (MIN_1 or SECONDS_5) for the instrument
 * 3. Optionally load USDX candles (Monthly/Weekly/Daily) for dynamic bias
 * 4. Walk forward through LTF candles one at a time
 * 5. At each step, build a MarketContext from the data seen SO FAR
 * 6. Call SignalAggregator.evaluate(context)
 * 7. If signal → open a simulated trade
 * 8. Check all open trades against current candle's high/low
 * 9. After all candles processed → calculate metrics
 *
 * LOOK-AHEAD BIAS PREVENTION:
 * At candle index i, the strategy only sees candles [0..i].
 * It never sees candle [i+1] or later.
 */
public class BacktestEngine {

	private final SignalAggregator aggregator;
	private final KillzoneService killzoneService;
	private final EconomicCalendarService calendarService;
	private final UsdxBiasEngine usdxBiasEngine;

	// Risk parameters
	private final double riskRewardRatio;
	private final long slBufferScaled;
	private final int maxOpenTrades;

	public BacktestEngine(double riskRewardRatio, long slBufferScaled, int maxOpenTrades) {
		this.aggregator = new SignalAggregator(
				new FairValueGapDetector(),
				new OrderBlockDetector(5, 1.5),
				new JudasSwingDetector(2, 5, 1.5));
		this.killzoneService = new KillzoneService();
		this.calendarService = new EconomicCalendarService();
		this.usdxBiasEngine = new UsdxBiasEngine(3);
		this.riskRewardRatio = riskRewardRatio;
		this.slBufferScaled = slBufferScaled;
		this.maxOpenTrades = maxOpenTrades;
	}

	public EconomicCalendarService calendarService() {
		return calendarService;
	}

	/**
	 * Runs the backtest with a PRECOMPUTED USDX bias.
	 * Used in tests where you want to control the bias directly.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			UsdxBias usdxBias) {
		return runInternal(instrument, htfCandles, ltfCandles,
				null, null, null, usdxBias);
	}

	/**
	 * Runs the backtest with DYNAMIC USDX bias computation.
	 * This is the real backtest — bias is recalculated as the
	 * simulation walks forward through time.
	 *
	 * @param instrument  which pair to backtest e.g. "EUR_USD"
	 * @param htfCandles  HTF candles (MIN_15) for PD array detection
	 * @param ltfCandles  LTF candles for Judas Swing detection
	 * @param usdxMonthly USDX Monthly candles for bias computation
	 * @param usdxWeekly  USDX Weekly candles for bias computation
	 * @param usdxDaily   USDX Daily candles for bias computation
	 * @return backtest result with all trades and metrics
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily) {
		return runInternal(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily, null);
	}

	private BacktestResult runInternal(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			UsdxBias fixedBias) {

		boolean dynamicBias = (fixedBias == null);

		List<SimulatedTrade> allTrades = new ArrayList<>();
		List<SimulatedTrade> openTrades = new ArrayList<>();
		int signalsGenerated = 0;
		int signalsRejected = 0;

		System.out.println("═══════════════════════════════════════════════════");
		System.out.println("  BACKTEST STARTING");
		System.out.println("  Instrument:    " + instrument);
		System.out.println("  HTF candles:   " + htfCandles.size());
		System.out.println("  LTF candles:   " + ltfCandles.size());
		System.out.println("  USDX bias:     " + (dynamicBias ? "DYNAMIC" : "FIXED " + fixedBias.direction()));
		System.out.println("  R:R target:    " + riskRewardRatio);
		System.out.println("  Max positions: " + maxOpenTrades);
		System.out.println("═══════════════════════════════════════════════════");

		int warmupPeriod = 30;

		for (int i = warmupPeriod; i < ltfCandles.size(); i++) {
			Candle currentCandle = ltfCandles.get(i);

			// Check open trades against this candle
			List<SimulatedTrade> toRemove = new ArrayList<>();
			for (SimulatedTrade trade : openTrades) {
				if (trade.checkExit(currentCandle.high(), currentCandle.low(),
						currentCandle.time())) {
					toRemove.add(trade);
				}
			}
			openTrades.removeAll(toRemove);

			if (openTrades.size() >= maxOpenTrades)
				continue;

			// HTF candles up to current time (no look-ahead)
			List<Candle> visibleHtf = htfCandles.stream()
					.filter(c -> !c.time().isAfter(currentCandle.time()))
					.toList();
			if (visibleHtf.size() < 10)
				continue;

			// LTF candles: last 50 up to current
			int ltfStart = Math.max(0, i - 50);
			List<Candle> visibleLtf = ltfCandles.subList(ltfStart, i + 1);

			// ── Compute USDX bias ────────────────────────────────────
			UsdxBias currentBias;
			if (dynamicBias) {
				// Filter each USDX timeframe to only include candles
				// that existed at this point in time (no look-ahead)
				Instant now = currentCandle.time();

				List<Candle> visibleMonthly = usdxMonthly.stream()
						.filter(c -> !c.time().isAfter(now)).toList();
				List<Candle> visibleWeekly = usdxWeekly.stream()
						.filter(c -> !c.time().isAfter(now)).toList();
				List<Candle> visibleDaily = usdxDaily.stream()
						.filter(c -> !c.time().isAfter(now)).toList();

				currentBias = usdxBiasEngine.compute(
						visibleMonthly, visibleWeekly, visibleDaily);
			} else {
				currentBias = fixedBias;
			}

			KillzoneWindow killzone = killzoneService.classify(currentCandle.time());
			boolean newsBlackout = calendarService.isNewsBlackout(
					currentCandle.time(), instrument);

			MarketContext ctx = new MarketContext(
					currentCandle.time(),
					instrument,
					killzone,
					currentBias,
					visibleHtf,
					visibleLtf,
					newsBlackout);

			Optional<TradeSignal> signal = aggregator.evaluate(ctx);

			if (signal.isEmpty()) {
				signalsRejected++;
				continue;
			}

			TradeSignal s = signal.get();
			signalsGenerated++;

			long sl, tp;
			if (s.bias() == MarketBias.BULLISH) {
				sl = s.sweepPrice() - slBufferScaled;
				long risk = s.idealEntry() - sl;
				tp = s.idealEntry() + (long) (risk * riskRewardRatio);
			} else {
				sl = s.sweepPrice() + slBufferScaled;
				long risk = sl - s.idealEntry();
				tp = s.idealEntry() - (long) (risk * riskRewardRatio);
			}

			SimulatedTrade trade = new SimulatedTrade(s, sl, tp);
			openTrades.add(trade);
			allTrades.add(trade);
		}

		// Force-close remaining open trades
		if (!ltfCandles.isEmpty()) {
			Candle lastCandle = ltfCandles.get(ltfCandles.size() - 1);
			for (SimulatedTrade trade : openTrades) {
				trade.checkExit(lastCandle.high(), lastCandle.low(), lastCandle.time());
			}
		}

		System.out.println("  Signals generated: " + signalsGenerated);
		System.out.println("  Contexts rejected: " + signalsRejected);
		System.out.println("  Total trades:      " + allTrades.size());

		return new BacktestResult(allTrades, signalsGenerated, signalsRejected);
	}
}
