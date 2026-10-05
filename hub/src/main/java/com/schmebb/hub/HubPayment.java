package com.schmebb.hub;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import com.schmebb.common.PaymentStatus;
import java.time.Instant;

/** The hub's record of one payment. {@code decidedAt} is null while PENDING. */
record HubPayment(
        PaymentInstruction instruction,
        PaymentStatus status,
        String reason,
        Instant receivedAt,
        Instant decidedAt) {

    static HubPayment pending(PaymentInstruction instruction, Instant now) {
        return new HubPayment(instruction, PaymentStatus.PENDING, null, now, null);
    }

    static HubPayment rejected(PaymentInstruction instruction, String reason, Instant now) {
        return new HubPayment(instruction, PaymentStatus.REJECTED, reason, now, now);
    }

    HubPayment decided(PaymentStatus status, String reason, Instant now) {
        return new HubPayment(instruction, status, reason, receivedAt, now);
    }

    HubPayment stillPending(String reason) {
        return new HubPayment(instruction, PaymentStatus.PENDING, reason, receivedAt, null);
    }

    PaymentResult result() {
        return new PaymentResult(instruction.paymentId(), status, reason);
    }
}
