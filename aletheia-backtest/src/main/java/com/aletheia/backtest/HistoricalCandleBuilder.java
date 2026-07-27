package com.aletheia.backtest;

import com.aletheia.core.Candle;
import com.aletheia.core.Tick;
import com.aletheia.core.Timeframe;
import com.aletheia.data.CandleAggregator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds historical candles from a list of ticks.
 *
 * This reuses the exact same CandleAggregator that live trading uses.
 * The aggregator doesn't know whether ticks are live or historical —
 * it just builds candles from whatever ticks you feed it.
 *
 * WHY NOT JUST QUERY CANDLES FROM THE DATABASE?
 * You could, if you already aggregated and stored them. But for a
 * fresh backtest from Dukascopy tick data, you need to build candles
 * from raw ticks. This class does that in memory — no database needed.
 *
 * USAGE:
 * List<Tick> ticks = dukascopyLoader.downloadTicks(...);
 * HistoricalCandleBuilder builder = new HistoricalCandleBuilder();
 * builder.buildFrom(ticks);
 * List<Candle> min15 = builder.getCandles(Timeframe.MIN_15);
 * List<Candle> min1 = builder.getCandles(Timeframe.MIN_1);
 */
public class HistoricalCandleBuilder {

	private final List<Candle> closedCandles = Collections.synchronizedList(new ArrayList<>());
	private final CandleAggregator aggregator;

	public HistoricalCandleBuilder() {
		this.aggregator = new CandleAggregator();
		// Register a listener that collects all closed candles
		aggregator.addCandleListener(closedCandles::add);
	}

	/**
	 * Feeds all ticks through the CandleAggregator.
	 * Ticks MUST be in chronological order (oldest first).
	 *
	 * After this method returns, call getCandles() to retrieve
	 * the aggregated candles at any timeframe.
	 *
	 * @param ticks historical ticks in chronological order
	 */
	public void buildFrom(List<Tick> ticks) {
		if (ticks == null || ticks.isEmpty())
			return;

		System.out.println("[HistoricalCandleBuilder] Processing "
				+ ticks.size() + " ticks...");

		int count = 0;
		for (Tick tick : ticks) {
			aggregator.onTick(tick);
			count++;

			// Progress reporting every 100,000 ticks
			if (count % 100_000 == 0) {
				System.out.println("  Processed " + count + " ticks, "
						+ closedCandles.size() + " candles built so far");
			}
		}

		System.out.println("[HistoricalCandleBuilder] Complete. "
				+ closedCandles.size() + " candles built from "
				+ count + " ticks.");
	}

	/**
	 * Returns all closed candles for the given timeframe,
	 * sorted in chronological order.
	 */
	public List<Candle> getCandles(Timeframe timeframe) {
		return closedCandles.stream()
				.filter(c -> c.timeframe() == timeframe)
				.sorted((a, b) -> a.time().compareTo(b.time()))
				.toList();
	}

	/**
	 * Returns all closed candles for the given instrument and timeframe.
	 */
	public List<Candle> getCandles(String instrument, Timeframe timeframe) {
		return closedCandles.stream()
				.filter(c -> c.instrument().equals(instrument))
				.filter(c -> c.timeframe() == timeframe)
				.sorted((a, b) -> a.time().compareTo(b.time()))
				.toList();
	}

	/**
	 * Returns a summary of how many candles were built per timeframe.
	 */
	public Map<Timeframe, Long> summary() {
		return closedCandles.stream()
				.collect(Collectors.groupingBy(Candle::timeframe, Collectors.counting()));
	}

	/**
	 * Total number of closed candles across all timeframes.
	 */
	public int totalCandles() {
		return closedCandles.size();
	}
}
