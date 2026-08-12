package com.aletheia.data;

import com.aletheia.core.Tick;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persistent streaming connection to OANDA's pricing API.
 *
 * HOW IT WORKS:
 * 1. Opens one HTTP connection to OANDA
 * 2. OANDA sends price ticks as individual JSON lines continuously
 * 3. We read each line, parse it into a Tick, notify all listeners
 * 4. If the connection drops, we wait and reconnect automatically
 * 5. This runs on its own thread — start() returns immediately
 *
 * RECONNECTION STRATEGY (Exponential Backoff):
 * On first failure: wait 1 second, then retry
 * On second failure: wait 2 seconds
 * On third failure: wait 4 seconds
 * On fourth failure: wait 8 seconds
 * ...doubling each time up to a maximum of 60 seconds.
 * On successful reconnection: reset the wait back to 1 second.
 *
 * WHY exponential backoff?
 * If OANDA's server is down, hammering it with retries every 100ms
 * makes the problem worse and might get your API key blocked.
 * Backing off gives the server time to recover.
 *
 * THREAD SAFETY:
 * - listeners is a CopyOnWriteArrayList: safe to iterate while another
 * thread adds a listener (rare operation, so copy-on-write is fine)
 * - running is an AtomicBoolean: safe to read from the main thread
 * while the streaming thread writes to it
 * - OkHttpClient is thread-safe by design
 */

public class OandaPricingStream implements PricingStream{

	private final OandaConfig config;
	private final String[] instruments;
	private final OandaTickParser parser;
	private final OkHttpClient httpClient;

	// Thread-safe list: listeners can be added from any thread
	private final List<TickListener> listeners = new CopyOnWriteArrayList<>();

	// Controls the streaming loop — set to false to stop cleanly
	private final AtomicBoolean running = new AtomicBoolean(false);

	// The thread that runs the streaming loop
	private Thread streamThread;

	// Reconnection backoff settings
	private static final long INITIAL_BACKOFF_MS = 1_000; // 1 second
	private static final long MAX_BACKOFF_MS = 60_000; // 60 seconds
	private static final double BACKOFF_MULTIPLIER = 2.0;

	// Tick counter — useful for monitoring
	private long tickCount = 0;

	public OandaPricingStream(OandaConfig config, String... instruments) {
		this.config = config;
		this.instruments = instruments;
		this.parser = new OandaTickParser();

		// OkHttp client configured for streaming:
		// - No read timeout (connection stays open for hours/days)
		// - Connect timeout of 30 seconds (give OANDA time to respond)
		this.httpClient = new OkHttpClient.Builder()
				.readTimeout(0, TimeUnit.SECONDS)
				.connectTimeout(30, TimeUnit.SECONDS)
				.build();
	}

	/**
	 * Register a listener that will be called on every parsed tick.
	 * Can be called before or after start().
	 */
	public void addListener(TickListener listener) {
		listeners.add(listener);
	}

	/**
	 * Start the streaming connection on a background thread.
	 * This method returns immediately — it does not block.
	 *
	 * The background thread runs an infinite loop:
	 * connect → read ticks → on failure → wait → reconnect → repeat
	 */
	public void start() {
		if (running.getAndSet(true)) {
			// Already running — don't start a second thread
			return;
		}

		streamThread = new Thread(this::streamLoop, "oanda-pricing-stream");
		// Daemon thread: JVM can exit even if this thread is still running
		// Without daemon=true, your app would hang on shutdown waiting
		// for this infinite loop to finish (which it never does)
		streamThread.setDaemon(true);
		streamThread.start();

		System.out.println("[OandaPricingStream] Started streaming: "
				+ String.join(", ", instruments));
	}

	/**
	 * Stop the streaming connection cleanly.
	 * The current read operation will complete, then the loop exits.
	 */
	public void stop() {
		running.set(false);
		if (streamThread != null) {
			streamThread.interrupt();
		}
		System.out.println("[OandaPricingStream] Stopped. Total ticks received: "
				+ tickCount);
	}

	/**
	 * Returns true if the stream is currently running.
	 */
	public boolean isRunning() {
		return running.get();
	}

	/**
	 * Returns the total number of ticks received since start.
	 */
	public long tickCount() {
		return tickCount;
	}

	/**
	 * The main streaming loop. Runs on the background thread.
	 * Connects, reads ticks, reconnects on failure — forever,
	 * until stop() is called.
	 */
	private void streamLoop() {
		long backoffMs = INITIAL_BACKOFF_MS;

		while (running.get()) {
			try {
				System.out.println("[OandaPricingStream] Connecting...");
				connectAndRead();

				// If connectAndRead() returns normally (not an exception),
				// it means the connection was closed cleanly by OANDA.
				// Reset backoff and reconnect.
				backoffMs = INITIAL_BACKOFF_MS;

			} catch (InterruptedException e) {
				// stop() was called — exit the loop cleanly
				Thread.currentThread().interrupt();
				break;

			} catch (Exception e) {
				if (!running.get()) {
					// stop() was called during the connection — exit cleanly
					break;
				}

				System.err.println("[OandaPricingStream] Connection failed: "
						+ e.getMessage());
				System.err.println("[OandaPricingStream] Reconnecting in "
						+ backoffMs + "ms...");

				try {
					Thread.sleep(backoffMs);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					break;
				}

				// Increase backoff for next failure (exponential)
				backoffMs = Math.min(
						(long) (backoffMs * BACKOFF_MULTIPLIER),
						MAX_BACKOFF_MS);
			}
		}

		System.out.println("[OandaPricingStream] Stream loop exited.");
	}

	/**
	 * Opens the HTTP connection and reads ticks until the connection
	 * drops or stop() is called.
	 *
	 * This method BLOCKS for the entire duration of the connection —
	 * which could be hours or days. That's why it runs on its own thread.
	 */
	private void connectAndRead() throws Exception {
		String url = config.buildStreamUrl(instruments);

		Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + config.apiKey())
				.header("Accept-Datetime-Format", "RFC3339")
				.build();

		try (Response response = httpClient.newCall(request).execute()) {

			if (!response.isSuccessful()) {
				throw new RuntimeException(
						"OANDA API error: " + response.code() + " "
								+ response.body().string());
			}

			System.out.println("[OandaPricingStream] Connected. Reading ticks...");

			// Read lines one at a time from the persistent stream
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body().byteStream()));

			String line;
			while (running.get() && (line = reader.readLine()) != null) {
				// Parse the JSON line into a Tick
				Optional<Tick> maybeTick = parser.parse(line);

				// If it was a PRICE (not a heartbeat), notify all listeners
				maybeTick.ifPresent(tick -> {
					tickCount++;
					for (TickListener listener : listeners) {
						try {
							listener.onTick(tick);
						} catch (Exception e) {
							// A listener error must NEVER crash the stream.
							// Log it and continue processing the next tick.
							System.err.println("[OandaPricingStream] Listener error: "
									+ e.getMessage());
						}
					}
				});
			}
		}
	}
}
