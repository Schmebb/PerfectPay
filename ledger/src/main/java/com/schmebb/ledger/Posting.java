package com.schmebb.ledger;

import java.time.Instant;

/** One line of an account's history: an entry, the transaction it came from, and the balance after it. */
public record Posting(String transactionId, Instant timestamp, long amountCents, long balanceAfterCents) {
}
