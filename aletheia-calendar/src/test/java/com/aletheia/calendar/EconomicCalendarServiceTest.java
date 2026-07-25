package com.aletheia.calendar;

import com.aletheia.core.EconomicEvent;
import com.aletheia.core.ImpactLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EconomicCalendarService.
 *
 * Each test loads specific events into the service and verifies
 * the blackout logic at different points in time relative to the event.
 */
class EconomicCalendarServiceTest {

	private EconomicCalendarService service;

	// NFP scheduled at 13:30 UTC
	private static final Instant NFP_TIME = Instant.parse("2026-07-22T13:30:00Z");

	// ECB Rate Decision at 12:15 UTC
	private static final Instant ECB_TIME = Instant.parse("2026-07-22T12:15:00Z");

	@BeforeEach
	void setUp() {
		service = new EconomicCalendarService();

		// Load two high-impact events
		service.loadEvents(List.of(
				new EconomicEvent(NFP_TIME, "USD", "Non-Farm Payrolls", ImpactLevel.HIGH),
				new EconomicEvent(ECB_TIME, "EUR", "ECB Rate Decision", ImpactLevel.HIGH),
				new EconomicEvent(
						Instant.parse("2026-07-22T09:00:00Z"),
						"EUR", "Trade Balance", ImpactLevel.MEDIUM)));
	}

	// ── BLACKOUT TIMING ─────────────────────────────────────────────

	@Test
	void blackout_15_minutes_before_event() {
		// NFP at 13:30. Check at 13:20 → within 15min before → BLOCKED
		Instant at1320 = Instant.parse("2026-07-22T13:20:00Z");

		assertThat(service.isNewsBlackout(at1320, "EUR_USD")).isTrue();
	}

	@Test
	void blackout_exactly_at_event_time() {
		// Right when NFP releases → BLOCKED
		assertThat(service.isNewsBlackout(NFP_TIME, "EUR_USD")).isTrue();
	}

	@Test
	void blackout_15_minutes_after_event() {
		// NFP at 13:30. Check at 13:40 → within 15min after → BLOCKED
		Instant at1340 = Instant.parse("2026-07-22T13:40:00Z");

		assertThat(service.isNewsBlackout(at1340, "EUR_USD")).isTrue();
	}

	@Test
	void no_blackout_at_boundary_edge() {
		// NFP at 13:30. Window is 13:15 to 13:45.
		// Check at 13:46 → just outside → NOT blocked
		Instant at1346 = Instant.parse("2026-07-22T13:46:00Z");

		assertThat(service.isNewsBlackout(at1346, "EUR_USD")).isFalse();
	}

	@Test
	void no_blackout_well_before_event() {
		// NFP at 13:30. Check at 12:00 → 90 minutes before → NOT blocked
		Instant at1100 = Instant.parse("2026-07-22T11:00:00Z");

		assertThat(service.isNewsBlackout(at1100, "EUR_USD")).isFalse();
	}

	// ── CURRENCY FILTERING ──────────────────────────────────────────

	@Test
	void usd_event_blocks_eurusd() {
		// NFP is a USD event. EUR/USD is affected by USD.
		assertThat(service.isNewsBlackout(NFP_TIME, "EUR_USD")).isTrue();
	}

	@Test
	void usd_event_blocks_gbpusd() {
		// NFP is USD. GBP/USD is also affected by USD.
		assertThat(service.isNewsBlackout(NFP_TIME, "GBP_USD")).isTrue();
	}

	@Test
	void usd_event_blocks_xauusd() {
		// Gold is priced in USD — affected by USD events.
		assertThat(service.isNewsBlackout(NFP_TIME, "XAU_USD")).isTrue();
	}

	@Test
	void eur_event_blocks_eurusd_but_not_gbpusd() {
		// ECB Rate Decision is a EUR event.
		// EUR/USD is affected (has EUR currency) → BLOCKED
		assertThat(service.isNewsBlackout(ECB_TIME, "EUR_USD")).isTrue();

		// GBP/USD is NOT affected (no EUR currency) → NOT blocked
		assertThat(service.isNewsBlackout(ECB_TIME, "GBP_USD")).isFalse();
	}

	// ── IMPACT LEVEL FILTERING ──────────────────────────────────────

	@Test
	void medium_impact_events_do_not_block_trading() {
		// Trade Balance is MEDIUM impact at 09:00 UTC.
		// Only HIGH impact blocks trading.
		Instant at0900 = Instant.parse("2026-07-22T09:00:00Z");

		assertThat(service.isNewsBlackout(at0900, "EUR_USD")).isFalse();
	}

	// ── NEXT EVENT ──────────────────────────────────────────────────

	@Test
	void finds_next_high_impact_event() {
		Instant beforeBoth = Instant.parse("2026-07-22T10:00:00Z");

		// ECB at 12:15 is the next high-impact event affecting EUR_USD
		Optional<EconomicEvent> next = service.nextHighImpactEvent(beforeBoth, "EUR_USD");

		assertThat(next).isPresent();
		assertThat(next.get().eventName()).isEqualTo("ECB Rate Decision");
	}

	@Test
	void next_event_skips_passed_events() {
		// After ECB but before NFP
		Instant afterEcb = Instant.parse("2026-07-22T12:30:00Z");

		Optional<EconomicEvent> next = service.nextHighImpactEvent(afterEcb, "EUR_USD");

		assertThat(next).isPresent();
		assertThat(next.get().eventName()).isEqualTo("Non-Farm Payrolls");
	}

	@Test
	void no_next_event_when_all_passed() {
		Instant afterAll = Instant.parse("2026-07-22T15:00:00Z");

		Optional<EconomicEvent> next = service.nextHighImpactEvent(afterAll, "EUR_USD");

		assertThat(next).isEmpty();
	}

	// ── CACHE MANAGEMENT ────────────────────────────────────────────

	@Test
	void cache_size_reflects_loaded_events() {
		assertThat(service.cacheSize()).isEqualTo(3);
	}

	@Test
	void empty_cache_means_no_blackout() {
		EconomicCalendarService empty = new EconomicCalendarService();

		// No events loaded → nothing can block
		assertThat(empty.isNewsBlackout(NFP_TIME, "EUR_USD")).isFalse();
		assertThat(empty.cacheSize()).isEqualTo(0);
	}

	@Test
	void loading_new_events_replaces_old_cache() {
		assertThat(service.cacheSize()).isEqualTo(3);

		// Load a completely different set of events
		service.loadEvents(List.of(
				new EconomicEvent(
						Instant.parse("2026-07-23T13:30:00Z"),
						"USD", "CPI", ImpactLevel.HIGH)));

		assertThat(service.cacheSize()).isEqualTo(1);

		// Old NFP event is gone — no blackout at its time anymore
		assertThat(service.isNewsBlackout(NFP_TIME, "EUR_USD")).isFalse();
	}
}
