package com.schmebb.hub;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import com.schmebb.common.PaymentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class Payments {

    private static final class Tracked {
        /** Held while forwarding, so one payment is never forwarded by two threads at once. */
        final ReentrantLock lock = new ReentrantLock();
        /** Replaced, never mutated. Volatile so a status enquiry can read it without waiting for the lock. */
        volatile HubPayment payment;

        Tracked(HubPayment payment) {
            this.payment = payment;
        }
    }

    private final HubProperties properties;
    private final BankClient banks;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final Map<String, Tracked> payments = new ConcurrentHashMap<>();

    Payments(HubProperties properties, BankClient banks, Clock clock, MeterRegistry metrics) {
        this.properties = properties;
        this.banks = banks;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Validates, stores as PENDING, forwards to the payee bank and returns the outcome.
     *
     * <p>A paymentId seen before is not processed again: the sender gets the stored outcome.
     * The one exception is a payment still PENDING after a timeout. A retry forwards it again,
     * which is safe because banks treat a repeated paymentId as the same payment. The
     * {@link #retryPending() retry job} does the same without waiting for the sender.
     */
    PaymentResult submit(PaymentInstruction instruction) {
        String id = instruction.paymentId();
        if (id == null || id.isBlank()) {
            return PaymentResult.rejected(id, "paymentId is required");
        }
        Tracked tracked = payments.computeIfAbsent(id, key -> {
            metrics.counter("paypen.hub.payments.received").increment();
            HubPayment payment = validate(instruction);
            if (payment.status() == PaymentStatus.REJECTED) {
                countDecided(payment, "hub");
            }
            return new Tracked(payment);
        });
        tracked.lock.lock();
        try {
            if (tracked.payment.status() == PaymentStatus.PENDING) {
                tracked.payment = forward(tracked.payment);
            }
            return tracked.payment.result();
        } finally {
            tracked.lock.unlock();
        }
    }

    /**
     * Re-forwards every PENDING payment. This is how a lost response from the payee bank gets
     * resolved: the bank recognises the paymentId and repeats its original answer instead of
     * crediting again. Payments being forwarded right now are skipped.
     */
    @Scheduled(fixedDelayString = "${hub.retry-interval}")
    void retryPending() {
        for (Tracked tracked : payments.values()) {
            if (tracked.payment.status() == PaymentStatus.PENDING && tracked.lock.tryLock()) {
                try {
                    if (tracked.payment.status() == PaymentStatus.PENDING) {
                        tracked.payment = forward(tracked.payment);
                    }
                } finally {
                    tracked.lock.unlock();
                }
            }
        }
    }

    Optional<PaymentResult> status(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId)).map(tracked -> tracked.payment.result());
    }

    List<HubPayment> all() {
        return payments.values().stream().map(tracked -> tracked.payment).toList();
    }

    private HubPayment validate(PaymentInstruction p) {
        var now = clock.instant();
        if (p.amountCents() <= 0) {
            return HubPayment.rejected(p, "Amount must be positive", now);
        }
        if (p.amountCents() > properties.schemeLimitCents()) {
            return HubPayment.rejected(p, "Amount exceeds the scheme limit of "
                    + Money.format(properties.schemeLimitCents()), now);
        }
        if (!properties.participants().containsKey(p.debtorBank())) {
            return HubPayment.rejected(p, "Unknown debtor bank: " + p.debtorBank(), now);
        }
        if (!properties.participants().containsKey(p.creditorBank())) {
            return HubPayment.rejected(p, "Unknown creditor bank: " + p.creditorBank(), now);
        }
        return HubPayment.pending(p, now);
    }

    private HubPayment forward(HubPayment payment) {
        String bank = payment.instruction().creditorBank();
        URI url = properties.participants().get(bank);
        Timer.Sample sample = Timer.start(metrics);
        BankClient.Delivery delivery = banks.send(url, payment.instruction());
        sample.stop(Timer.builder("paypen.hub.payee.latency")
                .description("Time for the payee bank to answer a forwarded payment")
                .tag("bank", bank)
                .tag("outcome", delivery.getClass().getSimpleName())
                .publishPercentileHistogram()
                .register(metrics));

        HubPayment next = switch (delivery) {
            case BankClient.Delivery.Answered(PaymentResult result)
                    when result != null && result.status() != null && result.status() != PaymentStatus.PENDING ->
                    countDecided(payment.decided(result.status(), result.reason(), clock.instant()), "payee_bank");
            case BankClient.Delivery.Answered answered ->
                    payment.stillPending(bank + " gave an unexpected answer; retry with the same paymentId");
            // Safe to reject: the payee bank never processed it, so nobody was credited.
            case BankClient.Delivery.NotDelivered(String reason) ->
                    countDecided(payment.decided(PaymentStatus.REJECTED, bank + ": " + reason, clock.instant()),
                            "undeliverable");
            // Not safe to reject: the payee may have been credited. Stay PENDING until a retry finds out.
            case BankClient.Delivery.Unknown(String reason) -> {
                metrics.counter("paypen.hub.payee.timeouts", "bank", bank).increment();
                yield payment.stillPending("No answer from " + bank + " within "
                        + properties.forwardTimeout().toSeconds() + "s; retry with the same paymentId");
            }
        };
        return next;
    }

    /** Counts a payment reaching ACCEPTED or REJECTED, and who rejected it: the hub, the payee bank, or nobody reachable. */
    private HubPayment countDecided(HubPayment payment, String decidedBy) {
        Counter.builder("paypen.hub.payments.decided")
                .tag("status", payment.status().name())
                .tag("rejected_by", payment.status() == PaymentStatus.REJECTED ? decidedBy : "none")
                .register(metrics)
                .increment();
        return payment;
    }
}
