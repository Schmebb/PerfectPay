package com.schmebb.ledger;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(String accountId, long balanceCents, long changeCents) {
        super("Insufficient funds in " + accountId + ": balance " + balanceCents
                + " cents, change " + changeCents + " cents");
    }
}
