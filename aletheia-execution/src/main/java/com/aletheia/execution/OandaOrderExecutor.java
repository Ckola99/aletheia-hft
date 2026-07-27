package com.aletheia.execution;

import com.aletheia.core.MarketBias;
import com.aletheia.core.PriceScale;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends orders to OANDA's REST API.
 *
 * OANDA REST API v3 endpoints used:
 * POST /accounts/{id}/orders — create a new order
 * PUT /accounts/{id}/trades/{id}/close — close a trade (full or partial)
 * PUT /accounts/{id}/trades/{id}/orders — modify SL/TP on an open trade
 * GET /accounts/{id}/summary — get account balance
 *
 * CIRCUIT BREAKER:
 * If OANDA fails 3 times in 60 seconds, the executor stops sending orders.
 * This prevents hammering a broken API and protects against runaway
 * order placement. The circuit resets after 60 seconds of no failures.
 *
 * We implement a simple manual circuit breaker here instead of Resilience4j
 * to keep dependencies minimal and the logic transparent.
 */
public class OandaOrderExecutor {

	private final String apiKey;
	private final String accountId;
	private final String baseUrl;
	private final OkHttpClient httpClient;
	private final ObjectMapper mapper;

	// Circuit breaker state
	private final AtomicInteger failureCount = new AtomicInteger(0);
	private volatile long lastFailureTime = 0;
	private volatile boolean circuitOpen = false;

	private static final int FAILURE_THRESHOLD = 3;
	private static final long CIRCUIT_RESET_MS = 60_000; // 60 seconds
	private static final MediaType JSON = MediaType.get("application/json");

	public OandaOrderExecutor(String apiKey, String accountId, String baseUrl) {
		this.apiKey = apiKey;
		this.accountId = accountId;
		this.baseUrl = baseUrl;
		this.httpClient = new OkHttpClient();
		this.mapper = new ObjectMapper();
	}

	/**
	 * Places a limit order on OANDA.
	 *
	 * A limit order says: "buy/sell when price reaches this level."
	 * The entry FVG midpoint is the limit price.
	 *
	 * @param order the managed order with entry, SL, TP details
	 * @return the OANDA order ID if successful, empty if failed
	 */
	public Optional<String> placeLimitOrder(ManagedOrder order) {
		if (isCircuitOpen()) {
			System.err.println("[OandaOrderExecutor] Circuit OPEN -- order blocked");
			return Optional.empty();
		}

		String direction = order.direction() == MarketBias.BULLISH ? "1" : "-1";
		long units = order.totalUnits();
		String signedUnits = direction.equals("-1")
				? "-" + units
				: String.valueOf(units);

		String instrument = order.instrument();
		String price = formatPrice(order.entryPrice(), instrument);
		String slPrice = formatPrice(order.currentSl(), instrument);
		String tpPrice = formatPrice(order.tp1(), instrument);

		String json = """
				{
				    "order": {
				        "type": "LIMIT",
				        "instrument": "%s",
				        "units": "%s",
				        "price": "%s",
				        "timeInForce": "GTC",
				        "stopLossOnFill": {
				            "price": "%s"
				        },
				        "takeProfitOnFill": {
				            "price": "%s"
				        }
				    }
				}
				""".formatted(instrument, signedUnits, price, slPrice, tpPrice);

		return executePost("/accounts/" + accountId + "/orders", json)
				.map(node -> {
					String orderId = node.path("orderCreateTransaction")
							.path("id").asText();
					System.out.println("[OandaOrderExecutor] Order placed: " + orderId);
					return orderId;
				});
	}

	/**
	 * Closes a trade partially or fully.
	 *
	 * @param tradeId the OANDA trade ID
	 * @param units   number of units to close (use "ALL" string for full close)
	 * @return true if successful
	 */
	public boolean closeTrade(String tradeId, long units) {
		if (isCircuitOpen()) {
			System.err.println("[OandaOrderExecutor] Circuit OPEN -- close blocked");
			return false;
		}

		String json = """
				{
				    "units": "%d"
				}
				""".formatted(units);

		String endpoint = "/accounts/" + accountId + "/trades/" + tradeId + "/close";
		return executePut(endpoint, json).isPresent();
	}

