-- The trade log.
--
-- Append-only by intention: a trade is a record of something that happened,
-- and a record that can be edited is not one. There is no UPDATE or DELETE
-- path to this table anywhere in the application.
--
-- Money is stored as BIGINT minor units, never NUMERIC with a scale and never
-- a floating type. A price that is a fraction of a cent out is a trade that
-- cannot be settled, and DOUBLE PRECISION cannot represent 0.1 exactly.
CREATE TABLE trade (
    id              BIGSERIAL PRIMARY KEY,

    -- The engine's own ordering. Unique because it is drawn from a single
    -- counter, and it is what makes replay deterministic: ordering by
    -- timestamp would be ambiguous within a millisecond.
    sequence        BIGINT       NOT NULL UNIQUE,

    buy_order_id    VARCHAR(64)  NOT NULL,
    sell_order_id   VARCHAR(64)  NOT NULL,

    price           BIGINT       NOT NULL CHECK (price > 0),
    quantity        BIGINT       NOT NULL CHECK (quantity > 0),

    executed_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A trade between an order and itself would mean the book crossed its own
    -- resting order, which the engine must never allow. Asserted here too,
    -- because a constraint the database enforces cannot be forgotten by a
    -- future code path.
    CONSTRAINT no_self_trade CHECK (buy_order_id <> sell_order_id)
);

-- Trades are read back by order and by recency; both are indexed rather than
-- left to a sequential scan once the table is large.
CREATE INDEX idx_trade_buy_order  ON trade (buy_order_id);
CREATE INDEX idx_trade_sell_order ON trade (sell_order_id);
CREATE INDEX idx_trade_sequence   ON trade (sequence DESC);
