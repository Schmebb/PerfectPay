package com.schmebb.bank;

import com.schmebb.bank.OutgoingPayment.Status;
import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import com.schmebb.common.PaymentStatus;
import com.schmebb.ledger.Account;
import com.schmebb.ledger.AccountType;
import com.schmebb.ledger.InsufficientFundsException;
import com.schmebb.ledger.Ledger;
import com.schmebb.ledger.LedgerException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Sends customers' payments: resolve the phone, hold the funds, send to the hub, then capture or refund.
 *
 * <p>The rule throughout: only refund when we know the payee was not credited. If the hub's answer
 * is lost, refunding would leave the payer refunded <em>and</em> the payee paid. So we ask the hub
 * what happened instead, and keep the money in suspense until it can tell us.
 */
@Service
class OutgoingPayments {

    record Request(String paymentId, String fromAccount, String toPhone, long amountCents) {
    }

    private static final class Tracked {
        /** Held while a payment is being worked on, so it is never held, sent or settled twice at once. */
        final ReentrantLock lock = new ReentrantLock();
        /** Null until the first attempt has started. Volatile so lookups don't wait for the lock. */
        volatile OutgoingPayment payment;
    }

    private final Ledger ledger;
    private final DirectoryClient directory;
    private final HubClient hub;
    private final Clock clock;
    private final String bankCode;
    private final Duration refundUnknownAfter;
    private final MeterRegistry metrics;
    private final Map<String, Tracked> payments = new ConcurrentHashMap<>();

    OutgoingPayments(Ledger ledger, DirectoryClient directory, HubClient hub, Clock clock,
                     @Value("${bank.code}") String bankCode,
                     @Value("${bank.refund-unknown-after}") Duration refundUnknownAfter,
                     MeterRegistry metrics) {
        this.ledger = ledger;
        this.directory = directory;
        this.hub = hub;
        this.clock = clock;
        this.bankCode = bankCode;
        this.refundUnknownAfter = refundUnknownAfter;
        this.metrics = metrics;
    }

    /**
     * Idempotent per paymentId. A finished payment is returned as it is; one still SENT gets a
     * fresh status enquiry to the hub.
     */
    OutgoingPayment pay(Request request) {
        String id = request.paymentId() == null || request.paymentId().isBlank()
                ? UUID.randomUUID().toString()
                : request.paymentId();
        Tracked tracked = payments.computeIfAbsent(id, key -> new Tracked());
        tracked.lock.lock();
        try {
            if (tracked.payment == null) {
                Timer.Sample sample = Timer.start(metrics);
                tracked.payment = hold(id, request);
                if (tracked.payment.status() == Status.HELD) {
                    tracked.payment = tracked.payment.sent(clock.instant());
                    tracked.payment = send(tracked.payment);
                }
                sample.stop(Timer.builder("paypen.bank.customer.payment.latency")
                        .description("Time to answer a customer's payment request")
                        .tag("status", tracked.payment.status().name())
                        .publishPercentileHistogram()
                        .register(metrics));
                countIfFinal(tracked.payment);
            } else if (tracked.payment.status() == Status.SENT) {
                tracked.payment = countIfFinal(enquire(tracked.payment));
            }
            return tracked.payment;
        } finally {
            tracked.lock.unlock();
        }
    }

