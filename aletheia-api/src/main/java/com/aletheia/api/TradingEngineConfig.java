package com.aletheia.api;

import com.aletheia.calendar.EconomicCalendarService;
import com.aletheia.data.CandleAggregator;
import com.aletheia.data.CandleRepository;
import com.aletheia.data.OandaConfig;
import com.aletheia.data.OandaPricingStream;
import com.aletheia.data.TickRepository;
import com.aletheia.execution.KillSwitch;
import com.aletheia.execution.OandaOrderExecutor;
import com.aletheia.execution.OrderExpiryService;
import com.aletheia.execution.OrderManager;
import com.aletheia.execution.RiskManager;
import com.aletheia.strategy.FairValueGapDetector;
import com.aletheia.strategy.JudasSwingDetector;
import com.aletheia.strategy.KillzoneService;
import com.aletheia.strategy.OrderBlockDetector;
import com.aletheia.strategy.SignalAggregator;
import com.aletheia.strategy.SmtDivergenceDetector;
import com.aletheia.strategy.SwingPointRegistry;
import com.aletheia.strategy.UsdxBiasEngine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aletheia.execution.BrokerExecutor;
import com.aletheia.data.PricingStream;

/**
 * Spring Configuration that wires the entire trading data pipeline.
 *
 * WHAT IS @Configuration?
 * It tells Spring: "this class contains @Bean methods — call them on startup
 * and register the returned objects as beans (shared instances)."
 *
 * WHAT IS A BEAN?
 * A bean is an object that Spring creates and manages for you.
 * Instead of you writing 'new CandleAggregator()' in 10 different places,
 * Spring creates ONE instance and gives it to anyone who needs it.
 *
 * WHAT IS @Value?
 * It injects a value from application.properties into the parameter.
 * @Value("${oanda.api-key}") reads the 'oanda.api-key' property.
 *
 * WHAT IS @Bean?
 * It marks a method whose return value should be registered as a bean.
 * Spring calls these methods automatically on startup.
 * If one @Bean method has parameters, Spring fills them with other beans.
 */

@Configuration
public class TradingEngineConfig {

	/**
	 * Creates the OANDA configuration from application.properties.
	 *
	 * This is where the OandaConfig finally gets its values from properties
	 * Spring reads the properties file,
	 * injects the values through @Value, and passes them to the constructor.
	 */
	@Bean
	public OandaConfig oandaConfig(
			@Value("${oanda.api-key}") String apiKey,
			@Value("${oanda.account-id}") String accountId,
			@Value("${oanda.stream-url}") String streamUrl) {
		return new OandaConfig(apiKey, accountId, streamUrl);
	}

	/**
	 * Creates the candle aggregator.
	 * No dependencies needed — it's a standalone component.
	 */
	@Bean
	public CandleAggregator candleAggregator() {
		return new CandleAggregator();
	}

	/**
	 * Creates the tick repository.
	 *
	 * Spring sees that this method needs a JdbcTemplate parameter.
	 * Spring already has a JdbcTemplate bean (auto-created by
	 * spring-boot-starter-jdbc when it detects a DataSource).
	 * So it passes it in automatically. This is DEPENDENCY INJECTION.
	 */
	@Bean
	public TickRepository tickRepository(
			JdbcTemplate jdbcTemplate,
			@Value("${tick.batch-size:100}") int batchSize) {
		return new TickRepository(jdbcTemplate, batchSize);
	}

	/**
	 * Creates the candle repository.
	 */
	@Bean
	public CandleRepository candleRepository(JdbcTemplate jdbcTemplate) {
		return new CandleRepository(jdbcTemplate);
	}

	@Bean
	public FairValueGapDetector fvgDetector() {
		return new FairValueGapDetector();
	}

	@Bean
	public OrderBlockDetector orderBlockDetector(
			@Value("${trading.ob-atr-period:14}") int atrPeriod,
			@Value("${trading.ob-displacement:2.0}") double displacement) {
		return new OrderBlockDetector(atrPeriod, displacement);
	}

