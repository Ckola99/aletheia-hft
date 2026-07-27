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
 * Event-driven backtesting engine with spread simulation and SMT support.
 */
public class BacktestEngine {

	private final SignalAggregator aggregator;
	private final KillzoneService killzoneService;
	private final EconomicCalendarService calendarService;
	private final UsdxBiasEngine usdxBiasEngine;

	private final double riskRewardRatio;
	private final long slBufferScaled;
	private final int maxOpenTrades;
	private final long spreadScaled;

	/**
	 * @param riskRewardRatio target R:R (e.g. 3.0)
	 * @param slBufferScaled  extra buffer beyond sweep price for SL
	 * @param maxOpenTrades   max simultaneous positions
	 * @param spreadScaled    simulated spread in scaled units
	 *                        EUR/USD typical spread = 1.5 pips = 15 scaled units
	 *                        Use 0 in unit tests to test mechanics without spread
	 */
	public BacktestEngine(double riskRewardRatio, long slBufferScaled,
			int maxOpenTrades, long spreadScaled) {
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
		this.spreadScaled = spreadScaled;
	}

	public EconomicCalendarService calendarService() {
		return calendarService;
	}

	/**
	 * Run with PRECOMPUTED USDX bias, no SMT. Used in unit tests.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			UsdxBias usdxBias) {
		return runInternal(instrument, htfCandles, ltfCandles,
				null, null, null, usdxBias, Optional.empty());
	}

	/**
	 * Run with DYNAMIC USDX bias, no SMT.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily) {
		return runInternal(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily, null, Optional.empty());
	}

	/**
	 * Run with DYNAMIC USDX bias AND SMT divergence signal.
	 */
	public BacktestResult run(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			Optional<SmtDivergenceSignal> smtSignal) {
		return runInternal(instrument, htfCandles, ltfCandles,
				usdxMonthly, usdxWeekly, usdxDaily, null, smtSignal);
	}

	private BacktestResult runInternal(String instrument,
			List<Candle> htfCandles,
			List<Candle> ltfCandles,
			List<Candle> usdxMonthly,
			List<Candle> usdxWeekly,
			List<Candle> usdxDaily,
			UsdxBias fixedBias,
			Optional<SmtDivergenceSignal> smtSignal) {

		boolean dynamicBias = (fixedBias == null);
		Optional<SmtDivergenceSignal> smt = (smtSignal != null) ? smtSignal : Optional.empty();

		List<SimulatedTrade> allTrades = new ArrayList<>();
		List<SimulatedTrade> openTrades = new ArrayList<>();
		int signalsGenerated = 0;
		int signalsRejected = 0;

		System.out.println("=====================================================");
		System.out.println("  BACKTEST STARTING");
		System.out.println("  Instrument:    " + instrument);
		System.out.println("  HTF candles:   " + htfCandles.size());
		System.out.println("  LTF candles:   " + ltfCandles.size());
		System.out.println("  USDX bias:     " + (dynamicBias ? "DYNAMIC" : "FIXED " + fixedBias.direction()));
		System.out.println("  R:R target:    " + riskRewardRatio);
		System.out.println("  Spread:        " + spreadScaled + " scaled units ("
				+ (spreadScaled / 10.0) + " pips)");
		System.out.println("  SMT signal:    " + (smt.isPresent() ? smt.get().type() : "none"));
		System.out.println("  Max positions: " + maxOpenTrades);
		System.out.println("=====================================================");

		int warmupPeriod = 30;
		int cooldownBars = 0; // bars remaining before next trade allowed
		int cooldownPeriod = 30; // wait 30 LTF candles after opening a trade

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

			// Cooldown: skip signal evaluation after recently opening a trade
			if (cooldownBars > 0) {
				cooldownBars--;
				continue;
			}

			// HTF candles up to current time (no look-ahead)
			List<Candle> visibleHtf = htfCandles.stream()
					.filter(c -> !c.time().isAfter(currentCandle.time()))
					.toList();
			if (visibleHtf.size() < 10)
				continue;

			// LTF candles: last 50 up to current
			int ltfStart = Math.max(0, i - 50);
			List<Candle> visibleLtf = ltfCandles.subList(ltfStart, i + 1);

			// Compute USDX bias
			UsdxBias currentBias;
			if (dynamicBias) {
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

			// Build context WITH SMT signal
			MarketContext ctx = new MarketContext(
					currentCandle.time(),
					instrument,
					killzone,
					currentBias,
					visibleHtf,
					visibleLtf,
					newsBlackout,
					smt);

			Optional<TradeSignal> signal = aggregator.evaluate(ctx);

			if (signal.isEmpty()) {
				signalsRejected++;
				continue;
			}

			TradeSignal s = signal.get();
			signalsGenerated++;

			// Calculate SL and TP with spread applied
			long sl, tp;
			if (s.bias() == MarketBias.BULLISH) {
				// Long: enter at ask (ideal entry + half spread)
				long effectiveEntry = s.idealEntry() + (spreadScaled / 2);
				sl = s.sweepPrice() - slBufferScaled;
				long risk = effectiveEntry - sl;
				tp = effectiveEntry + (long) (risk * riskRewardRatio);
			} else {
				// Short: enter at bid (ideal entry - half spread)
				long effectiveEntry = s.idealEntry() - (spreadScaled / 2);
				sl = s.sweepPrice() + slBufferScaled;
				long risk = sl - effectiveEntry;
				tp = effectiveEntry - (long) (risk * riskRewardRatio);
			}

			SimulatedTrade trade = new SimulatedTrade(s, sl, tp);
			openTrades.add(trade);
			allTrades.add(trade);

			// Start cooldown — don't open another trade for 50 candles
			cooldownBars = cooldownPeriod;
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
