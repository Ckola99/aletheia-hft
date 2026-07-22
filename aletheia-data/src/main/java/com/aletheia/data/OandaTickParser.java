package com.aletheia.data;

import com.aletheia.core.PriceScale;
import com.aletheia.core.Tick;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;

/**
 * Parses a single line of OANDA streaming JSON into a Tick record.
 *
 * OANDA sends two types of messages:
 * PRICE — contains bid/ask prices for an instrument
 * HEARTBEAT — sent every ~5 seconds to confirm connection is alive
 *
 * We parse PRICE messages into Ticks and ignore HEARTBEATs.
 *
 * WHY Optional<Tick>?
 * Not every line produces a Tick — heartbeats don't.
 * Optional forces the caller to handle the "no tick" case explicitly
 * rather than risking a NullPointerException.
 *
 * WHAT IS ObjectMapper?
 * Jackson's main class for reading and writing JSON.
 * It is thread-safe and should be created once and reused.
 * Creating a new ObjectMapper per parse call is wasteful —
 * it allocates internal caches on construction.
 */

public class OandaTickParser {

	// Created once, reused for every parse call. Thread-safe.
	private final ObjectMapper mapper = new ObjectMapper();

	/**
	 * Parses one line of OANDA streaming JSON.
	 *
	 * @param json a single line from the OANDA pricing stream
	 * @return Optional containing a Tick if the line was a PRICE,
	 *         or empty if it was a HEARTBEAT or unparseable
	 *
	 *         EXAMPLE INPUT:
	 *         {"type":"PRICE","time":"2026-07-22T07:01:01.229Z",
	 *         "instrument":"EUR_USD",
	 *         "bids":[{"price":"1.14108","liquidity":500000},...],
	 *         "asks":[{"price":"1.14123","liquidity":500000},...]}
	 *
	 *         EXAMPLE OUTPUT:
	 *         Tick(time=2026-07-22T07:01:01.229Z, instrument="EUR_USD",
	 *         bid=114108, ask=114123)
	 */
	public Optional<Tick> parse(String json) {
		try {
			// Parse the JSON string into a tree structure
			JsonNode root = mapper.readTree(json);

			// Check the type field — only process PRICE messages
			String type = root.path("type").asText("");
			if (!"PRICE".equals(type)) {
				return Optional.empty(); // heartbeat or unknown — skip
			}

			// Extract the fields we need
			String timeStr = root.path("time").asText();
			String instrument = root.path("instrument").asText();

			// bids[0].price — the best (highest) bid
			// asks[0].price — the best (lowest) ask
			// These are strings in the JSON ("1.14108"), not numbers
			String bidStr = root.path("bids").path(0).path("price").asText();
			String askStr = root.path("asks").path(0).path("price").asText();

			// Convert string prices to doubles, then to scaled longs
			// This is the ONE place where we touch doubles —
			// immediately converted to scaled longs and never used again
			double bidDouble = Double.parseDouble(bidStr);
			double askDouble = Double.parseDouble(askStr);

			Tick tick = new Tick(
					Instant.parse(timeStr), // RFC3339 → Instant
					instrument, // "EUR_USD"
					PriceScale.toScaled(bidDouble, instrument), // 1.14108 → 114108
					PriceScale.toScaled(askDouble, instrument) // 1.14123 → 114123
			);

			return Optional.of(tick);

		} catch (Exception e) {
			// If any parsing fails, log and skip — don't crash the stream
			System.err.println("Failed to parse tick: " + e.getMessage());
			return Optional.empty();
		}
	}
}
