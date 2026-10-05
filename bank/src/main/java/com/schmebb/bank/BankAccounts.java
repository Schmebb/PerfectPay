package com.schmebb.bank;

import com.schmebb.ledger.Ledger;

/**
 * The bank's system accounts. Every step of a PayShap payment is an ordinary ledger transfer:
 *
 * <pre>
 * Opening balance          FUNDING           → customer
 * Hold funds before send   customer          → OUTGOING_SUSPENSE
 * Payment accepted         OUTGOING_SUSPENSE → SCHEME_SETTLEMENT
 * Payment failed, refund   OUTGOING_SUSPENSE → customer
 * Money received           SCHEME_SETTLEMENT → customer
 * </pre>
 */
public final class BankAccounts {

    /** Where opening balances come from. Created by the ledger itself. */
    public static final String FUNDING = Ledger.FUNDING;
    /** Money that has left a customer's account but isn't yet confirmed by the other bank. */
    public static final String OUTGOING_SUSPENSE = "OUTGOING_SUSPENSE";
    /**
     * This bank's position with the other banks in the scheme. Negative: they owe this bank
     * (it has paid out money received for its customers). Positive: this bank owes them.
     */
    public static final String SCHEME_SETTLEMENT = "SCHEME_SETTLEMENT";

    private BankAccounts() {
    }
}
