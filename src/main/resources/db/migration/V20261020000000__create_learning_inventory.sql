CREATE TABLE learning_stocks (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL,
    remaining_quantity INTEGER NOT NULL CHECK (remaining_quantity >= 0)
);

CREATE TABLE learning_reservations (
    id UUID PRIMARY KEY,
    stock_id UUID NOT NULL REFERENCES learning_stocks (id),
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    reserved_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_learning_reservations_recent
    ON learning_reservations (stock_id, reserved_at DESC, id DESC);