	@Bean
	public JudasSwingDetector judasSwingDetector(
			@Value("${trading.judas-lookback:3}") int lookback,
			@Value("${trading.judas-atr-period:20}") int atrPeriod,
			@Value("${trading.judas-displacement:2.5}") double displacement) {
		return new JudasSwingDetector(lookback, atrPeriod, displacement);
	}

	@Bean
	public SignalAggregator signalAggregator(
			FairValueGapDetector fvgDetector,
			OrderBlockDetector obDetector,
			JudasSwingDetector judasDetector) {
		return new SignalAggregator(fvgDetector, obDetector, judasDetector);
	}

	@Bean
	public KillzoneService killzoneService() {
		return new KillzoneService();
	}

	@Bean
	public EconomicCalendarService economicCalendarService() {
		return new EconomicCalendarService();
	}

	@Bean
	public UsdxBiasEngine usdxBiasEngine() {
		return new UsdxBiasEngine(3);
	}

	@Bean
	public SwingPointRegistry swingPointRegistry() {
		return new SwingPointRegistry(3, 50);
	}

	@Bean
	public SmtDivergenceDetector smtDivergenceDetector() {
		return new SmtDivergenceDetector();
	}

	// ── Execution Layer Beans ────────────────────────────────────────

	@Bean
	public RiskManager riskManager(
			@Value("${trading.risk-percentage:0.01}") double riskPct) {
		return new RiskManager(riskPct);
	}

	@Bean
	public OrderManager orderManager(
			RiskManager riskManager,
			@Value("${trading.max-open-positions:4}") int maxPositions,
			@Value("${trading.tp1-multiple:2.0}") double tp1,
			@Value("${trading.tp2-multiple:3.0}") double tp2,
			@Value("${trading.sl-buffer:20}") long slBuffer) {
		return new OrderManager(riskManager, maxPositions, tp1, tp2, slBuffer);
	}

	@Bean
	public BrokerExecutor brokerExecutor(
			@Value("${oanda.api-key}") String apiKey,
			@Value("${oanda.account-id}") String accountId,
			@Value("${oanda.base-url}") String baseUrl) {
		return new OandaOrderExecutor(apiKey, accountId, baseUrl);
	}

	@Bean
	public KillSwitch killSwitch(OrderManager orderManager,
			BrokerExecutor executor) {
		return new KillSwitch(orderManager, executor);
	}

	@Bean
	public OrderExpiryService orderExpiryService(
			OrderManager orderManager,
			BrokerExecutor executor,
			KillzoneService killzoneService) {
		return new OrderExpiryService(orderManager, executor, killzoneService);
	}
	/**
	 * Creates the pricing stream AND wires everything together.
	 *
	 * This is where the magic happens:
	 * 1. Create the stream
	 * 2. Register the aggregator as a tick listener
	 * 3. Register the tick repository as a tick listener
	 * 4. Register the candle repository as a candle listener
	 *
	 * After this method returns, the pipeline is fully connected.
	 * The stream is NOT started yet — that happens in TradingEngineRunner.
	 */
	@Bean
	public PricingStream pricingStream(
			OandaConfig oandaConfig,
			CandleAggregator candleAggregator,
			TickRepository tickRepository,
			CandleRepository candleRepository,
			@Value("${trading.instruments}") String[] instruments) {

		// Create the stream
		OandaPricingStream stream = new OandaPricingStream(oandaConfig, instruments);

		// Wire: stream → aggregator (ticks become candles)
		stream.addListener(candleAggregator);

		// Wire: stream → tick repository (ticks saved to database)
		stream.addListener(tickRepository);

		// Wire: aggregator → candle repository (closed candles saved to database)
		candleAggregator.addCandleListener(candleRepository);

		// Log what we connected
		System.out.println("[TradingEngineConfig] Pipeline wired:");
		System.out.println("  Stream → CandleAggregator → CandleRepository");
		System.out.println("  Stream → TickRepository (batch size: " + tickRepository + ")");
		System.out.println("  Instruments: " + String.join(", ", instruments));

		return stream;
	}
}
