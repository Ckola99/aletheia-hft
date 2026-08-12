package com.aletheia.data;

/**
 * A broker-agnostic live price stream.
 *
 * The engine (CandleAggregator, TickRepository, LiveSignalService via the
 * candle pipeline) depends on this interface, not on any specific broker's
 * streaming mechanism. Concrete implementations wrap a particular broker's
 * price feed:
 *
 * OandaPricingStream — OANDA's chunked HTTP price stream (demo)
 * CTraderFixPricingStream — cTrader FIX price session (FxPro, live) [to build]
 *
 * Different brokers deliver prices very differently under the hood (HTTP
 * streaming vs. a persistent FIX session vs. Protobuf over a socket), but all
 * of that is implementation detail. Every implementation must, at minimum,
 * notify registered TickListeners of parsed ticks, and support starting and
 * stopping the connection cleanly.
 *
 * Swapping brokers is a matter of wiring a different implementation, with no
 * change to CandleAggregator, TickRepository, or the rest of the pipeline.
 */
public interface PricingStream {

	/**
	 * Registers a listener to be called on every parsed tick.
	 * Can be called before or after start().
	 */
	void addListener(TickListener listener);

	/**
	 * Starts the streaming connection on a background thread.
	 * Must return immediately (non-blocking).
	 */
	void start();

	/**
	 * Stops the streaming connection cleanly.
	 */
	void stop();

	/**
	 * Returns true if the stream is currently running.
	 */
	boolean isRunning();

	/**
	 * Returns the total number of ticks received since start.
	 */
	long tickCount();
}
