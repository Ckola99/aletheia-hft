package com.aletheia.data;

import com.aletheia.core.PriceScale;
import com.aletheia.core.Tick;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses decompressed Dukascopy .bi5 binary data into Tick records.
 *
 * BI5 FORMAT:
 * Each tick is exactly 20 bytes, big-endian:
 * bytes 0-3: int32 — milliseconds since the start of the hour
 * bytes 4-7: int32 — ask price (integer pips)
 * bytes 8-11: int32 — bid price (integer pips)
 * bytes 12-15: float32 — ask volume (in lots)
 * bytes 16-19: float32 — bid volume (in lots)
 *
 * PRICE INTERPRETATION:
 * The integer prices are "pips from zero" — meaning for a 5-decimal-place
 * instrument like EUR/USD, a value of 114108 means 1.14108.
 * For XAU/USD (3 decimal places in Dukascopy), 2353505 means 2353.505.
 *
 * TIMESTAMP:
 * The millisecond offset is added to the hour's start time (derived from
 * the file's URL: year/month/day/hour). Dukascopy months are 0-indexed:
 * January = 0, December = 11.
 *
 * All times are UTC — no timezone conversion needed.
 */
public class Bi5TickParser {

	private static final int TICK_SIZE = 20; // bytes per tick

	/**
	 * Point value for each instrument.
	 * Multiply the raw integer by this to get the actual price.
	 *
	 * EUR/USD: 114108 × 0.00001 = 1.14108
	 * XAU/USD: 2353505 × 0.001 = 2353.505
	 */
	private static double pointValue(String instrument) {
		return switch (instrument) {
			case "EUR_USD", "GBP_USD", "AUD_USD",
					"USD_CHF", "USD_CAD" ->
				0.00001;
			case "USD_JPY" -> 0.001;
			case "XAU_USD" -> 0.001;
			case "DOLLAR_IDX" -> 0.001;
			default -> 0.00001;
		};
	}

	/**
	 * Converts a Dukascopy instrument name (no underscore) to our format.
	 * Dukascopy uses "EURUSD", we use "EUR_USD".
	 */
	public static String toOandaInstrument(String dukascopySymbol) {
		if (dukascopySymbol.equals("XAUUSD"))
			return "XAU_USD";
		if (dukascopySymbol.equals("DOLLARIDXUSD"))
			return "DOLLAR_IDX";
		if (dukascopySymbol.length() == 6) {
			return dukascopySymbol.substring(0, 3) + "_" + dukascopySymbol.substring(3);
		}
		return dukascopySymbol;
	}

	/**
	 * Converts our instrument name back to Dukascopy format for URLs.
	 * "EUR_USD" → "EURUSD"
	 */
	public static String toDukascopySymbol(String instrument) {
		if (instrument.equals("DOLLAR_IDX"))
			return "DOLLARIDXUSD";
		return instrument.replace("_", "");
	}

	/**
	 * Parses raw decompressed bi5 bytes into Tick records.
	 *
	 * @param data       the decompressed byte array
	 * @param instrument our instrument name e.g. "EUR_USD"
	 * @param hourStart  the start time of this hour file
	 *                   e.g. 2023-01-15T09:00:00Z for 09h_ticks.bi5
	 * @return list of parsed ticks, empty if data is empty or invalid
	 */
	public List<Tick> parse(byte[] data, String instrument, Instant hourStart) {
		List<Tick> ticks = new ArrayList<>();

		if (data == null || data.length < TICK_SIZE) {
			return ticks; // empty or too small — no ticks
		}

		// How many complete ticks fit in the data?
		int tickCount = data.length / TICK_SIZE;

		// Wrap the byte array for easy reading
		// BIG_ENDIAN: Dukascopy stores bytes in big-endian order
		// (most significant byte first — network byte order)
		ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);

		double pv = pointValue(instrument);

		for (int i = 0; i < tickCount; i++) {
			// Read 5 fields, each 4 bytes
			int millisOffset = buffer.getInt(); // ms since hour start
			int askRaw = buffer.getInt(); // ask in integer pips
			int bidRaw = buffer.getInt(); // bid in integer pips
			float askVol = buffer.getFloat(); // ask volume (we don't use this)
			float bidVol = buffer.getFloat(); // bid volume (we don't use this)

			// Skip ticks with zero prices — these are padding/invalid entries
			if (askRaw == 0 && bidRaw == 0) {
				continue;
			}

			// Calculate the actual timestamp
			// hourStart (e.g. 09:00:00.000Z) + millisOffset (e.g. 37500)
			// = 09:00:37.500Z
			Instant tickTime = hourStart.plusMillis(millisOffset);

			// Convert raw integer prices to actual prices, then to our scaled format
			// askRaw = 114108, pv = 0.00001
			// askPrice = 114108 × 0.00001 = 1.14108
			// PriceScale.toScaled(1.14108, "EUR_USD") = 114108
			//
			// For 5dp forex pairs this is a round-trip that returns the same number.
			// For other instruments the point value differs from our scale, so
			// the conversion is necessary.
			double askPrice = askRaw * pv;
			double bidPrice = bidRaw * pv;

			Tick tick = new Tick(
					tickTime,
					instrument,
					PriceScale.toScaled(bidPrice, instrument),
					PriceScale.toScaled(askPrice, instrument));

			ticks.add(tick);
		}

		return ticks;
	}
}
