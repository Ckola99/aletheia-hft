package com.aletheia.strategy;

import com.aletheia.core.KillzoneWindow;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for KillzoneService.
 *
 * Each test creates a time in a specific timezone and verifies
 * the correct killzone is returned.
 *
 * IMPORTANT: We test with both EST times (direct) and UTC times
 * (converted) to verify the timezone conversion works correctly.
 */
class KillzoneServiceTest {

	private final KillzoneService service = new KillzoneService();

	// Helper: create a time in EST directly
	private ZonedDateTime est(int hour, int minute) {
		return ZonedDateTime.of(2026, 7, 22, hour, minute, 0, 0,
				ZoneId.of("America/New_York"));
	}

	// Helper: create a time in UTC
	private ZonedDateTime utc(int hour, int minute) {
		return ZonedDateTime.of(2026, 7, 22, hour, minute, 0, 0,
				ZoneId.of("UTC"));
	}

	// ── LONDON OPEN (02:00 – 05:00 EST) ─────────────────────────────

	@Test
	void london_open_at_0200_EST() {
		assertThat(service.classify(est(2, 0)))
				.isEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	@Test
	void london_open_at_0330_EST() {
		assertThat(service.classify(est(3, 30)))
				.isEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	@Test
	void london_open_at_0459_EST() {
		assertThat(service.classify(est(4, 59)))
				.isEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	@Test
	void london_open_ends_at_0500_EST() {
		// 05:00 is NOT London Open — the range is [02:00, 05:00)
		assertThat(service.classify(est(5, 0)))
				.isNotEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	// ── NEW YORK OPEN (07:00 – 10:00 EST) ───────────────────────────

	@Test
	void new_york_open_at_0700_EST() {
		assertThat(service.classify(est(7, 0)))
				.isEqualTo(KillzoneWindow.NEW_YORK_OPEN);
	}

	@Test
	void new_york_open_at_0845_EST() {
		assertThat(service.classify(est(8, 45)))
				.isEqualTo(KillzoneWindow.NEW_YORK_OPEN);
	}

	@Test
	void new_york_open_at_0959_EST() {
		assertThat(service.classify(est(9, 59)))
				.isEqualTo(KillzoneWindow.NEW_YORK_OPEN);
	}

	// ── LONDON CLOSE (10:00 – 12:00 EST) ────────────────────────────

	@Test
	void london_close_at_1000_EST() {
		assertThat(service.classify(est(10, 0)))
				.isEqualTo(KillzoneWindow.LONDON_CLOSE);
	}

	@Test
	void london_close_at_1130_EST() {
		assertThat(service.classify(est(11, 30)))
				.isEqualTo(KillzoneWindow.LONDON_CLOSE);
	}

	@Test
	void london_close_ends_at_1200_EST() {
		assertThat(service.classify(est(12, 0)))
				.isEqualTo(KillzoneWindow.NONE);
	}

	// ── NONE (outside all killzones) ────────────────────────────────

	@Test
	void none_at_midnight_EST() {
		assertThat(service.classify(est(0, 0)))
				.isEqualTo(KillzoneWindow.NONE);
	}

	@Test
	void none_at_0130_EST() {
		// Between midnight and London Open
		assertThat(service.classify(est(1, 30)))
				.isEqualTo(KillzoneWindow.NONE);
	}

	@Test
	void none_at_0600_EST() {
		// Gap between London Open end and NY Open start
		assertThat(service.classify(est(6, 0)))
				.isEqualTo(KillzoneWindow.NONE);
	}

	@Test
	void none_at_1500_EST() {
		// Afternoon — no killzone
		assertThat(service.classify(est(15, 0)))
				.isEqualTo(KillzoneWindow.NONE);
	}

	// ── TIMEZONE CONVERSION ─────────────────────────────────────────

	@Test
	void converts_utc_to_est_correctly() {
		// In July, EST is actually EDT (UTC-4)
		// So 07:00 UTC = 03:00 EDT = London Open
		assertThat(service.classify(utc(7, 0)))
				.isEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	@Test
	void converts_johannesburg_time_correctly() {
		// Johannesburg is SAST (UTC+2)
		// 09:00 SAST = 07:00 UTC = 03:00 EDT = London Open
		ZonedDateTime joburg = ZonedDateTime.of(2026, 7, 22, 9, 0, 0, 0,
				ZoneId.of("Africa/Johannesburg"));

		assertThat(service.classify(joburg))
				.isEqualTo(KillzoneWindow.LONDON_OPEN);
	}

	// ── INSTANT OVERLOAD ────────────────────────────────────────────

	@Test
	void classify_from_instant_works() {
		// Create an Instant at a known UTC time
		// 11:00 UTC in July = 07:00 EDT = NY Open
		java.time.Instant instant = utc(11, 0).toInstant();

		assertThat(service.classify(instant))
				.isEqualTo(KillzoneWindow.NEW_YORK_OPEN);
	}

	@Test
	void isInKillzone_returns_true_during_session() {
		java.time.Instant londonTime = utc(7, 0).toInstant(); // 03:00 EDT
		assertThat(service.isInKillzone(londonTime)).isTrue();
	}

	@Test
	void isInKillzone_returns_false_outside_session() {
		java.time.Instant nightTime = utc(0, 0).toInstant(); // 20:00 EDT
		assertThat(service.isInKillzone(nightTime)).isFalse();
	}
}
