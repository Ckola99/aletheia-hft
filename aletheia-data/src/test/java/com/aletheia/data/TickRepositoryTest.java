package com.aletheia.data;

import com.aletheia.core.Tick;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for TickRepository buffering logic.
 *
 * Instead of mocking JdbcOperations (which Java 24 makes difficult),
 * we test with a subclass that overrides flush() to record what
 * WOULD have been flushed — no database, no mock framework needed.
 */
class TickRepositoryTest {

	/**
	 * A test version of TickRepository that captures flushed ticks
	 * instead of writing to a database.
	 *
	 * This is a common testing pattern: extend the class and override
	 * the part that touches external systems (database, network, etc.)
	 */
	static class TestableTickRepository extends TickRepository {
		final List<List<Tick>> flushedBatches = new ArrayList<>();

		TestableTickRepository(int batchSize) {
			super(null, batchSize); // null jdbc — we override flush()
		}

		@Override
		public synchronized void flush() {
			// Instead of writing to DB, capture the buffer contents
			if (bufferSize() > 0) {
				// We can't access the private buffer directly,
				// so we just record that a flush happened
				flushedBatches.add(List.of()); // placeholder
			}
			// Call a simplified clear
			clearBuffer();
		}
	}

	private Tick tick(String instrument) {
		return new Tick(Instant.now(), instrument, 114100L, 114115L);
	}

	@Test
	void buffers_ticks_until_batch_size_reached() {
		// Batch size of 3 — flush after every 3 ticks
		TickRepository repo = new TickRepository(null, 3) {
			@Override
			public synchronized void flush() {
				clearBuffer(); // just clear, don't touch DB
			}
		};

		repo.onTick(tick("EUR_USD"));
		repo.onTick(tick("EUR_USD"));
		assertThat(repo.bufferSize()).isEqualTo(2);

		// 3rd tick triggers flush → buffer clears
		repo.onTick(tick("EUR_USD"));
		assertThat(repo.bufferSize()).isEqualTo(0);
	}

	@Test
	void flush_clears_the_buffer() {
		TickRepository repo = new TickRepository(null, 100) {
			@Override
			public synchronized void flush() {
				clearBuffer();
			}
		};

		repo.onTick(tick("EUR_USD"));
		repo.onTick(tick("GBP_USD"));
		assertThat(repo.bufferSize()).isEqualTo(2);

		repo.flush();
		assertThat(repo.bufferSize()).isEqualTo(0);
	}

	@Test
	void flush_does_nothing_when_buffer_empty() {
		TickRepository repo = new TickRepository(null, 100) {
			@Override
			public synchronized void flush() {
				clearBuffer();
			}
		};

		// Should not throw
		repo.flush();
		assertThat(repo.bufferSize()).isEqualTo(0);
	}
}
