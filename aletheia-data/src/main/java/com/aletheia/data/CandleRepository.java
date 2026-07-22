package com.aletheia.data;

import com.aletheia.core.Candle;
import com.aletheia.core.Timeframe;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;

/**
 * Persists closed candles to the TimescaleDB 'candles' table
 * and provides query methods for the strategy engine.
 *
 * Unlike TickRepository, candles don't need batch buffering —
 * candle closes are infrequent (a MIN_15 candle closes once every
 * 15 minutes, not 50 times per second like ticks).
 *
 * IMPLEMENTS CandleListener — plugs into CandleAggregator:
 * aggregator.addCandleListener(candleRepository);
 * // every closed candle is automatically saved to the database
 */

public class CandleRepository implements CandleListener {

	private final JdbcOperations jdbc;

	private static final String INSERT_SQL = "INSERT INTO candles (time, instrument, timeframe, open, high, low, close, volume) "
			+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

	/**
	 * Query: get the most recent N candles for a given instrument and timeframe.
	 *
	 * Used by the strategy engine:
	 * "Give me the last 100 15-minute EUR_USD candles"
	 * → MarketStructureAnalyser uses these to determine HH/HL/LH/LL
	 * → FairValueGapDetector scans these for 3-candle gap patterns
	 *
	 * ORDER BY time DESC → most recent first
	 * LIMIT ? → only return N candles (not the entire history)
	 */

	private static final String FIND_RECENT_SQL = "SELECT time, instrument, timeframe, open, high, low, close, volume "
			+ "FROM candles "
			+ "WHERE instrument = ? AND timeframe = ? "
			+ "ORDER BY time DESC "
			+ "LIMIT ?";

	public CandleRepository(JdbcOperations jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Called by CandleAggregator when a candle period ends.
	 * Saves the completed candle to the database.
	 */
	@Override
	public void onCandleClosed(Candle candle) {
		jdbc.update(INSERT_SQL,
				Timestamp.from(candle.time()),
				candle.instrument(),
				candle.timeframe().name(), // enum → string: "MIN_15"
				candle.open(),
				candle.high(),
				candle.low(),
				candle.close(),
				candle.volume());
	}

	/**
	 * Returns the most recent N candles for the given instrument and timeframe.
	 *
	 * The result is ordered most-recent-first, but the strategy engine
	 * typically wants oldest-first (chronological order), so we reverse.
	 *
	 * WHAT IS RowMapper?
	 * jdbc.query() executes the SQL and returns rows from the database.
	 * The lambda (rs, rowNum) -> { ... } converts each database row
	 * into a Candle object. 'rs' is the ResultSet — you read columns
	 * from it by name or position.
	 *
	 * @param instrument e.g. "EUR_USD"
	 * @param timeframe  e.g. Timeframe.MIN_15
	 * @param limit      how many candles to return (e.g. 100)
	 * @return candles in chronological order (oldest first)
	 */

	public List<Candle> findRecent(String instrument, Timeframe timeframe, int limit) {
		List<Candle> candles = jdbc.query(FIND_RECENT_SQL,
				(rs, rowNum) -> new Candle(
						// rs.getTimestamp("time") → java.sql.Timestamp
						// .toInstant() → java.time.Instant
						rs.getTimestamp("time").toInstant(),
						rs.getString("instrument"),
						// Timeframe.valueOf("MIN_15") → Timeframe.MIN_15
						Timeframe.valueOf(rs.getString("timeframe")),
						rs.getLong("open"),
						rs.getLong("high"),
						rs.getLong("low"),
						rs.getLong("close"),
						rs.getLong("volume")),
				instrument,
				timeframe.name(),
				limit);

		// Reverse: DB returns newest-first, strategy wants oldest-first
		return candles.reversed();
	}

	/**
	 * Returns candles between two timestamps.
	 * Used by BacktestEngine to load historical candles for a specific date range.
	 *
	 * @param instrument e.g. "EUR_USD"
	 * @param timeframe  e.g. Timeframe.MIN_15
	 * @param from       start of range (inclusive)
	 * @param to         end of range (inclusive)
	 * @return candles in chronological order
	 */
	public List<Candle> findBetween(String instrument, Timeframe timeframe,
			java.time.Instant from, java.time.Instant to) {
		return jdbc.query(
				"SELECT time, instrument, timeframe, open, high, low, close, volume "
						+ "FROM candles "
						+ "WHERE instrument = ? AND timeframe = ? "
						+ "AND time >= ? AND time <= ? "
						+ "ORDER BY time ASC",
				(rs, rowNum) -> new Candle(
						rs.getTimestamp("time").toInstant(),
						rs.getString("instrument"),
						Timeframe.valueOf(rs.getString("timeframe")),
						rs.getLong("open"),
						rs.getLong("high"),
						rs.getLong("low"),
						rs.getLong("close"),
						rs.getLong("volume")),
				instrument,
				timeframe.name(),
				Timestamp.from(from),
				Timestamp.from(to));
	}
}
