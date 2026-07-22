package com.aletheia.data;

/**
 * Configuration for the OANDA API connection.
 *
 * In production this is populated by Spring Boot from application.properties.
 * In tests we construct it directly with test values.
 *
 * WHY A SEPARATE CLASS?
 * The streaming client should not read environment variables directly.
 * That would make it impossible to test — you'd need real credentials
 * in every test run. By accepting a config object, tests can pass
 * in fake values and mock the HTTP layer.
 */

public class OandaConfig {

	private final String apiKey;
	private final String accountId;
	private final String streamUrl;

	public OandaConfig(String apiKey, String accountId, String streamUrl) {
		this.apiKey = apiKey;
		this.accountId = accountId;
		this.streamUrl = streamUrl;
	}

	public String apiKey() {
		return apiKey;
	}

	public String accountId() {
		return accountId;
	}

	public String streamUrl() {
		return streamUrl;
	}

	/**
	 * Builds the full streaming URL for the given instruments.
	 *
	 * Example result:
	 * https://stream-fxpractice.oanda.com/v3/accounts/101-004-xxx/pricing/stream
	 * ?instruments=EUR_USD%2CGBP_USD%2CXAU_USD
	 *
	 * %2C is the URL encoding for comma — OANDA expects comma-separated
	 * instrument names in the query parameter.
	 */

	public String buildStreamUrl(String... instruments) {
		String instrumentParam = String.join("%2C", instruments);
		return streamUrl + "/accounts/" + accountId
				+ "/pricing/stream?instruments=" + instrumentParam;
	}

	/**
	 * Factory method: create from environment variables.
	 * Used when running the app locally with a .env file loaded.
	 */
	
	public static OandaConfig fromEnv() {
		return new OandaConfig(
				System.getenv("OANDA_API_KEY"),
				System.getenv("OANDA_ACCOUNT_ID"),
				System.getenv().getOrDefault("OANDA_STREAM_URL",
						"https://stream-fxpractice.oanda.com/v3"));
	}
}
