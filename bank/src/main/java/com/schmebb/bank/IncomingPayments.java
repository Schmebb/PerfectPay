package com.schmebb.bank;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import com.schmebb.ledger.Account;
import com.schmebb.ledger.AccountType;
import com.schmebb.ledger.Ledger;
import com.schmebb.ledger.LedgerException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Credits payments sent to this bank's customers: SCHEME_SETTLEMENT → customer. */
@Service
class IncomingPayments {

    private final Ledger ledger;
    private final String bankCode;
    /** The answer given for every paymentId seen so far, accepted or rejected. */
    private final Map<String, PaymentResult> results = new ConcurrentHashMap<>();

    private final MeterRegistry metrics;

    IncomingPayments(Ledger ledger, @Value("${bank.code}") String bankCode, MeterRegistry metrics) {
        this.ledger = ledger;
        this.bankCode = bankCode;
        this.metrics = metrics;
    }

    /**
     * Idempotent: a paymentId seen before gets the same answer again and is never credited twice.
     * This matters when the hub retries after a timeout. Answering a retry with "REJECTED:
     * duplicate" would tell the hub the payment failed when the customer was in fact paid.
     */
    Received receive(PaymentInstruction payment) {
        if (payment.paymentId() == null || payment.paymentId().isBlank()) {
            return new Received(PaymentResult.rejected(payment.paymentId(), "paymentId is required"), false);
        }
        boolean[] firstTime = {false};
        // computeIfAbsent runs process() at most once per id, even for simultaneous retries.
        PaymentResult result = results.computeIfAbsent(payment.paymentId(), id -> {
            firstTime[0] = true;
            PaymentResult answer = process(payment);
            metrics.counter("paypen.bank.incoming.payments", "status", answer.status().name()).increment();
            return answer;
        });
        return new Received(result, firstTime[0]);
    }

    /** {@code firstTime} is false when this was a repeat of a paymentId already answered. */
    record Received(PaymentResult result, boolean firstTime) {
    }

    private PaymentResult process(PaymentInstruction payment) {
        String id = payment.paymentId();
        if (!bankCode.equals(payment.creditorBank())) {
            return PaymentResult.rejected(id, "Payment is for " + payment.creditorBank() + ", this is " + bankCode);
        }
        Optional<Account> account = Optional.ofNullable(payment.creditorAccount())
                .flatMap(ledger::account)
                .filter(a -> a.type() == AccountType.CUSTOMER);
        if (account.isEmpty()) {
            return PaymentResult.rejected(id, "Unknown account: " + payment.creditorAccount());
        }
        if (account.get().frozen()) {
            return PaymentResult.rejected(id, "Account is frozen: " + payment.creditorAccount());
        }
        try {
            ledger.transfer(BankAccounts.SCHEME_SETTLEMENT, payment.creditorAccount(), payment.amountCents(),
                    "incoming:" + id);
        } catch (LedgerException e) {
            return PaymentResult.rejected(id, e.getMessage());
        }
        return PaymentResult.accepted(id);
    }
}
