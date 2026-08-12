package com.aletheia.api;

import com.aletheia.data.OandaPricingStream;
import com.aletheia.data.TickRepository;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.OrderExpiryService;

import java.time.Instant;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import com.aletheia.data.PricingStream;

/**
 * Starts the OANDA pricing stream when the application boots.
 *
 * WHAT IS CommandLineRunner?
 * A Spring interface with one method: run().
 * Spring calls run() automatically after the application context
 * is fully initialised — all beans created, all wiring done.
 * This is the right place to start the stream because everything
 * is ready.
 *
 * WHAT IS @Profile("!test")?
 * This means: "only create this bean when the 'test' profile is NOT active."
 * During tests (mvnw test -Dspring.profiles.active=test), we do NOT want
 * the stream to start and connect to OANDA. Tests should be fast and
 * not depend on external services.
 *
 * WHAT IS @PreDestroy?
 * Spring calls methods marked with @PreDestroy when the application
 * is shutting down (Ctrl+C, or server stop). We use it to cleanly
 * stop the stream and flush any remaining buffered ticks.
 */
@Component
@Profile("!test")
public class TradingEngineRunner implements CommandLineRunner {

	private final PricingStream pricingStream;
	private final TickRepository tickRepository;
	private final OrderExpiryService orderExpiryService;
	private final KillSwitch killSwitch;

	/**
	 * Spring injects the beans we created in TradingEngineConfig.
	 * This is CONSTRUCTOR INJECTION — the recommended way to inject
	 * dependencies in Spring. The parameters match beans by type.
	 */
	public TradingEngineRunner(OandaPricingStream pricingStream,
			TickRepository tickRepository,
			OrderExpiryService orderExpiryService,
			KillSwitch killSwitch) {
		this.pricingStream = pricingStream;
		this.tickRepository = tickRepository;
		this.orderExpiryService = orderExpiryService;
		this.killSwitch = killSwitch;
	}

	/**
	 * Called automatically by Spring after startup.
	 * Starts the OANDA pricing stream.
	 */
	@Override
	public void run(String... args) {
		System.out.println("===================================================");
		System.out.println("  Aletheia Trading Engine Starting");
		System.out.println("===================================================");

		pricingStream.start();

		System.out.println("  Stream started. Ticks are flowing.");
		System.out.println("  Press Ctrl+C to stop.");
		System.out.println("==================================================");
	}

	/**
	 * Checks for expired pending orders every 60 seconds.
	 * Orders placed during a killzone that has ended are cancelled.
	 */
	@Scheduled(fixedDelayString = "${trading.order-expiry-check-seconds:60}000")
	public void checkOrderExpiry() {
		if (!killSwitch.isActive()) {
			orderExpiryService.checkAndExpire(Instant.now());
		}
	}

	/**
	 * Called automatically by Spring on shutdown (Ctrl+C).
	 * Stops the stream and flushes any remaining ticks to the database.
	 */
	@PreDestroy
	public void shutdown() {
		System.out.println("\n[TradingEngineRunner] Shutting down...");

		// Stop the stream first — no more ticks arrive
		pricingStream.stop();

		// Flush any remaining buffered ticks to the database
		tickRepository.flush();

		System.out.println("[TradingEngineRunner] Shutdown complete.");
	}
}
