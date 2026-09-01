package com.aletheia.api;

import com.aletheia.core.Candle;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import com.aletheia.data.CandleAggregator;
import com.aletheia.data.DukascopyHistoryLoader;
import com.aletheia.data.TickRepository;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Provides REAL US Dollar Index candles for the USDX bias, sourced from
 * Dukascopy (symbol DOLLAR_IDX -> DOLLARIDXUSD).
 *
 * WHY THIS EXISTS:
 * The tuned strategy used real DXY structure to filter the dollar bias, which
 * roughly doubled the win rate versus the synthetic EUR/USD-inverse proxy.
 * OANDA practice accounts generally don't stream a dollar index, so we pull it
 * from Dukascopy on a schedule instead.
 *
 * HOW IT WORKS:
 * - On startup, it first tries to LOAD CACHED candles from disk. If a recent
 * enough cache exists, the feed is immediately "seeded" and the first refresh
 * only needs to fetch the gap between the cache and now — so the engine can
 * trade almost immediately after a restart instead of re-downloading 90 days.
 * - If there is no cache (or it is too old), a background thread downloads
 * `lookbackDays` of DXY ticks, aggregates them, and writes the result to disk.
 * - On a fixed interval it re-downloads a small recent window, merges it over
 * the tail, and re-writes the cache so the bias stays fresh.
 * - The three candle lists are exposed as immutable, volatile snapshots.
 *
 * MAPPING TO THE BIAS ENGINE (same as the backtest):
 * DAILY -> monthly proxy
 * HOUR_4 -> weekly proxy
 * HOUR_1 -> daily proxy
 *
 * SAFETY:
 * - Runs on its own daemon thread so it never blocks the pricing stream or
 * Spring's scheduler.
 * - Until data is available, the lists are empty; the caller treats "no data"
 * as NEUTRAL bias, so the engine simply doesn't trade yet.
 * - Disk caching is purely additive: if the cache is missing or corrupt, the
 * service silently falls back to seeding from scratch (the original behaviour).
 */
@Component
public class DxyFeedService {

	private final boolean enabled;
	private final String dxyInstrument;
	private final int lookbackDays;
	private final int refreshDays;
	private final long refreshMinutes;
	private final Path cacheFile;

	// Immutable snapshots, swapped atomically via volatile
	private volatile List<Candle> daily = List.of(); // monthly proxy
	private volatile List<Candle> hour4 = List.of(); // weekly proxy
	private volatile List<Candle> hour1 = List.of(); // daily proxy
	private volatile boolean seeded = false;

