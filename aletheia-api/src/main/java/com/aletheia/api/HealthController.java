package com.aletheia.api;

import com.aletheia.data.PricingStream;
import com.aletheia.execution.KillSwitch;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Health check endpoint.
 *
 * GET /health — returns system health status
 * Used by Docker healthcheck and AWS ALB health checks.
 */
@RestController
public class HealthController {

	private final PricingStream pricingStream;
	private final KillSwitch killSwitch;

	public HealthController(PricingStream pricingStream, KillSwitch killSwitch) {
		this.pricingStream = pricingStream;
		this.killSwitch = killSwitch;
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, Object>> health() {
		Map<String, Object> health = new HashMap<>();
		health.put("status", "UP");
		health.put("streamRunning", pricingStream.isRunning());
		health.put("tickCount", pricingStream.tickCount());
		health.put("killSwitchActive", killSwitch.isActive());
		return ResponseEntity.ok(health);
	}
}
