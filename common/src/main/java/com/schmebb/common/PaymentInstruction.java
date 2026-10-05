package com.schmebb.common;

/**
 * A payment as it travels between banks via the hub: {@code amountCents} from the debtor's
 * account at {@code debtorBank} to the creditor's account at {@code creditorBank}.
 * {@code paymentId} is unique per payment and is how every party recognises a retry.
 */
public record PaymentInstruction(
        String paymentId,
        String debtorBank,
        String debtorAccount,
        String creditorBank,
        String creditorAccount,
        long amountCents) {
}
