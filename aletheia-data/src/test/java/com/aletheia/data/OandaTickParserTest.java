package com.aletheia.data;

import com.aletheia.core.Tick;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for OandaTickParser.
 *
 * These use REAL JSON from the OANDA API — the exact format you saw
 * in the connection test. This guarantees our parser handles the
 * actual production data format, not a simplified version.
 */

class OandaTickParserTest {

	private final OandaTickParser parser = new OandaTickParser();

	// Real EUR/USD tick from your connection test
	private static final String EUR_USD_TICK = """
			{"type":"PRICE","time":"2026-07-22T07:01:01.229030006Z",\
			"bids":[{"price":"1.14108","liquidity":500000},\
			{"price":"1.14107","liquidity":500000}],\
			"asks":[{"price":"1.14123","liquidity":500000},\
			{"price":"1.14125","liquidity":2500000}],\
			"closeoutBid":"1.14099","closeoutAsk":"1.14133",\
			"status":"tradeable","tradeable":true,\
			"instrument":"EUR_USD"}""";

	// Real GBP/USD tick from your connection test
	private static final String GBP_USD_TICK = """
			{"type":"PRICE","time":"2026-07-22T07:01:00.019959715Z",\
			"bids":[{"price":"1.33793","liquidity":500000}],\
			"asks":[{"price":"1.33810","liquidity":500000}],\
			"closeoutBid":"1.33775","closeoutAsk":"1.33829",\
			"status":"tradeable","tradeable":true,\
			"instrument":"GBP_USD"}""";

	private static final String HEARTBEAT = """
			{"type":"HEARTBEAT","time":"2026-07-22T07:01:05.000000000Z"}""";

	@Test
	void parses_eurusd_tick_with_correct_scaled_prices() {
		Optional<Tick> result = parser.parse(EUR_USD_TICK);

		assertThat(result).isPresent();
		Tick tick = result.get();

		assertThat(tick.instrument()).isEqualTo("EUR_USD");
		// 1.14108 × 100,000 = 114108
		assertThat(tick.bid()).isEqualTo(114_108L);
		// 1.14123 × 100,000 = 114123
		assertThat(tick.ask()).isEqualTo(114_123L);
		// Spread = ask - bid = 114123 - 114108 = 15 (1.5 pips)
		assertThat(tick.spread()).isEqualTo(15L);
	}

	@Test
	void parses_gbpusd_tick_with_correct_scaled_prices() {
		Optional<Tick> result = parser.parse(GBP_USD_TICK);

		assertThat(result).isPresent();
		Tick tick = result.get();

		assertThat(tick.instrument()).isEqualTo("GBP_USD");
		// 1.33793 × 100,000 = 133793
		assertThat(tick.bid()).isEqualTo(133_793L);
		// 1.33810 × 100,000 = 133810
		assertThat(tick.ask()).isEqualTo(133_810L);
	}

	@Test
	void parses_timestamp_as_instant() {
		Optional<Tick> result = parser.parse(EUR_USD_TICK);

		assertThat(result).isPresent();
		Tick tick = result.get();

		// Verify the timestamp is correctly parsed
		assertThat(tick.time()).isNotNull();
		assertThat(tick.time().toString()).startsWith("2026-07-22");
	}

	@Test
	void extracts_best_bid_and_ask_from_first_array_element() {
		// OANDA sends multiple price levels (market depth)
		// We only care about bids[0] (best bid) and asks[0] (best ask)
		Optional<Tick> result = parser.parse(EUR_USD_TICK);

		Tick tick = result.get();
		// bids[0].price is "1.14108" (the best/highest bid)
		// bids[1].price is "1.14107" (worse bid — we ignore it)
		assertThat(tick.bid()).isEqualTo(114_108L); // bids[0], not bids[1]
	}

	@Test
	void returns_empty_for_heartbeat() {
		Optional<Tick> result = parser.parse(HEARTBEAT);

		assertThat(result).isEmpty();
	}

	@Test
	void returns_empty_for_garbage_input() {
		Optional<Tick> result = parser.parse("this is not json");

		assertThat(result).isEmpty();
	}

	@Test
	void returns_empty_for_empty_string() {
		Optional<Tick> result = parser.parse("");

		assertThat(result).isEmpty();
	}

	@Test
	void mid_price_is_average_of_bid_and_ask() {
		Tick tick = parser.parse(EUR_USD_TICK).get();

		// mid = (114108 + 114123) / 2 = 114115 (integer division)
		assertThat(tick.mid()).isEqualTo(114_115L);
	}
}
