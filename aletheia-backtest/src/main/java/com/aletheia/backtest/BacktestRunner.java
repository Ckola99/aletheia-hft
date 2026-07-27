package com.aletheia.backtest;

import com.aletheia.calendar.CsvCalendarLoader;
import com.aletheia.core.Candle;
import com.aletheia.core.EconomicEvent;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import com.aletheia.data.Bi5TickParser;
import com.aletheia.data.DukascopyHistoryLoader;
import com.aletheia.data.TickRepository;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Self-contained backtest runner.
 *
 * Downloads historical ticks from Dukascopy, aggregates them into candles,
 * creates synthetic USDX bias data, loads economic calendar events,
 * and runs the full backtest — all in memory, no database required.
 *
 * USAGE:
 * BacktestRunner runner = new BacktestRunner();
 * runner.run("EUR_USD",
 * LocalDate.of(2023, 6, 1),
 * LocalDate.of(2023, 6, 30),
 * Path.of("data/calendar_2023.csv"));
 *
 * This will:
 * 1. Download June 2023 EUR/USD ticks from Dukascopy (~4M ticks)
 * 2. Aggregate into candles at all timeframes
 * 3. Create synthetic USDX from EUR/USD inverse
 * 4. Load news events from the CSV
 * 5. Run the backtest and print the performance report
 */
public class BacktestRunner {

	private final double riskRewardRatio;
	private final long slBufferScaled;
	private final int maxOpenTrades;

	/**
	 * @param riskRewardRatio target R:R (e.g. 3.0 means TP is 3× SL distance)
	 * @param slBufferScaled  extra buffer beyond sweep price for SL placement
	 * @param maxOpenTrades   maximum simultaneous positions
	 */
	public BacktestRunner(double riskRewardRatio, long slBufferScaled, int maxOpenTrades) {
		this.riskRewardRatio = riskRewardRatio;
		this.slBufferScaled = slBufferScaled;
		this.maxOpenTrades = maxOpenTrades;
	}

	/**
	 * Creates a runner with default settings:
	 * R:R 3.0, SL buffer 20 units, max 2 open trades.
	 */
	public BacktestRunner() {
		this(3.0, 20L, 2);
	}

