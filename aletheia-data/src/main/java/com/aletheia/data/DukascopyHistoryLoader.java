package com.aletheia.data;

import com.aletheia.core.Tick;
import org.apache.commons.compress.compressors.lzma.LZMACompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Downloads historical tick data from Dukascopy and loads it into TimescaleDB.
 *
 * HOW DUKASCOPY DATA IS ORGANISED:
 * Each file contains one hour of ticks for one instrument.
 * The URL pattern is:
 * https://datafeed.dukascopy.com/datafeed/EURUSD/2023/00/15/09h_ticks.bi5
 * │ │ │ │ │
 * │ │ │ │ └── hour (00-23)
 * │ │ │ └── day (01-31)
 * │ │ └── month (00-11, ZERO-INDEXED!)
 * │ └── year
 * └── instrument (no underscore)
 *
 * IMPORTANT: Months are zero-indexed. January = 00, December = 11.
 *
 * Each .bi5 file is LZMA compressed. After decompression, each tick
 * is 20 bytes of binary data (parsed by Bi5TickParser).
 *
 * RATE LIMITING:
 * Dukascopy rate-limits downloads per IP. We add a small delay between
 * requests to avoid being blocked. If a download fails, we skip that
 * hour and continue — some hours have no data (weekends, holidays).
 *
 * USAGE:
 * loader.load("EUR_USD", LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 31));
 * // Downloads all January 2023 EUR/USD ticks and inserts into TimescaleDB
 */
public class DukascopyHistoryLoader {

	private static final String BASE_URL = "https://datafeed.dukascopy.com/datafeed";

	private final Bi5TickParser parser;
	private final TickRepository tickRepository;
	private final HttpClient httpClient;

	// Counters for progress reporting
	private long totalTicksLoaded = 0;
	private int filesDownloaded = 0;
	private int filesFailed = 0;

	public DukascopyHistoryLoader(TickRepository tickRepository) {
		this.parser = new Bi5TickParser();
		this.tickRepository = tickRepository;
		this.httpClient = HttpClient.newHttpClient();
	}

	/**
	 * Downloads and loads tick data for the given instrument and date range.
	 *
	 * Iterates day by day, hour by hour, downloading each bi5 file,
	 * decompressing it, parsing the ticks, and inserting into the database.
	 *
	 * @param instrument our instrument name e.g. "EUR_USD"
	 * @param startDate  first day to download (inclusive)
	 * @param endDate    last day to download (inclusive)
	 */
	public void load(String instrument, LocalDate startDate, LocalDate endDate) {
		String symbol = Bi5TickParser.toDukascopySymbol(instrument);
		// "EUR_USD" → "EURUSD"

		System.out.println("========================================================");
		System.out.println("  Dukascopy Historical Data Loader");
		System.out.println("  Instrument: " + instrument + " (" + symbol + ")");
		System.out.println("  Range: " + startDate + " to " + endDate);
		System.out.println("========================================================");

		// Loop through every day in the range
		LocalDate current = startDate;
		while (!current.isAfter(endDate)) {

			// Skip weekends — forex markets are closed Saturday and Sunday
			if (current.getDayOfWeek().getValue() >= 6) {
				// 6 = Saturday, 7 = Sunday
				current = current.plusDays(1);
				continue;
			}

			System.out.print("  " + current + " ... ");
			int dayTicks = 0;

			// Loop through every hour (0-23)
			for (int hour = 0; hour < 24; hour++) {
				try {
					List<Tick> ticks = downloadAndParseHour(
							symbol, instrument, current, hour);

					// Feed each tick to the repository (buffers internally)
					for (Tick tick : ticks) {
						tickRepository.onTick(tick);
					}

					dayTicks += ticks.size();
					filesDownloaded++;

				} catch (Exception e) {
					// Some hours have no data (weekends, holidays, market close)
					// This is normal — skip and continue
					filesFailed++;
				}

				// Small delay between requests to avoid rate limiting
				sleep(100);
			}

			// Flush any remaining ticks in the buffer to the database
			tickRepository.flush();

			totalTicksLoaded += dayTicks;
			System.out.println(dayTicks + " ticks (total: " + totalTicksLoaded + ")");

			current = current.plusDays(1);
		}

		// Final flush to make sure nothing is left in the buffer
		tickRepository.flush();

		System.out.println("========================================================");
		System.out.println("  Complete!");
		System.out.println("  Files downloaded: " + filesDownloaded);
		System.out.println("  Files skipped:    " + filesFailed);
		System.out.println("  Total ticks:      " + totalTicksLoaded);
		System.out.println("=========================================================");
	}

