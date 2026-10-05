package com.schmebb.common;

/** A bank's answer to a {@link PaymentInstruction}. {@code reason} is null when accepted. */
public record PaymentResult(String paymentId, PaymentStatus status, String reason) {

    public static PaymentResult accepted(String paymentId) {
        return new PaymentResult(paymentId, PaymentStatus.ACCEPTED, null);
    }

    public static PaymentResult rejected(String paymentId, String reason) {
        return new PaymentResult(paymentId, PaymentStatus.REJECTED, reason);
    }
}
