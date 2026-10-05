package com.schmebb.integration;

import static com.schmebb.integration.PaymentNetwork.balance;
import static com.schmebb.integration.PaymentNetwork.customerPayment;
import static com.schmebb.integration.PaymentNetwork.get;
import static com.schmebb.integration.PaymentNetwork.ledger;
import static com.schmebb.integration.PaymentNetwork.ledgerTotal;
import static com.schmebb.integration.PaymentNetwork.post;
import static com.schmebb.integration.PaymentNetwork.setChaos;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Every payment scenario, end to end, against real running services. After each one the books
 * are checked: every bank's ledger sums to zero, no money is left in suspense, and the two banks'
 * SCHEME_SETTLEMENT accounts mirror each other and the hub's settlement report.
 */
class PaymentScenariosTest {

    private static final String THANDI_PHONE = "0821234567"; // BANK_A, R500
    private static final String SIPHO_PHONE = "0831234567";  // BANK_B, R250

    private PaymentNetwork network;
    private RestClient bankA;
    private RestClient bankB;
    /** BANK_B's books just before a test stops it, so the checks can still include them. */
    private Map<String, Number> bankBBooksBeforeStop;

    @BeforeEach
    void startNetwork() {
        network = new PaymentNetwork();
        bankA = network.bankA;
        bankB = network.bankBClient;
    }

    @AfterEach
    void booksStillBalance() {
        try {
            assertThat(ledgerTotal(bankA)).as("BANK_A ledger total").isZero();
            Map<String, Number> booksA = ledger(bankA);
            Map<String, Number> booksB;
            if (network.bankBRunning()) {
                assertThat(ledgerTotal(bankB)).as("BANK_B ledger total").isZero();
                booksB = ledger(bankB);
            } else {
                booksB = bankBBooksBeforeStop;
            }
            assertThat(booksA.get("OUTGOING_SUSPENSE").longValue()).as("BANK_A money left in suspense").isZero();
            assertThat(booksB.get("OUTGOING_SUSPENSE").longValue()).as("BANK_B money left in suspense").isZero();

            long settlementA = booksA.get("SCHEME_SETTLEMENT").longValue();
            long settlementB = booksB.get("SCHEME_SETTLEMENT").longValue();
            assertThat(settlementA + settlementB).as("what A owes B must be what B is owed by A").isZero();

            @SuppressWarnings("unchecked")
            var hubNet = (Map<String, Number>) get(network.hub, "/settlement").get("netPositionsCents");
            // Hub: positive = receives. Bank: SCHEME_SETTLEMENT positive = owes. Opposite signs.
            assertThat(hubNet.get("BANK_A").longValue()).as("hub's view of BANK_A").isEqualTo(-settlementA);
            assertThat(hubNet.get("BANK_B").longValue()).as("hub's view of BANK_B").isEqualTo(-settlementB);
        } finally {
            network.close();
        }
    }

    private Map<String, Object> thandiPays(String paymentId, String toPhone, long amountCents) {
        return customerPayment(bankA, paymentId, "thandi", toPhone, amountCents);
    }

    private String statusOf(String paymentId) {
        return (String) get(bankA, "/customer-payments/" + paymentId).get("status");
    }

