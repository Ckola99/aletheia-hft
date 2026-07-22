package com.aletheia.data;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * This is NOT a unit test — it's a connectivity check.
 * It connects to the real OANDA streaming API and prints
 * raw lines so you can see exactly what the data looks like.
 *
 * It only runs when the OANDA_API_KEY environment variable is set.
 * In CI (GitHub Actions), this env var is not set, so the test is skipped.
 * This is intentional — CI tests must not depend on external services.
 *
 * To run locally:
 * On Windows Git Bash:
 * export OANDA_API_KEY=your_token_here
 * export OANDA_ACCOUNT_ID=101-004-13158301-002
 * ./mvnw test -pl aletheia-data -Dtest=OandaConnectionTest
 */

class OandaConnectionTest {

	@Test
	@EnabledIfEnvironmentVariable(named = "OANDA_API_KEY", matches = ".+")
	void printRawStreamingData() throws Exception {

		String apiKey = System.getenv("OANDA_API_KEY");
		String accountId = System.getenv("OANDA_ACCOUNT_ID");
		String streamUrl = "https://stream-fxpractice.oanda.com/v3";

		// OkHttpClient with long timeouts — streaming connections
		// stay open for hours. Default timeouts would kill the connection.
		OkHttpClient client = new OkHttpClient.Builder()
				.readTimeout(0, TimeUnit.SECONDS) // no read timeout
				.build();

		// Build the streaming request
		// instruments parameter: comma-separated list of instruments
		Request request = new Request.Builder()
				.url(streamUrl + "/accounts/" + accountId
						+ "/pricing/stream?instruments=EUR_USD%2CGBP_USD")
				.header("Authorization", "Bearer " + apiKey)
				.header("Accept-Datetime-Format", "RFC3339")
				.build();

		System.out.println("Connecting to OANDA streaming API...");
		System.out.println("URL: " + request.url());
		System.out.println("Waiting for data (will print 10 lines then stop)...");
		System.out.println("─".repeat(70));

		// Execute the request — response.body() is a stream, not a single string
		try (Response response = client.newCall(request).execute()) {

			if (!response.isSuccessful()) {
				System.out.println("ERROR: " + response.code() + " " + response.message());
				System.out.println(response.body().string());
				return;
			}

			// Read lines one at a time from the persistent connection
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body().byteStream()));

			int count = 0;
			String line;
			while ((line = reader.readLine()) != null && count < 10) {
				System.out.println("[" + count + "] " + line);
				count++;
			}
		}

		System.out.println("─".repeat(70));
		System.out.println("Done. Received " + 10 + " lines.");
	}
}