    Optional<OutgoingPayment> find(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId)).map(tracked -> tracked.payment);
    }

    /** Asks the hub about every payment still SENT. Payments being worked on right now are skipped. */
    @Scheduled(fixedDelayString = "${bank.enquiry-interval}")
    void enquireSent() {
        for (Tracked tracked : payments.values()) {
            OutgoingPayment payment = tracked.payment;
            if (payment != null && payment.status() == Status.SENT && tracked.lock.tryLock()) {
                try {
                    if (tracked.payment.status() == Status.SENT) {
                        tracked.payment = countIfFinal(enquire(tracked.payment));
                    }
                } finally {
                    tracked.lock.unlock();
                }
            }
        }
    }

    /** Counts each payment once, when it reaches REJECTED, COMPLETED or REFUNDED. */
    private OutgoingPayment countIfFinal(OutgoingPayment payment) {
        if (payment.status().isFinal()) {
            metrics.counter("paypen.bank.customer.payments", "status", payment.status().name()).increment();
        }
        return payment;
    }

    /** Checks the payer and payee, then moves the money into suspense: HELD, or REJECTED if nothing moved. */
    private OutgoingPayment hold(String id, Request request) {
        var payment = new OutgoingPayment(id, request.fromAccount(), request.toPhone(), null, null,
                request.amountCents(), Status.HELD, null, null);
        if (request.amountCents() <= 0) {
            return payment.with(Status.REJECTED, "Amount must be positive");
        }
        Optional<Account> payer = Optional.ofNullable(request.fromAccount())
                .flatMap(ledger::account)
                .filter(account -> account.type() == AccountType.CUSTOMER);
        if (payer.isEmpty()) {
            return payment.with(Status.REJECTED, "Unknown account: " + request.fromAccount());
        }
        if (payer.get().frozen()) {
            return payment.with(Status.REJECTED, "Account is frozen: " + request.fromAccount());
        }

        Optional<DirectoryClient.Proxy> payee;
        try {
            payee = Optional.ofNullable(request.toPhone()).flatMap(directory::resolve);
        } catch (DirectoryClient.DirectoryUnavailableException e) {
            return payment.with(Status.REJECTED, "Directory is unavailable, try again later");
        }
        if (payee.isEmpty()) {
            return payment.with(Status.REJECTED, "No account is registered for " + request.toPhone());
        }
        payment = new OutgoingPayment(id, request.fromAccount(), request.toPhone(), payee.get().bankCode(),
                payee.get().accountId(), request.amountCents(), Status.HELD, null, null);

        try {
            ledger.transfer(request.fromAccount(), BankAccounts.OUTGOING_SUSPENSE, request.amountCents(), "hold:" + id);
        } catch (InsufficientFundsException e) {
            return payment.with(Status.REJECTED, "Insufficient funds");
        } catch (LedgerException e) {
            return payment.with(Status.REJECTED, e.getMessage());
        }
        return payment;
    }

    /** Sends a held payment to the hub and settles the suspense money according to the answer. */
    private OutgoingPayment send(OutgoingPayment payment) {
        var instruction = new PaymentInstruction(payment.paymentId(), bankCode, payment.fromAccount(),
                payment.creditorBank(), payment.creditorAccount(), payment.amountCents());
        return switch (hub.send(instruction)) {
            case HubClient.Delivery.Answered(PaymentResult result) -> settle(payment, result);
            // Safe to refund: the hub never took the payment, so nobody can have been credited.
            case HubClient.Delivery.NotDelivered(String reason) -> refund(payment, reason);
            // The answer was lost. Don't refund: the payee may have been paid. Ask the hub instead.
            case HubClient.Delivery.Unknown(String reason) -> enquire(payment);
        };
    }

    /** Status enquiry: settles the payment if the hub knows its outcome, otherwise leaves it SENT. */
    private OutgoingPayment enquire(OutgoingPayment payment) {
        return switch (hub.enquire(payment.paymentId())) {
            case HubClient.Enquiry.Known(PaymentResult result) -> settle(payment, result);
            // The hub has never heard of it. Right after sending, the request may still be on its way,
            // so wait. Once it's well past any timeout, it never arrived and the refund is safe.
            case HubClient.Enquiry.NotFound() when sentLongAgo(payment) ->
                    refund(payment, "The hub never received the payment");
            case HubClient.Enquiry.NotFound() ->
                    payment.with(Status.SENT, "The hub has no record of the payment yet; will ask again");
            case HubClient.Enquiry.Unavailable(String reason) ->
                    payment.with(Status.SENT, "Could not reach the hub to ask; will ask again");
        };
    }

    private OutgoingPayment settle(OutgoingPayment payment, PaymentResult result) {
        if (result != null && result.status() == PaymentStatus.ACCEPTED) {
            return capture(payment);
        }
        if (result != null && result.status() == PaymentStatus.REJECTED) {
            return refund(payment, result.reason());
        }
        // PENDING, or an answer we don't understand: the payee may yet be credited, so keep the money held.
        return payment.with(Status.SENT, "The hub has no final answer yet; will ask again");
    }

    private boolean sentLongAgo(OutgoingPayment payment) {
        return payment.sentAt().plus(refundUnknownAfter).isBefore(clock.instant());
    }

    private OutgoingPayment capture(OutgoingPayment payment) {
        ledger.transfer(BankAccounts.OUTGOING_SUSPENSE, BankAccounts.SCHEME_SETTLEMENT, payment.amountCents(),
                "capture:" + payment.paymentId());
        return payment.with(Status.COMPLETED, null);
    }

    private OutgoingPayment refund(OutgoingPayment payment, String reason) {
        ledger.transfer(BankAccounts.OUTGOING_SUSPENSE, payment.fromAccount(), payment.amountCents(),
                "refund:" + payment.paymentId());
        return payment.with(Status.REFUNDED, reason);
    }
}