	/**
	 * Closes a trade fully.
	 */
	public boolean closeTradeAll(String tradeId) {
		if (isCircuitOpen()) {
			System.err.println("[OandaOrderExecutor] Circuit OPEN -- close blocked");
			return false;
		}

		String json = """
				{
				    "units": "ALL"
				}
				""";

		String endpoint = "/accounts/" + accountId + "/trades/" + tradeId + "/close";
		return executePut(endpoint, json).isPresent();
	}

	/**
	 * Modifies the SL on an open trade (e.g. move to breakeven after TP1).
	 */
	public boolean modifyStopLoss(String tradeId, long newSlPrice, String instrument) {
		if (isCircuitOpen()) {
			return false;
		}

		String price = formatPrice(newSlPrice, instrument);
		String json = """
				{
				    "stopLoss": {
				        "price": "%s"
				    }
				}
				""".formatted(price);

		String endpoint = "/accounts/" + accountId + "/trades/" + tradeId + "/orders";
		return executePut(endpoint, json).isPresent();
	}

	/**
	 * Gets the current account balance.
	 * Used by RiskManager before position sizing.
	 */
	public Optional<Double> getAccountBalance() {
		return executeGet("/accounts/" + accountId + "/summary")
				.map(node -> node.path("account").path("balance").asDouble());
	}

	/**
	 * Cancels a pending order.
	 */
	public boolean cancelOrder(String orderId) {
		if (isCircuitOpen())
			return false;

		String endpoint = "/accounts/" + accountId + "/orders/" + orderId + "/cancel";
		return executePut(endpoint, "{}").isPresent();
	}

	// -- Circuit Breaker Logic -----------------------------------------

	/**
	 * Returns true if the circuit is open (API calls should be blocked).
	 * The circuit resets after CIRCUIT_RESET_MS with no new failures.
	 */
	public boolean isCircuitOpen() {
		if (!circuitOpen)
			return false;

		// Check if enough time has passed to reset
		if (System.currentTimeMillis() - lastFailureTime > CIRCUIT_RESET_MS) {
			circuitOpen = false;
			failureCount.set(0);
			System.out.println("[OandaOrderExecutor] Circuit CLOSED -- reset after timeout");
			return false;
		}

		return true;
	}

	private void recordFailure() {
		lastFailureTime = System.currentTimeMillis();
		int count = failureCount.incrementAndGet();
		if (count >= FAILURE_THRESHOLD) {
			circuitOpen = true;
			System.err.println("[OandaOrderExecutor] Circuit OPENED -- "
					+ count + " failures in " + CIRCUIT_RESET_MS + "ms");
		}
	}

	private void recordSuccess() {
		failureCount.set(0);
	}

	// -- HTTP Methods --------------------------------------------------

	private Optional<JsonNode> executePost(String endpoint, String json) {
		Request request = new Request.Builder()
				.url(baseUrl + endpoint)
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.post(RequestBody.create(json, JSON))
				.build();

		return executeRequest(request);
	}

	private Optional<JsonNode> executePut(String endpoint, String json) {
		Request request = new Request.Builder()
				.url(baseUrl + endpoint)
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.put(RequestBody.create(json, JSON))
				.build();

		return executeRequest(request);
	}

	private Optional<JsonNode> executeGet(String endpoint) {
		Request request = new Request.Builder()
				.url(baseUrl + endpoint)
				.header("Authorization", "Bearer " + apiKey)
				.get()
				.build();

		return executeRequest(request);
	}

	private Optional<JsonNode> executeRequest(Request request) {
		try (Response response = httpClient.newCall(request).execute()) {
			String body = response.body() != null ? response.body().string() : "";

			if (!response.isSuccessful()) {
				System.err.println("[OandaOrderExecutor] API error: "
						+ response.code() + " " + body);
				recordFailure();
				return Optional.empty();
			}

			recordSuccess();
			return Optional.of(mapper.readTree(body));

		} catch (IOException e) {
			System.err.println("[OandaOrderExecutor] Request failed: " + e.getMessage());
			recordFailure();
			return Optional.empty();
		}
	}

	// -- Utility -------------------------------------------------------

	/**
	 * Converts a scaled long price to OANDA's string format.
	 * EUR/USD: 108300 → "1.08300"
	 */
	private String formatPrice(long scaledPrice, String instrument) {
		double price = PriceScale.toDouble(scaledPrice, instrument);
		int decimals = instrument.contains("JPY") ? 3 : 5;
		return String.format("%." + decimals + "f", price);
	}
}
