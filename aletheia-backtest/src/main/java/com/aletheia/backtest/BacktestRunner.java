package com.aletheia.backtest;

import com.aletheia.calendar.CsvCalendarLoader;
import com.aletheia.core.Candle;
import com.aletheia.core.EconomicEvent;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import com.aletheia.data.DukascopyHistoryLoader;
import com.aletheia.data.TickRepository;
import com.aletheia.strategy.SmtDivergenceDetector;
import com.aletheia.strategy.SmtDivergenceSignal;
import com.aletheia.strategy.SmtPair;
import com.aletheia.strategy.SwingPointRegistry;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Self-contained backtest runner with spread simulation and SMT divergence.
 */
public class BacktestRunner {

	private final double riskRewardRatio;
	private final long slBufferScaled;
	private final int maxOpenTrades;
	private final long spreadScaled;

	public BacktestRunner(double riskRewardRatio, long slBufferScaled,
			int maxOpenTrades, long spreadScaled) {
		this.riskRewardRatio = riskRewardRatio;
		this.slBufferScaled = slBufferScaled;
		this.maxOpenTrades = maxOpenTrades;
		this.spreadScaled = spreadScaled;
	}

	public BacktestRunner() {
		this(3.0, 20L, 2, 15L);
	}

	public BacktestResult run(String instrument, String smtInstrument,
			LocalDate startDate, LocalDate endDate,
			Path calendarCsv) {

		System.out.println("==========================================================");
		System.out.println("  ALETHEIA BACKTEST RUNNER");
		System.out.println("  Primary:     " + instrument);
		System.out.println("  SMT pair:    " + (smtInstrument != null ? smtInstrument : "disabled"));
		System.out.println("  Period:      " + startDate + " to " + endDate);
		System.out.println("  R:R target:  " + riskRewardRatio);
		System.out.println("  Spread:      " + spreadScaled + " (" + (spreadScaled / 10.0) + " pips)");
		System.out.println("  Max trades:  " + maxOpenTrades);
		System.out.println("==========================================================");

		// -- Step 1: Download and aggregate PRIMARY instrument ----------
		System.out.println("\n-- Step 1: Building " + instrument + " candles ----------");
		HistoricalCandleBuilder primaryBuilder = downloadAndAggregate(instrument, startDate, endDate);

		if (primaryBuilder.totalCandles() == 0) {
			System.out.println("  No candles built. Aborting.");
			return new BacktestResult(List.of(), 0, 0);
		}

		primaryBuilder.summary().forEach((tf, count) -> System.out.println("  " + tf + ": " + count));

		List<Candle> htfCandles = primaryBuilder.getCandles(instrument, Timeframe.HOUR_1);
		List<Candle> ltfCandles = primaryBuilder.getCandles(instrument, Timeframe.MIN_5);

		System.out.println("  HTF (MIN_15): " + htfCandles.size() + " candles");
		System.out.println("  LTF (MIN_1):  " + ltfCandles.size() + " candles");

		if (htfCandles.size() < 30 || ltfCandles.size() < 50) {
			System.out.println("  Insufficient candles for backtest. Aborting.");
			return new BacktestResult(List.of(), 0, 0);
		}

		// -- Step 2: Download and aggregate SMT instrument --------------
		HistoricalCandleBuilder smtBuilder = null;
		if (smtInstrument != null) {
			System.out.println("\n-- Step 2: Building " + smtInstrument + " candles ----------");
			smtBuilder = downloadAndAggregate(smtInstrument, startDate, endDate);

			smtBuilder.summary().forEach((tf, count) -> System.out.println("  " + tf + ": " + count));
		} else {
			System.out.println("\n-- Step 2: SMT disabled ---------------------------------");
		}

		// -- Step 3: Download and aggregate DXY data --------------------
		System.out.println("\n-- Step 3: Downloading DXY (US Dollar Index) ----------");
		// Download DXY with 3 months of lead-in data for structure analysis
		// The bias engine needs prior Monthly/Weekly candles to establish trend
		LocalDate dxyStart = startDate.minusMonths(3);
		System.out.println("  DXY range: " + dxyStart + " to " + endDate
				+ " (3-month lead-in for structure)");
		HistoricalCandleBuilder dxyBuilder = downloadAndAggregate("DOLLAR_IDX", dxyStart, endDate);

		List<Candle> usdxMonthlyProxy;
		List<Candle> usdxWeeklyProxy;
		List<Candle> usdxDailyProxy;

		if (dxyBuilder.totalCandles() > 0) {
			// Use real DXY data
			// DAILY as monthly proxy, HOUR_4 as weekly proxy, HOUR_1 as daily proxy
			// (For short backtests we don't have enough data for real monthly/weekly)
			usdxMonthlyProxy = dxyBuilder.getCandles("DOLLAR_IDX", Timeframe.DAILY);
			usdxWeeklyProxy = dxyBuilder.getCandles("DOLLAR_IDX", Timeframe.HOUR_4);
			usdxDailyProxy = dxyBuilder.getCandles("DOLLAR_IDX", Timeframe.HOUR_1);

			System.out.println("  Using REAL DXY data");
			System.out.println("  Monthly proxy (DXY DAILY):  " + usdxMonthlyProxy.size());
			System.out.println("  Weekly proxy  (DXY HOUR_4): " + usdxWeeklyProxy.size());
			System.out.println("  Daily proxy   (DXY HOUR_1): " + usdxDailyProxy.size());

			// Free DXY builder memory
			dxyBuilder = null;
			System.gc();
		} else {
			// Fallback to synthetic if DXY download fails
			System.out.println("  DXY download failed -- falling back to synthetic USDX");
			List<Candle> eurDaily = primaryBuilder.getCandles(instrument, Timeframe.DAILY);
			List<Candle> eurHour4 = primaryBuilder.getCandles(instrument, Timeframe.HOUR_4);
			List<Candle> eurHour1 = primaryBuilder.getCandles(instrument, Timeframe.HOUR_1);

			usdxMonthlyProxy = SyntheticUsdxBuilder.fromEurUsd(eurDaily);
			usdxWeeklyProxy = SyntheticUsdxBuilder.fromEurUsd(eurHour4);
			usdxDailyProxy = SyntheticUsdxBuilder.fromEurUsd(eurHour1);
		}

		// -- Step 4: Check SMT Divergence -------------------------------
		Optional<SmtDivergenceSignal> smtSignal = Optional.empty();

		if (smtBuilder != null) {
			System.out.println("\n-- Step 4: Checking SMT Divergence ----------------------");
			SwingPointRegistry smtRegistry = new SwingPointRegistry(3, 50);
			SmtDivergenceDetector smtDetector = new SmtDivergenceDetector();

			List<Candle> primaryMin15 = primaryBuilder.getCandles(instrument, Timeframe.MIN_15);
			List<Candle> smtMin15 = smtBuilder.getCandles(smtInstrument, Timeframe.MIN_15);

			smtRegistry.update(instrument, Timeframe.MIN_15, primaryMin15);
			smtRegistry.update(smtInstrument, Timeframe.MIN_15, smtMin15);

			System.out.println("  " + instrument + " swings: "
					+ smtRegistry.getSwings(instrument, Timeframe.MIN_15).size());
			System.out.println("  " + smtInstrument + " swings: "
					+ smtRegistry.getSwings(smtInstrument, Timeframe.MIN_15).size());

			smtSignal = smtDetector.detect(
					new SmtPair(instrument, smtInstrument),
					Timeframe.MIN_15,
					smtRegistry,
					com.aletheia.core.KillzoneWindow.LONDON_OPEN);

			if (smtSignal.isEmpty()) {
				smtSignal = smtDetector.detect(
						new SmtPair(instrument, smtInstrument),
						Timeframe.MIN_15,
						smtRegistry,
						com.aletheia.core.KillzoneWindow.NEW_YORK_OPEN);
			}

			if (smtSignal.isPresent()) {
				System.out.println("  SMT Divergence FOUND: " + smtSignal.get().type());
			} else {
				System.out.println("  No SMT divergence detected");
			}

			// Free SMT builder memory — we only needed it for swing detection
			smtBuilder = null;
			System.gc();
		} else {
			System.out.println("\n-- Step 4: SMT disabled ---------------------------------");
		}

		// -- Step 5: Load economic calendar -----------------------------
		BacktestEngine engine = new BacktestEngine(
				riskRewardRatio, slBufferScaled, maxOpenTrades, spreadScaled);

		if (calendarCsv != null) {
			System.out.println("\n-- Step 5: Loading economic calendar ---------------------");
			try {
				CsvCalendarLoader csvLoader = new CsvCalendarLoader(calendarCsv);
				List<EconomicEvent> events = csvLoader.fetch(startDate, endDate);
				engine.calendarService().loadEvents(events);
				System.out.println("  Loaded " + events.size() + " events");
			} catch (Exception e) {
				System.out.println("  Warning: could not load calendar -- " + e.getMessage());
			}
		} else {
			System.out.println("\n-- Step 5: No calendar file -- news guard disabled ------");
		}

		// Free primary builder — we extracted all the candle lists we need
		primaryBuilder = null;
		System.gc();

		// -- Step 6: Run the backtest -----------------------------------
		System.out.println("\n-- Step 6: Running backtest ------------------------------");
		BacktestResult result = engine.run(
				instrument,
				htfCandles,
				ltfCandles,
				usdxMonthlyProxy,
				usdxWeeklyProxy,
				usdxDailyProxy,
				smtSignal);

		System.out.println("\n");
		result.printReport();

		return result;
	}

	public BacktestResult run(String instrument, LocalDate startDate,
			LocalDate endDate, Path calendarCsv) {
		return run(instrument, null, startDate, endDate, calendarCsv);
	}

	/**
	 * Downloads ticks and aggregates into candles IN ONE STEP.
	 * The ticks are discarded after aggregation to free memory.
	 * This is the key memory optimisation for large backtests.
	 *
	 * Instead of: download all ticks → hold in memory → aggregate → hold candles +
	 * ticks
	 * We do: download ticks → aggregate on the fly → only candles remain
	 */
	private HistoricalCandleBuilder downloadAndAggregate(String instrument,
			LocalDate startDate,
			LocalDate endDate) {
		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();

		// Feed ticks directly to the builder as they download
		// Ticks are NOT stored — only candles accumulate
		TickRepository streamingRepo = new TickRepository(null, 1_000_000) {
			@Override
			public void onTick(Tick tick) {
				builder.processOneTick(tick);
			}

			@Override
			public synchronized void flush() {
				// no-op
			}
		};

		DukascopyHistoryLoader loader = new DukascopyHistoryLoader(streamingRepo);
		loader.load(instrument, startDate, endDate);

		System.out.println("  Candles built: " + builder.totalCandles());
		return builder;
	}
}
