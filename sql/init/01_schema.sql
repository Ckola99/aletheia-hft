-- ═══════════════════════════════════════════════════════════════════
-- Aletheia — Database Schema
-- Runs automatically on first 'docker compose up'
-- ═══════════════════════════════════════════════════════════════════

CREATE EXTENSION IF NOT EXISTS timescaledb;

-- Raw ticks from OANDA streaming feed
-- bid/ask are BIGINT (scaled integers — see PriceScale.java)
CREATE TABLE IF NOT EXISTS ticks (
    time        TIMESTAMPTZ NOT NULL,
    instrument  TEXT        NOT NULL,
    bid         BIGINT      NOT NULL,
    ask         BIGINT      NOT NULL
);

SELECT create_hypertable('ticks', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_ticks_instrument_time
    ON ticks (instrument, time DESC);

-- OHLCV candles at every timeframe
CREATE TABLE IF NOT EXISTS candles (
    time        TIMESTAMPTZ NOT NULL,
    instrument  TEXT        NOT NULL,
    timeframe   TEXT        NOT NULL,
    open        BIGINT      NOT NULL,
    high        BIGINT      NOT NULL,
    low         BIGINT      NOT NULL,
    close       BIGINT      NOT NULL,
    volume      BIGINT
);

SELECT create_hypertable('candles', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_candles_lookup
    ON candles (instrument, timeframe, time DESC);

-- Economic calendar events from Forex Factory scraper
CREATE TABLE IF NOT EXISTS economic_events (
    id              BIGSERIAL   PRIMARY KEY,
    scheduled_time  TIMESTAMPTZ NOT NULL,
    currency        TEXT        NOT NULL,
    event_name      TEXT        NOT NULL,
    impact          TEXT        NOT NULL,
    actual          TEXT,
    forecast        TEXT,
    previous        TEXT,
    scraped_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (scheduled_time, currency, event_name)
);

CREATE INDEX IF NOT EXISTS idx_events_time_impact
    ON economic_events (scheduled_time, impact);

-- Trade journal — every trade with full ICT context
CREATE TABLE IF NOT EXISTS trades (
    id              BIGSERIAL   PRIMARY KEY,
    trade_id        UUID        NOT NULL UNIQUE,
    instrument      TEXT        NOT NULL,
    direction       TEXT        NOT NULL,
    entry_time      TIMESTAMPTZ NOT NULL,
    entry_price     BIGINT      NOT NULL,
    stop_loss       BIGINT      NOT NULL,
    tp1_price       BIGINT      NOT NULL,
    tp2_price       BIGINT,
    exit_time       TIMESTAMPTZ,
    exit_price      BIGINT,
    pnl_pips        NUMERIC,
    pnl_dollars     NUMERIC,
    signal_grade    TEXT,
    smt_confirmed   BOOLEAN     NOT NULL DEFAULT FALSE,
    news_cleared    BOOLEAN     NOT NULL DEFAULT TRUE,
    killzone        TEXT        NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_trades_entry_time
    ON trades (entry_time DESC);