	/**
	 * Downloads one hour's bi5 file, decompresses it, and parses into ticks.
	 *
	 * @param symbol     Dukascopy symbol e.g. "EURUSD"
	 * @param instrument our instrument name e.g. "EUR_USD"
	 * @param date       the date
	 * @param hour       the hour (0-23)
	 * @return list of parsed ticks for that hour
	 */
	private List<Tick> downloadAndParseHour(String symbol, String instrument,
			LocalDate date, int hour)
			throws Exception {

		// Build the URL
		// Month is ZERO-INDEXED: January → 00, February → 01, etc.
		String url = String.format("%s/%s/%d/%02d/%02d/%02dh_ticks.bi5",
				BASE_URL,
				symbol,
				date.getYear(),
				date.getMonthValue() - 1, // zero-indexed!
				date.getDayOfMonth(),
				hour);

		// Download the compressed file
		byte[] compressed = download(url);

		if (compressed == null || compressed.length == 0) {
			return List.of(); // empty file — no ticks this hour
		}

		// Decompress LZMA → raw binary
		byte[] decompressed = decompress(compressed);

		// Calculate the start time of this hour
		// e.g. 2023-01-15, hour 9 → 2023-01-15T09:00:00Z
		ZonedDateTime hourStartZdt = date.atStartOfDay(ZoneOffset.UTC).plusHours(hour);
		Instant hourStart = hourStartZdt.toInstant();

		// Parse the raw binary into Tick records
		return parser.parse(decompressed, instrument, hourStart);
	}

	/**
	 * Downloads a URL and returns the raw bytes.
	 *
	 * Uses Java 11's built-in HttpClient — no external library needed.
	 * Returns null if the server returns 404 (no data for this hour).
	 */
	private byte[] download(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(url))
				.header("User-Agent", "Aletheia-HFT/1.0")
				.GET()
				.build();

		HttpResponse<byte[]> response = httpClient.send(
				request, HttpResponse.BodyHandlers.ofByteArray());

		if (response.statusCode() == 404) {
			return null; // no data for this hour — normal
		}

		if (response.statusCode() != 200) {
			throw new RuntimeException("HTTP " + response.statusCode() + " for " + url);
		}

		return response.body();
	}

	/**
	 * Decompresses LZMA-compressed data.
	 *
	 * LZMA (Lempel-Ziv-Markov chain Algorithm) is a compression algorithm.
	 * Dukascopy uses it because tick data compresses extremely well — the
	 * same price repeated with tiny variations is very predictable for a
	 * compressor. A 1MB compressed file might expand to 10MB of raw ticks.
	 *
	 * Apache Commons Compress handles the decompression — we just
	 * pipe the compressed bytes through an LZMACompressorInputStream
	 * and read the decompressed output.
	 */
	private byte[] decompress(byte[] compressed) throws Exception {
		try (InputStream compressedStream = new ByteArrayInputStream(compressed);
				LZMACompressorInputStream lzmaStream = new LZMACompressorInputStream(compressedStream);
				ByteArrayOutputStream output = new ByteArrayOutputStream()) {

			// Read decompressed bytes in 4KB chunks
			byte[] buffer = new byte[4096];
			int bytesRead;
			while ((bytesRead = lzmaStream.read(buffer)) != -1) {
				output.write(buffer, 0, bytesRead);
			}

			return output.toByteArray();
		}
	}

	/**
	 * Sleep without throwing a checked exception.
	 * Used for rate-limit delays between downloads.
	 */
	private void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Total ticks loaded across all calls to load(). */
	public long totalTicksLoaded() {
		return totalTicksLoaded;
	}
}
