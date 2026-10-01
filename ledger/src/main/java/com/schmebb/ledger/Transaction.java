package com.schmebb.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Transaction(String id, Instant timestamp, List<Entry> entries) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timestamp, "timestamp");
        entries = List.copyOf(entries);
    }
}
