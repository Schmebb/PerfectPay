package com.schmebb.ledger;

import java.util.Objects;

public record Account(String id, AccountType type, boolean frozen) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }
}
