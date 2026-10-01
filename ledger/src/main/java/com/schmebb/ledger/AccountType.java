package com.schmebb.ledger;

public enum AccountType {
    /** A real customer's account. Can never go negative. */
    CUSTOMER,
    /** An internal account (e.g. FUNDING). Allowed to go negative. */
    SYSTEM
}