    private static void await(String what, Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.get()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            Thread.sleep(100);
        }
    }

    @Test
    void happyPath() {
        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).containsEntry("status", "COMPLETED").containsEntry("creditorBank", "BANK_B");
        assertThat(balance(bankA, "thandi")).isEqualTo(380_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(370_00);
    }

    @Test
    void unknownNumber() {
        var payment = thandiPays("pay-1", "0611111111", 120_00);

        assertThat(payment).containsEntry("status", "REJECTED")
                .containsEntry("reason", "No account is registered for 0611111111");
        assertThat(balance(bankA, "thandi")).isEqualTo(500_00);
        assertThat(get(network.hub, "/settlement").get("acceptedPayments")).isEqualTo(0);
    }

    @Test
    void insufficientFunds() {
        thandiPays("pay-1", SIPHO_PHONE, 190_00);
        thandiPays("pay-2", SIPHO_PHONE, 190_00);
        var third = thandiPays("pay-3", SIPHO_PHONE, 190_00);

        assertThat(third).containsEntry("status", "REJECTED").containsEntry("reason", "Insufficient funds");
        assertThat(balance(bankA, "thandi")).isEqualTo(120_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(630_00);
    }

    @Test
    void frozenPayeeIsRefunded() {
        post(bankB, "/admin/accounts/sipho/freeze", null);

        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).containsEntry("status", "REFUNDED").containsEntry("reason", "Account is frozen: sipho");
        assertThat(balance(bankA, "thandi")).isEqualTo(500_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(250_00);
    }

    @Test
    void frozenPayerIsRejected() {
        post(bankA, "/admin/accounts/thandi/freeze", null);

        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).containsEntry("status", "REJECTED").containsEntry("reason", "Account is frozen: thandi");
        assertThat(balance(bankB, "sipho")).isEqualTo(250_00);
    }

    @Test
    void overTheLimit() {
        var payment = thandiPays("pay-1", SIPHO_PHONE, PaymentNetwork.SCHEME_LIMIT_CENTS + 1);

        assertThat(payment).containsEntry("status", "REFUNDED")
                .containsEntry("reason", "Amount exceeds the scheme limit of R200.00");
        assertThat(balance(bankA, "thandi")).isEqualTo(500_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(250_00);
    }

    @Test
    void payeeBankDown() {
        bankBBooksBeforeStop = ledger(bankB);
        network.stopBankB();

        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).containsEntry("status", "REFUNDED").containsEntry("reason", "BANK_B: Bank is unreachable");
        assertThat(balance(bankA, "thandi")).isEqualTo(500_00);
    }

    @Test
    void duplicateInstruction() {
        thandiPays("pay-1", SIPHO_PHONE, 120_00);
        // The customer's app retries.
        var retry = thandiPays("pay-1", SIPHO_PHONE, 120_00);
        // BANK_A (or anyone) sends the same instruction to the hub again.
        var resent = post(network.hub, "/payments", Map.of("paymentId", "pay-1", "debtorBank", "BANK_A",
                "debtorAccount", "thandi", "creditorBank", "BANK_B", "creditorAccount", "sipho", "amountCents", 120_00));
        // The hub (or anyone) sends it to BANK_B again.
        var redelivered = post(bankB, "/incoming-payments", Map.of("paymentId", "pay-1", "debtorBank", "BANK_A",
                "debtorAccount", "thandi", "creditorBank", "BANK_B", "creditorAccount", "sipho", "amountCents", 120_00));

        assertThat(retry).containsEntry("status", "COMPLETED");
        assertThat(resent).containsEntry("status", "ACCEPTED");
        assertThat(redelivered).containsEntry("status", "ACCEPTED");
        assertThat(balance(bankA, "thandi")).isEqualTo(380_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(370_00);
    }

    /** BANK_B answers after 2s: the hub (3s) gets the answer, but BANK_A (1s) has already timed out. */
    @Test
    void slowPayeeBankBankAEnquiresInsteadOfRefunding() throws InterruptedException {
        setChaos(bankB, "SLOW", 2_000);

        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).as("not refunded on timeout").containsEntry("status", "SENT");
        assertThat(balance(bankA, "OUTGOING_SUSPENSE")).isEqualTo(120_00);
        await("BANK_A to capture after enquiring", () -> statusOf("pay-1").equals("COMPLETED"));
        assertThat(balance(bankA, "thandi")).isEqualTo(380_00);
        assertThat(balance(bankB, "sipho")).isEqualTo(370_00);
    }

    /**
     * BANK_B credits Sipho, then its answer is lost: the hub times out and leaves the payment
     * PENDING. The hub's retry gets BANK_B's original answer, and BANK_A's enquiry then sees it.
     * Refunding Thandi on the timeout would have paid out the money twice.
     */
    @Test
    void lostResponseFromPayeeBankIsRecovered() throws InterruptedException {
        setChaos(bankB, "DROP_RESPONSE", 4_000);

        var payment = thandiPays("pay-1", SIPHO_PHONE, 120_00);

        assertThat(payment).as("not refunded on timeout").containsEntry("status", "SENT");
        await("BANK_B to credit Sipho", () -> balance(bankB, "sipho") == 370_00);
        assertThat(get(network.hub, "/payments/pay-1").get("status")).isEqualTo("PENDING");
        await("BANK_A to capture after the hub recovers", () -> statusOf("pay-1").equals("COMPLETED"));
        assertThat(get(network.hub, "/payments/pay-1").get("status")).isEqualTo("ACCEPTED");
        assertThat(balance(bankA, "thandi")).isEqualTo(380_00);
        assertThat(balance(bankB, "sipho")).as("credited exactly once").isEqualTo(370_00);
    }
}
