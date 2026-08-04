package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.ManagedOrder;
import com.aletheia.execution.OrderManager;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for monitoring and controlling the trading engine.
 *
 * ENDPOINTS:
 * GET /admin/status — current engine status and open positions
 * POST /admin/kill-switch — emergency shutdown
 * GET /admin/positions — list all open positions
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

	private final KillSwitch killSwitch;
	private final OrderManager orderManager;
	private final EconomicCalendarService calendarService;

	public AdminController(KillSwitch killSwitch, OrderManager orderManager,
			EconomicCalendarService calendarService) {
		this.killSwitch = killSwitch;
		this.orderManager = orderManager;
		this.calendarService = calendarService;
	}

	/**
	 * GET /admin/status
	 * Returns the current state of the trading engine.
	 */
	@GetMapping("/status")
	public ResponseEntity<Map<String, Object>> status() {
		Map<String, Object> status = new HashMap<>();
		status.put("killSwitchActive", killSwitch.isActive());
		status.put("killSwitchReason", killSwitch.reason());
		status.put("killSwitchTime", killSwitch.activatedAt());
		status.put("openPositions", orderManager.openPositionCount());
		status.put("pendingOrders", orderManager.pendingOrders().size());
		status.put("totalOrders", orderManager.allOrders().size());
		return ResponseEntity.ok(status);
	}

	/**
	 * POST /admin/kill-switch
	 * Activates the emergency kill switch.
	 * Cancels all pending orders and closes all positions immediately.
	 *
	 * Usage: curl -X POST http://localhost:8080/admin/kill-switch?reason=manual
	 */
	@PostMapping("/kill-switch")
	public ResponseEntity<Map<String, Object>> activateKillSwitch(
			@RequestParam(name = "reason", defaultValue = "Manual activation") String reason) {

		boolean activated = killSwitch.activate(reason);

		Map<String, Object> response = new HashMap<>();
		response.put("activated", activated);
		response.put("reason", reason);
		response.put("message", activated
				? "Kill switch activated. All positions closed."
				: "Kill switch was already active.");

		return ResponseEntity.ok(response);
	}

	/**
	 * GET /admin/positions
	 * Returns details of all open positions.
	 */
	@GetMapping("/positions")
	public ResponseEntity<List<Map<String, Object>>> positions() {
		List<Map<String, Object>> positions = orderManager.openPositions().stream()
				.map(this::orderToMap)
				.toList();
		return ResponseEntity.ok(positions);
	}

	private Map<String, Object> orderToMap(ManagedOrder order) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", order.id());
		map.put("instrument", order.instrument());
		map.put("direction", order.direction());
		map.put("state", order.state());
		map.put("entryPrice", order.entryPrice());
		map.put("stopLoss", order.currentSl());
		map.put("tp1", order.tp1());
		map.put("tp2", order.tp2());
		map.put("totalUnits", order.totalUnits());
		map.put("remainingUnits", order.remainingUnits());
		map.put("grade", order.grade());
		map.put("killzone", order.killzone());
		map.put("pnl", order.realisedPnl());
		return map;
	}

	/**
	 * GET /admin/calendar
	 * Shows what's loaded and the next upcoming high-impact event per instrument,
	 * with UTC times so you can sanity-check timezone alignment.
	 */
	@GetMapping("/calendar")
	public ResponseEntity<Map<String, Object>> calendar() {
		Map<String, Object> out = new HashMap<>();
		out.put("cacheSize", calendarService.cacheSize());
		out.put("nowUtc", Instant.now().toString());

		List<String> all = calendarService.allEvents().stream()
				.map(e -> e.scheduledTime() + " | " + e.currency()
						+ " | " + e.impact() + " | " + e.eventName())
				.toList();
		out.put("events", all);
		return ResponseEntity.ok(out);
	}
}