	/**
	 * Runs a complete backtest from scratch — download, aggregate, simulate.
	 *
	 * @param instrument  which pair to backtest e.g. "EUR_USD"
	 * @param startDate   first day of backtest period
	 * @param endDate     last day of backtest period
	 * @param calendarCsv path to calendar CSV file (null to skip news guard)
	 * @return the backtest result with trades and metrics
	 */
	public BacktestResult run(String instrument, LocalDate startDate,
			LocalDate endDate, Path calendarCsv) {

		System.out.println("══════════════════════════════════════════════════════════");
		System.out.println("  ALETHEIA BACKTEST RUNNER");
		System.out.println("  Instrument:  " + instrument);
		System.out.println("  Period:      " + startDate + " to " + endDate);
		System.out.println("  R:R target:  " + riskRewardRatio);
		System.out.println("  SL buffer:   " + slBufferScaled);
		System.out.println("  Max trades:  " + maxOpenTrades);
		System.out.println("══════════════════════════════════════════════════════════");

		// ── Step 1: Download historical ticks ────────────────────────
		System.out.println("\n── Step 1: Downloading ticks from Dukascopy ──────────");
		List<Tick> ticks = downloadTicks(instrument, startDate, endDate);

		if (ticks.isEmpty()) {
			System.out.println("  No ticks downloaded. Aborting.");
			return new BacktestResult(List.of(), 0, 0);
		}

		// ── Step 2: Aggregate ticks into candles ─────────────────────
		System.out.println("\n── Step 2: Aggregating ticks into candles ────────────");
		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
		builder.buildFrom(ticks);

		// Print summary
		builder.summary().forEach((tf, count) -> System.out.println("  " + tf + ": " + count + " candles"));

		List<Candle> htfCandles = builder.getCandles(instrument, Timeframe.MIN_15);
		List<Candle> ltfCandles = builder.getCandles(instrument, Timeframe.MIN_1);

		System.out.println("  HTF (MIN_15): " + htfCandles.size() + " candles");
		System.out.println("  LTF (MIN_1):  " + ltfCandles.size() + " candles");

		if (htfCandles.size() < 30 || ltfCandles.size() < 50) {
			System.out.println("  Insufficient candles for backtest. Aborting.");
			return new BacktestResult(List.of(), 0, 0);
		}

		// ── Step 3: Create synthetic USDX candles ────────────────────
		System.out.println("\n── Step 3: Building synthetic USDX bias data ────────");
		List<Candle> eurDaily = builder.getCandles(instrument, Timeframe.DAILY);
		List<Candle> eurHour4 = builder.getCandles(instrument, Timeframe.HOUR_4);
		List<Candle> eurHour1 = builder.getCandles(instrument, Timeframe.HOUR_1);

		// Use DAILY as monthly proxy, HOUR_4 as weekly proxy, HOUR_1 as daily proxy
		// (We don't have enough data for real monthly/weekly candles in a 1-month
		// backtest)
		// This gives us multi-timeframe structure analysis at accessible timeframes
		List<Candle> usdxMonthlyProxy = SyntheticUsdxBuilder.fromEurUsd(eurDaily);
		List<Candle> usdxWeeklyProxy = SyntheticUsdxBuilder.fromEurUsd(eurHour4);
		List<Candle> usdxDailyProxy = SyntheticUsdxBuilder.fromEurUsd(eurHour1);

		System.out.println("  USDX monthly proxy (from DAILY):  " + usdxMonthlyProxy.size());
		System.out.println("  USDX weekly proxy  (from HOUR_4): " + usdxWeeklyProxy.size());
		System.out.println("  USDX daily proxy   (from HOUR_1): " + usdxDailyProxy.size());

		// ── Step 4: Load economic calendar ───────────────────────────
		BacktestEngine engine = new BacktestEngine(riskRewardRatio, slBufferScaled, maxOpenTrades);

		if (calendarCsv != null) {
			System.out.println("\n── Step 4: Loading economic calendar ─────────────────");
			try {
				CsvCalendarLoader csvLoader = new CsvCalendarLoader(calendarCsv);
				List<EconomicEvent> events = csvLoader.fetch(startDate, endDate);
				engine.calendarService().loadEvents(events);
				System.out.println("  Loaded " + events.size() + " events");
			} catch (Exception e) {
				System.out.println("  Warning: could not load calendar — " + e.getMessage());
				System.out.println("  Continuing without news guard.");
			}
		} else {
			System.out.println("\n── Step 4: No calendar file — news guard disabled ───");
		}

		// ── Step 5: Run the backtest ─────────────────────────────────
		System.out.println("\n── Step 5: Running backtest ──────────────────────────");
		BacktestResult result = engine.run(
				instrument,
				htfCandles,
				ltfCandles,
				usdxMonthlyProxy,
				usdxWeeklyProxy,
				usdxDailyProxy);

		// ── Step 6: Print results ────────────────────────────────────
		System.out.println("\n");
		result.printReport();

		return result;
	}

	/**
	 * Downloads ticks from Dukascopy into memory.
	 * Uses a TickRepository that collects ticks instead of writing to DB.
	 */
	private List<Tick> downloadTicks(String instrument, LocalDate startDate,
			LocalDate endDate) {
		List<Tick> allTicks = new ArrayList<>();

		// TickRepository that collects ticks in memory instead of DB
		TickRepository memoryRepo = new TickRepository(null, Integer.MAX_VALUE) {
			@Override
			public void onTick(Tick tick) {
				allTicks.add(tick);
			}

			@Override
			public synchronized void flush() {
				// no-op — ticks are already in allTicks
			}
		};

		DukascopyHistoryLoader loader = new DukascopyHistoryLoader(memoryRepo);
		loader.load(instrument, startDate, endDate);

		System.out.println("  Downloaded " + allTicks.size() + " ticks");
		return allTicks;
	}
}
