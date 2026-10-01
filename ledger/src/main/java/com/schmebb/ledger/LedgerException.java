package com.schmebb.ledger;

/** Thrown when the ledger rejects a transaction. Nothing is recorded when this is thrown. */
public class LedgerException extends RuntimeException {

    public LedgerException(String message) {
        super(message);
    }
}