	// True once we've loaded from disk and still owe the "catch up the gap"
	// fetch. Distinguishes a cache-warm start from a cold seed.
	private volatile boolean loadedFromCache = false;

	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "dxy-feed");
		t.setDaemon(true);
		return t;
	});

	public DxyFeedService(
			@Value("${trading.dxy.enabled:true}") boolean enabled,
			@Value("${trading.dxy.instrument:DOLLAR_IDX}") String dxyInstrument,
			@Value("${trading.dxy.lookback-days:90}") int lookbackDays,
			@Value("${trading.dxy.refresh-days:5}") int refreshDays,
			@Value("${trading.dxy.refresh-minutes:120}") long refreshMinutes,
			@Value("${trading.dxy.cache-file:data/cache/dxy_feed.csv}") String cacheFilePath)  {
		this.enabled = enabled;
		this.dxyInstrument = dxyInstrument;
		this.lookbackDays = lookbackDays;
		this.refreshDays = refreshDays;
		this.refreshMinutes = refreshMinutes;
		this.cacheFile = Path.of(cacheFilePath);
	}

	@PostConstruct
	public void init() {
		if (!enabled) {
			System.out.println("[DxyFeedService] Disabled — USDX bias will fall back to synthetic.");
			return;
		}

		// Try to warm-start from the disk cache before scheduling downloads.
		tryLoadCache();

		if (seeded) {
			System.out.println("[DxyFeedService] Warm start from cache — DAILY=" + daily.size()
					+ " HOUR_4=" + hour4.size() + " HOUR_1=" + hour1.size()
					+ ". Will fetch the gap on first refresh.");
		} else {
			System.out.println("[DxyFeedService] No usable cache. Seeding " + lookbackDays
					+ " days of " + dxyInstrument + " in the background...");
		}

		// First run (delay 0) either fetches the gap (warm) or seeds (cold);
		// subsequent runs refresh a small recent window.
		scheduler.scheduleWithFixedDelay(this::refresh, 0, refreshMinutes, TimeUnit.MINUTES);
	}

	/**
	 * Downloads recent DXY, aggregates to candles, updates the snapshots, and
	 * re-writes the disk cache.
	 *
	 * - Cold start (no cache): first run seeds the full lookback.
	 * - Warm start (loaded cache): first run fetches only the gap from the
	 * newest cached candle to now, then merges.
	 * - Steady state: each run fetches a small `refreshDays` window and merges.
	 */
	private void refresh() {
		try {
			LocalDate end = LocalDate.now(ZoneOffset.UTC);
			LocalDate start;

			if (!seeded) {
				// Cold start — seed the full history.
				start = end.minusDays(lookbackDays);
			} else if (loadedFromCache) {
				// Warm start — fetch from the newest cached candle (minus a small
				// overlap for safety) up to now. This is the "crawl forward
				// however far we were down" behaviour.
				LocalDate newest = newestCandleDate();
				LocalDate gapStart = (newest != null) ? newest.minusDays(1)
						: end.minusDays(refreshDays);
				start = gapStart;
				loadedFromCache = false; // only do the gap-catch-up once
			} else {
				// Steady state — small recent window.
				start = end.minusDays(refreshDays);
			}

			System.out.println("[DxyFeedService] " + (seeded ? "Refreshing" : "Seeding")
					+ " DXY " + start + " -> " + end);

			Aggregated agg = downloadWindow(start, end);

			if (!seeded) {
				daily = agg.daily;
				hour4 = agg.hour4;
				hour1 = agg.hour1;
				seeded = true;
			} else {
				daily = merge(daily, agg.daily);
				hour4 = merge(hour4, agg.hour4);
				hour1 = merge(hour1, agg.hour1);
			}

			System.out.println("[DxyFeedService] DXY candles — DAILY=" + daily.size()
					+ " HOUR_4=" + hour4.size() + " HOUR_1=" + hour1.size());

			// Persist the freshly-updated snapshots for the next restart.
			writeCache();

		} catch (Exception e) {
			System.err.println("[DxyFeedService] Refresh failed (keeping previous data): "
					+ e.getMessage());
		}
	}

	/**
	 * Streams DXY ticks for the window through a fresh aggregator, keeping only
	 * the DAILY / HOUR_4 / HOUR_1 candles (fine timeframes are discarded
	 * immediately to avoid memory blow-up).
	 */
	private Aggregated downloadWindow(LocalDate start, LocalDate end) {
		CandleAggregator aggregator = new CandleAggregator();
		List<Candle> collected = Collections.synchronizedList(new ArrayList<>());

		aggregator.addCandleListener(c -> {
			Timeframe tf = c.timeframe();
			if (tf == Timeframe.DAILY || tf == Timeframe.HOUR_4 || tf == Timeframe.HOUR_1) {
				collected.add(c);
			}
		});

		// Stream ticks straight into the aggregator; never retain the ticks
		TickRepository streamingRepo = new TickRepository(null, 1_000_000) {
			@Override
			public void onTick(Tick tick) {
				aggregator.onTick(tick);
			}

			@Override
			public synchronized void flush() {
				/* no-op */ }
		};

		new DukascopyHistoryLoader(streamingRepo).load(dxyInstrument, start, end);

		return new Aggregated(
				filterSort(collected, Timeframe.DAILY),
				filterSort(collected, Timeframe.HOUR_4),
				filterSort(collected, Timeframe.HOUR_1));
	}

	// ── Public snapshot accessors (read by LiveSignalService) ───────────

	public boolean enabled() {
		return enabled;
	}

	public boolean hasData() {
		return seeded && !daily.isEmpty();
	}

	public List<Candle> usdxMonthly() {
		return daily;
	} // DAILY -> monthly proxy

	public List<Candle> usdxWeekly() {
		return hour4;
	} // HOUR_4 -> weekly proxy

	public List<Candle> usdxDaily() {
		return hour1;
	} // HOUR_1 -> daily proxy

	@PreDestroy
	public void shutdown() {
		scheduler.shutdownNow();
	}

	// ── Disk cache ──────────────────────────────────────────────────────

	/**
	 * Loads the three timeframe lists from the CSV cache, if present and recent
	 * enough. Sets seeded + loadedFromCache on success. Any problem (missing
	 * file, parse error, stale data) leaves the service in the cold-seed state.
	 */
	private void tryLoadCache() {
		try {
			if (!Files.exists(cacheFile)) {
				return;
			}

			List<Candle> d = new ArrayList<>();
			List<Candle> h4 = new ArrayList<>();
			List<Candle> h1 = new ArrayList<>();

			try (BufferedReader r = Files.newBufferedReader(cacheFile)) {
				String line = r.readLine(); // header
				while ((line = r.readLine()) != null) {
					if (line.isBlank())
						continue;
					String[] p = line.split(",");
					if (p.length < 8)
						continue;
					Candle c = new Candle(
							Instant.parse(p[0]),
							p[1],
							Timeframe.valueOf(p[2]),
							Long.parseLong(p[3]),
							Long.parseLong(p[4]),
							Long.parseLong(p[5]),
							Long.parseLong(p[6]),
							Long.parseLong(p[7]));
					switch (c.timeframe()) {
						case DAILY -> d.add(c);
						case HOUR_4 -> h4.add(c);
						case HOUR_1 -> h1.add(c);
						default -> {
							/* ignore anything unexpected */ }
					}
				}
			}

			if (d.isEmpty()) {
				return; // nothing usable
			}

			// Staleness guard: if the newest cached candle is older than the full
			// lookback, the gap is too big to bridge — seed fresh instead.
			Instant newest = d.stream().map(Candle::time).max(Comparator.naturalOrder()).orElse(null);
			if (newest != null) {
				long ageDays = java.time.Duration.between(newest, Instant.now()).toDays();
				if (ageDays > lookbackDays) {
					System.out.println("[DxyFeedService] Cache is " + ageDays
							+ " days old (> " + lookbackDays
							+ ") — ignoring, will seed fresh.");
					return;
				}
			}

			daily = List.copyOf(sortByTime(d));
			hour4 = List.copyOf(sortByTime(h4));
			hour1 = List.copyOf(sortByTime(h1));
			seeded = true;
			loadedFromCache = true;

		} catch (Exception e) {
			System.err.println("[DxyFeedService] Cache load failed (will seed fresh): "
					+ e.getMessage());
			// leave seeded=false
		}
	}

	/**
	 * Writes the current three timeframe lists to the CSV cache. Best-effort:
	 * a failure here is logged but never disrupts the running feed.
	 */
	private void writeCache() {
		try {
			Files.createDirectories(cacheFile.getParent());
			try (BufferedWriter w = Files.newBufferedWriter(cacheFile)) {
				w.write("time,instrument,timeframe,open,high,low,close,volume");
				w.newLine();
				writeCandles(w, daily);
				writeCandles(w, hour4);
				writeCandles(w, hour1);
			}
		} catch (Exception e) {
			System.err.println("[DxyFeedService] Cache write failed (non-fatal): "
					+ e.getMessage());
		}
	}

	private void writeCandles(BufferedWriter w, List<Candle> candles) throws IOException {
		for (Candle c : candles) {
			w.write(c.time() + "," + c.instrument() + "," + c.timeframe()
					+ "," + c.open() + "," + c.high() + "," + c.low()
					+ "," + c.close() + "," + c.volume());
			w.newLine();
		}
	}

	/** The date (UTC) of the newest DAILY candle we currently hold, or null. */
	private LocalDate newestCandleDate() {
		return daily.stream()
				.map(Candle::time)
				.max(Comparator.naturalOrder())
				.map(i -> i.atZone(ZoneOffset.UTC).toLocalDate())
				.orElse(null);
	}

	private static List<Candle> sortByTime(List<Candle> in) {
		return in.stream().sorted(Comparator.comparing(Candle::time)).toList();
	}

	// ── Pure helpers (unit-testable, no network) ────────────────────────

	/** Filters to one timeframe and sorts oldest-first. */
	static List<Candle> filterSort(List<Candle> all, Timeframe tf) {
		return all.stream()
				.filter(c -> c.timeframe() == tf)
				.sorted(Comparator.comparing(Candle::time))
				.toList();
	}

	/**
	 * Replaces the tail of `existing` with `refreshed`: keeps existing candles
	 * strictly before the first refreshed candle's time, then appends all
	 * refreshed candles. Both inputs must be oldest-first.
	 */
	static List<Candle> merge(List<Candle> existing, List<Candle> refreshed) {
		if (refreshed.isEmpty())
			return existing;
		if (existing.isEmpty())
			return refreshed;

		Instant cut = refreshed.get(0).time();
		List<Candle> out = new ArrayList<>();
		for (Candle c : existing) {
			if (c.time().isBefore(cut))
				out.add(c);
		}
		out.addAll(refreshed);
		return List.copyOf(out);
	}

	/** Small carrier for the three timeframes we care about. */
	private record Aggregated(List<Candle> daily, List<Candle> hour4, List<Candle> hour1) {
	}
}
