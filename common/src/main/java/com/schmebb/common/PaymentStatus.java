package com.schmebb.common;

public enum PaymentStatus {
    /** The hub has the payment but doesn't yet know the payee bank's answer. Not final. */
    PENDING,
    ACCEPTED,
    REJECTED
}
