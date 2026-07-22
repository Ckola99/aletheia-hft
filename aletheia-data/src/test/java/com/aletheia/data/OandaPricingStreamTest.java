package com.aletheia.data;

import com.aletheia.core.Tick;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Live integration test for OandaPricingStream.
 * Only runs when OANDA_API_KEY is set (skipped in CI).
 */
class OandaPricingStreamTest {

	@Test
	@EnabledIfEnvironmentVariable(named = "OANDA_API_KEY", matches = ".+")
	void receives_ticks_from_live_stream() throws Exception {

		OandaConfig config = OandaConfig.fromEnv();

		// Thread-safe list to collect ticks from the streaming thread
		List<Tick> received = Collections.synchronizedList(new ArrayList<>());

		OandaPricingStream stream = new OandaPricingStream(
				config, "EUR_USD", "GBP_USD");

		// Register a listener that collects ticks into our list
		stream.addListener(received::add);

		// the above is identical to writing this out fully:
		// stream.addListener(new TickListener() {
		// 	@Override
		// 	public void onTick(Tick tick) {
		// 		received.add(tick);
		// 	}
		// });

		// Which is also identical to this lambda:
		// stream.addListener(tick -> received.add(tick));

		// Start streaming (returns immediately — runs on background thread)
		stream.start();
		assertThat(stream.isRunning()).isTrue();

		// Wait up to 15 seconds for at least 5 ticks
		// Markets move fast during London session — should take < 3 seconds
		long deadline = System.currentTimeMillis() + 15_000;
		while (received.size() < 5 && System.currentTimeMillis() < deadline) {
			Thread.sleep(500);
		}

		// Stop the stream
		stream.stop();
		assertThat(stream.isRunning()).isFalse();

		// Verify we got real ticks
		System.out.println("Received " + received.size() + " ticks");
		assertThat(received).hasSizeGreaterThanOrEqualTo(5);

		// Verify the ticks have sensible data
		for (Tick tick : received) {
			System.out.println("  " + tick.instrument()
					+ " bid=" + tick.bid()
					+ " ask=" + tick.ask()
					+ " spread=" + tick.spread());

			assertThat(tick.instrument()).isIn("EUR_USD", "GBP_USD");
			assertThat(tick.bid()).isGreaterThan(0);
			assertThat(tick.ask()).isGreaterThan(tick.bid()); // ask always > bid
			assertThat(tick.time()).isNotNull();
		}

		System.out.println("Total tick count: " + stream.tickCount());
	}
}
