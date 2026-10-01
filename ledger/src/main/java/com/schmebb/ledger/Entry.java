package com.schmebb.ledger;

import java.util.Objects;

/** One leg of a transaction: a signed amount in cents (+ credit, − debit) against one account. */
public record Entry(String accountId, long amountCents) {

    public Entry {
        Objects.requireNonNull(accountId, "accountId");
    }
}
