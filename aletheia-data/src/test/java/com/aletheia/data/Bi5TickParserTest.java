package com.aletheia.data;

import com.aletheia.core.Tick;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Bi5TickParser.
 *
 * We fabricate raw binary data matching the bi5 format
 * and verify the parser produces correct Tick records.
 */
class Bi5TickParserTest {

	private final Bi5TickParser parser = new Bi5TickParser();

	/**
	 * Helper: build a 20-byte bi5 tick record.
	 */
	private byte[] buildBi5Tick(int millisOffset, int ask, int bid,
			float askVol, float bidVol) {
		ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN);
		buf.putInt(millisOffset);
		buf.putInt(ask);
		buf.putInt(bid);
		buf.putFloat(askVol);
		buf.putFloat(bidVol);
		return buf.array();
	}

	/**
	 * Helper: concatenate multiple byte arrays.
	 */
	private byte[] concat(byte[]... arrays) {
		int total = 0;
		for (byte[] a : arrays)
			total += a.length;
		ByteBuffer buf = ByteBuffer.allocate(total);
		for (byte[] a : arrays)
			buf.put(a);
		return buf.array();
	}

	@Test
	void parses_single_eurusd_tick() {
		// Simulate one EUR/USD tick at 37.5 seconds into the hour
		// ask = 114123 → 114123 × 0.00001 = 1.14123
		// bid = 114108 → 114108 × 0.00001 = 1.14108
		byte[] data = buildBi5Tick(37500, 114123, 114108, 1.5f, 1.5f);

		Instant hourStart = Instant.parse("2023-01-15T09:00:00Z");
		List<Tick> ticks = parser.parse(data, "EUR_USD", hourStart);

		assertThat(ticks).hasSize(1);

		Tick tick = ticks.get(0);
		assertThat(tick.instrument()).isEqualTo("EUR_USD");
		assertThat(tick.ask()).isEqualTo(114123L); // 1.14123 in 100,000 scale
		assertThat(tick.bid()).isEqualTo(114108L); // 1.14108 in 100,000 scale

		// Timestamp: 09:00:00.000 + 37500ms = 09:00:37.500
		assertThat(tick.time()).isEqualTo(Instant.parse("2023-01-15T09:00:37.500Z"));
	}

	@Test
	void parses_multiple_ticks() {
		byte[] data = concat(
				buildBi5Tick(0, 114100, 114085, 1.0f, 1.0f),
				buildBi5Tick(500, 114110, 114095, 1.0f, 1.0f),
				buildBi5Tick(1000, 114120, 114105, 1.0f, 1.0f));

		Instant hourStart = Instant.parse("2023-01-15T09:00:00Z");
		List<Tick> ticks = parser.parse(data, "EUR_USD", hourStart);

		assertThat(ticks).hasSize(3);
		assertThat(ticks.get(0).ask()).isEqualTo(114100L);
		assertThat(ticks.get(1).ask()).isEqualTo(114110L);
		assertThat(ticks.get(2).ask()).isEqualTo(114120L);

		// Verify timestamps
		assertThat(ticks.get(0).time()).isEqualTo(Instant.parse("2023-01-15T09:00:00Z"));
		assertThat(ticks.get(1).time()).isEqualTo(Instant.parse("2023-01-15T09:00:00.500Z"));
		assertThat(ticks.get(2).time()).isEqualTo(Instant.parse("2023-01-15T09:00:01Z"));
	}

	@Test
	void skips_zero_price_ticks() {
		byte[] data = concat(
				buildBi5Tick(0, 114100, 114085, 1.0f, 1.0f), // valid
				buildBi5Tick(500, 0, 0, 0.0f, 0.0f), // padding — skip
				buildBi5Tick(1000, 114120, 114105, 1.0f, 1.0f) // valid
		);

		List<Tick> ticks = parser.parse(data, "EUR_USD",
				Instant.parse("2023-01-15T09:00:00Z"));

		assertThat(ticks).hasSize(2); // the zero-price tick was skipped
	}

	@Test
	void returns_empty_for_null_data() {
		List<Tick> ticks = parser.parse(null, "EUR_USD",
				Instant.parse("2023-01-15T09:00:00Z"));

		assertThat(ticks).isEmpty();
	}

	@Test
	void returns_empty_for_data_smaller_than_one_tick() {
		byte[] tooSmall = new byte[10]; // less than 20 bytes

		List<Tick> ticks = parser.parse(tooSmall, "EUR_USD",
				Instant.parse("2023-01-15T09:00:00Z"));

		assertThat(ticks).isEmpty();
	}

	@Test
	void instrument_name_conversion() {
		assertThat(Bi5TickParser.toDukascopySymbol("EUR_USD")).isEqualTo("EURUSD");
		assertThat(Bi5TickParser.toDukascopySymbol("GBP_USD")).isEqualTo("GBPUSD");
		assertThat(Bi5TickParser.toDukascopySymbol("XAU_USD")).isEqualTo("XAUUSD");

		assertThat(Bi5TickParser.toOandaInstrument("EURUSD")).isEqualTo("EUR_USD");
		assertThat(Bi5TickParser.toOandaInstrument("GBPUSD")).isEqualTo("GBP_USD");
		assertThat(Bi5TickParser.toOandaInstrument("XAUUSD")).isEqualTo("XAU_USD");
	}
}
