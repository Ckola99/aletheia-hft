package com.aletheia.data;

import com.aletheia.core.Tick;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists ticks to the TimescaleDB 'ticks' table.
 *
 * DESIGN DECISIONS:
 *
 * 1. BATCH INSERTS — not one INSERT per tick.
 * At 20-50 ticks/second, individual INSERTs would mean 20-50 database
 * round trips per second. Each round trip takes ~1-5ms (network to DB
 * and back). That's 100-250ms of every second spent waiting on the DB.
 *
 * Instead, we buffer ticks in memory and flush them in one batch INSERT
 * every N ticks or every M seconds (whichever comes first). One round
 * trip to insert 100 ticks is the same cost as inserting 1.
 *
 * 2. IMPLEMENTS TickListener — plugs directly into the streaming pipeline.
 * stream.addListener(tickRepository) and ticks are automatically saved.
 *
 * 3. THREAD SAFETY — the buffer uses synchronized access because the
 * streaming thread adds ticks and the flush timer reads them.
 */
public class TickRepository implements TickListener {

	private final JdbcOperations jdbc;
	private final int batchSize;

	// Buffer: ticks accumulate here until we flush to the database
	private final List<Tick> buffer;

	// SQL for batch insert — $1, $2, etc are positional parameters
	private static final String INSERT_SQL = "INSERT INTO ticks (time, instrument, bid, ask) VALUES (?, ?, ?, ?)";

	/**
	 * @param jdbc      Spring's JdbcTemplate — manages DB connections for us
	 * @param batchSize how many ticks to buffer before flushing to DB
	 *                  (e.g. 100 means: every 100 ticks, do one batch INSERT)
	 */
	public TickRepository(JdbcOperations jdbc, int batchSize) {
		this.jdbc = jdbc;
		this.batchSize = batchSize;
		this.buffer = new ArrayList<>(batchSize);
	}

	/**
	 * Called on every tick from the streaming thread.
	 * Adds the tick to the buffer. When the buffer is full, flushes to DB.
	 */
	@Override
	public synchronized void onTick(Tick tick) {
		buffer.add(tick);

		if (buffer.size() >= batchSize) {
			flush();
		}
	}

	/**
	 * Writes all buffered ticks to the database in one batch operation.
	 *
	 * WHAT IS batchUpdate?
	 * Instead of executing the INSERT statement 100 times (100 round trips),
	 * batchUpdate sends all 100 INSERTs in a single network message to the
	 * database. The database executes them all and responds once.
	 * 100 inserts, 1 round trip.
	 *
	 * The lambda (ps -> { ... }) is called once per tick in the batch.
	 * It sets the parameter values (?, ?, ?, ?) for each INSERT.
	 */
	public synchronized void flush() {
		if (buffer.isEmpty()) {
			return;
		}

		List<Tick> toFlush = new ArrayList<>(buffer);
		buffer.clear();

		if (jdbc == null) {
			return; // test mode — no database
		}

		jdbc.batchUpdate(INSERT_SQL, toFlush, toFlush.size(),
				(ps, tick) -> {
					ps.setTimestamp(1, Timestamp.from(tick.time()));
					ps.setString(2, tick.instrument());
					ps.setLong(3, tick.bid());
					ps.setLong(4, tick.ask());
				});
	}

	/**
	 * Clears the buffer without writing to the database.
	 * Used by test subclasses that override flush().
	 */
	protected synchronized void clearBuffer() {
		buffer.clear();
	}

	/**
	 * Returns how many ticks are currently buffered (not yet flushed).
	 * Useful for monitoring and testing.
	 */
	public synchronized int bufferSize() {
		return buffer.size();
	}
}
