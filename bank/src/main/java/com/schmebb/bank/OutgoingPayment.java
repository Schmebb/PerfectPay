package com.schmebb.bank;

import java.time.Instant;

/**
 * A payment one of this bank's customers is making. Where the money is at each status:
 *
 * <pre>
 * REJECTED   never held: refused before any money moved
 * HELD       customer → OUTGOING_SUSPENSE, not yet sent
 * SENT       sent to the hub, final answer not known yet; money still in suspense
 * COMPLETED  OUTGOING_SUSPENSE → SCHEME_SETTLEMENT
 * REFUNDED   OUTGOING_SUSPENSE → customer
 * </pre>
 *
 * {@code sentAt} is when it was first sent to the hub; null until then.
 */
record OutgoingPayment(
        String paymentId,
        String fromAccount,
        String toPhone,
        String creditorBank,
        String creditorAccount,
        long amountCents,
        Status status,
        String reason,
        Instant sentAt) {

    enum Status {
        REJECTED, HELD, SENT, COMPLETED, REFUNDED;

        boolean isFinal() {
            return this == REJECTED || this == COMPLETED || this == REFUNDED;
        }
    }

    OutgoingPayment with(Status status, String reason) {
        return new OutgoingPayment(paymentId, fromAccount, toPhone, creditorBank, creditorAccount, amountCents,
                status, reason, sentAt);
    }

    OutgoingPayment sent(Instant at) {
        return new OutgoingPayment(paymentId, fromAccount, toPhone, creditorBank, creditorAccount, amountCents,
                Status.SENT, null, sentAt != null ? sentAt : at);
    }
}
