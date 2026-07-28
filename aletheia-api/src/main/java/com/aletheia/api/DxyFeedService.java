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
 * - On startup, a background thread downloads `lookbackDays` of DXY ticks,
 * streams them through a CandleAggregator, and keeps the resulting
 * DAILY / HOUR_4 / HOUR_1 candles.
 * - On a fixed interval it re-downloads a small recent window and merges it,
 * replacing the tail so the bias stays fresh without re-pulling everything.
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
 * - Until the first download completes, the lists are empty; the caller treats
 * "no data" as NEUTRAL bias, so the engine simply doesn't trade yet.
 * - Dukascopy's publishing lag means the most recent hour may be missing.
 * That's acceptable for a higher-timeframe structural bias.
 *
 * NOTE ON LOGGING: DukascopyHistoryLoader prints per-day progress, so refreshes
 * are visible in the logs.
 */
@Component
public class DxyFeedService {

	private final boolean enabled;
	private final String dxyInstrument;
	private final int lookbackDays;
	private final int refreshDays;
	private final long refreshMinutes;

	// Immutable snapshots, swapped atomically via volatile
	private volatile List<Candle> daily = List.of(); // monthly proxy
	private volatile List<Candle> hour4 = List.of(); // weekly proxy
	private volatile List<Candle> hour1 = List.of(); // daily proxy
	private volatile boolean seeded = false;

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
			@Value("${trading.dxy.refresh-minutes:120}") long refreshMinutes) {
		this.enabled = enabled;
		this.dxyInstrument = dxyInstrument;
		this.lookbackDays = lookbackDays;
		this.refreshDays = refreshDays;
		this.refreshMinutes = refreshMinutes;
	}

	@PostConstruct
	public void init() {
		if (!enabled) {
			System.out.println("[DxyFeedService] Disabled — USDX bias will fall back to synthetic.");
			return;
		}
		System.out.println("[DxyFeedService] Enabled. Seeding " + lookbackDays
				+ " days of " + dxyInstrument + " in the background...");
		// First run (delay 0) seeds lookbackDays; subsequent runs refresh a small
		// window
		scheduler.scheduleWithFixedDelay(this::refresh, 0, refreshMinutes, TimeUnit.MINUTES);
	}

	/**
	 * Downloads recent DXY, aggregates to candles, and updates the snapshots.
	 * The first successful run seeds the full history; later runs merge a
	 * recent window over the tail.
	 */
	private void refresh() {
		try {
			LocalDate end = LocalDate.now(ZoneOffset.UTC);
			int days = seeded ? refreshDays : lookbackDays;
			LocalDate start = end.minusDays(days);

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
