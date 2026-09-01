package com.aletheia.backtest;

import com.aletheia.calendar.CsvCalendarLoader;
import com.aletheia.core.Candle;
import com.aletheia.core.EconomicEvent;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import com.aletheia.data.DukascopyHistoryLoader;
import com.aletheia.data.TickRepository;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;

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
	private final java.util.Map<String, HistoricalCandleBuilder> candleCache = new java.util.HashMap<>();

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

		Timeframe htf = Timeframe.HOUR_1;
		Timeframe ltf = Timeframe.MIN_5;
		List<Candle> htfCandles = primaryBuilder.getCandles(instrument, htf);
		List<Candle> ltfCandles = primaryBuilder.getCandles(instrument, ltf);

		System.out.println("  HTF (" + htf.displayName() + "): " + htfCandles.size() + " candles");
		System.out.println("  LTF (" + ltf.displayName() + "): " + ltfCandles.size() + " candles");

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

		// -- Step 4: Extract SMT partner candles (SMT now computed per-candle
		// inside the engine, mirroring live — no precompute, no look-ahead)
		List<Candle> selfMin15 = primaryBuilder.getCandles(instrument, Timeframe.MIN_15);
		List<Candle> smtPartnerMin15 = null;
		if (smtBuilder != null) {
			System.out.println("\n-- Step 4: SMT partner = " + smtInstrument
					+ " (per-candle divergence) ----------");
			smtPartnerMin15 = smtBuilder.getCandles(smtInstrument, Timeframe.MIN_15);
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
		BacktestResult result = engine.runWithLiveSmt(
				instrument,
				htfCandles,
				ltfCandles,
				usdxMonthlyProxy,
				usdxWeeklyProxy,
				usdxDailyProxy,
				smtInstrument,
				selfMin15,
				smtPartnerMin15);

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
	private static final Path CACHE_DIR = Path.of("data/cache");

	private HistoricalCandleBuilder downloadAndAggregate(String instrument,
			LocalDate startDate,
			LocalDate endDate) {

		String cacheKey = instrument + "|" + startDate + "|" + endDate;

		// 1. In-memory cache (within this run)
		HistoricalCandleBuilder cached = candleCache.get(cacheKey);
		if (cached != null) {
			System.out.println("  Reusing in-memory candles for " + instrument
					+ " (" + startDate + " to " + endDate + ") -- download skipped");
			return cached;
		}

		// 2. Disk cache (across runs) — safe for complete past ranges only
		Path cacheFile = cacheFileFor(instrument, startDate, endDate);
		boolean recentRange = !endDate.isBefore(LocalDate.now().minusDays(2));
		if (!recentRange && Files.exists(cacheFile)) {
			try {
				HistoricalCandleBuilder builder = loadFromDisk(cacheFile);
				System.out.println("  Loaded " + builder.totalCandles()
						+ " candles from DISK cache for " + instrument
						+ " (" + startDate + " to " + endDate + ") -- download skipped");
				candleCache.put(cacheKey, builder);
				return builder;
			} catch (IOException e) {
				System.out.println("  Disk cache read failed (" + e.getMessage()
						+ ") -- falling back to download");
			}
		}

		// 3. Cache miss — download + aggregate
		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();

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

		// Write to disk cache for future runs (skip very recent ranges)
		if (!recentRange && builder.totalCandles() > 0) {
			try {
				writeToDisk(cacheFile, builder);
				System.out.println("  Wrote " + builder.totalCandles()
						+ " candles to DISK cache: " + cacheFile);
			} catch (IOException e) {
				System.out.println("  Disk cache write failed (" + e.getMessage() + ")");
			}
		}

		candleCache.put(cacheKey, builder);
		return builder;
	}

	private Path cacheFileFor(String instrument, LocalDate start, LocalDate end) {
		String name = instrument + "_" + start + "_" + end + ".csv";
		return CACHE_DIR.resolve(name);
	}

	/** Writes all closed candles to a CSV file (one row per candle). */
	private void writeToDisk(Path file, HistoricalCandleBuilder builder)
			throws IOException {
		Files.createDirectories(file.getParent());
		try (BufferedWriter w = Files.newBufferedWriter(file)) {
			w.write("time,instrument,timeframe,open,high,low,close,volume");
			w.newLine();
			// Pull every timeframe's candles from the builder
			for (Timeframe tf : Timeframe.values()) {
				for (Candle c : builder.getCandles(tf)) {
					w.write(c.time() + "," + c.instrument() + "," + c.timeframe()
							+ "," + c.open() + "," + c.high() + "," + c.low()
							+ "," + c.close() + "," + c.volume());
					w.newLine();
				}
			}
		}
	}

	/** Reads candles back from a CSV file into a fresh builder. */
	private HistoricalCandleBuilder loadFromDisk(Path file) throws IOException {
		List<Candle> candles = new ArrayList<>();
		try (BufferedReader r = Files.newBufferedReader(file)) {
			String line = r.readLine(); // skip header
			while ((line = r.readLine()) != null) {
				if (line.isBlank())
					continue;
				String[] p = line.split(",");
				candles.add(new Candle(
						Instant.parse(p[0]),
						p[1],
						Timeframe.valueOf(p[2]),
						Long.parseLong(p[3]),
						Long.parseLong(p[4]),
						Long.parseLong(p[5]),
						Long.parseLong(p[6]),
						Long.parseLong(p[7])));
			}
		}
		HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
		builder.loadCandles(candles);
		return builder;
	}
}
