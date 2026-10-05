package com.schmebb.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// The retry job is effectively off here so tests control every forward; HubRetryJobTest covers it.
@SpringBootTest(properties = {"hub.forward-timeout=1s", "hub.scheme-limit-cents=300000", "hub.retry-interval=1h"})
@AutoConfigureMockMvc
// Payments are held in memory, so give every test a fresh hub.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HubApplicationTest {

    private static final FakeBank BANK = startFakeBank();

    @DynamicPropertySource
    static void participants(DynamicPropertyRegistry registry) throws IOException {
        registry.add("hub.participants[BANK_A]", BANK::url);
        registry.add("hub.participants[BANK_B]", BANK::url);
        // A registered bank with nothing listening on its port.
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        registry.add("hub.participants[BANK_DOWN]", () -> "http://localhost:" + closedPort);
    }

    private static FakeBank startFakeBank() {
        try {
            return new FakeBank();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void resetBank() {
        BANK.reset();
    }

    private ResultActions pay(String paymentId, String debtorBank, String creditorBank, String creditorAccount,
                              long amountCents) throws Exception {
        String body = """
                {"paymentId": "%s", "debtorBank": "%s", "debtorAccount": "thandi",
                 "creditorBank": "%s", "creditorAccount": "%s", "amountCents": %d}
                """.formatted(paymentId, debtorBank, creditorBank, creditorAccount, amountCents);
        return mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions pay(String paymentId, long amountCents) throws Exception {
        return pay(paymentId, "BANK_A", "BANK_B", "lerato", amountCents);
    }

    private static void expect(ResultActions result, String status, String reason) throws Exception {
        result.andExpect(jsonPath("$.status").value(status)).andExpect(jsonPath("$.reason").value(reason));
    }

    @Test
    void acceptedPaymentIsForwardedAndStored() throws Exception {
        expect(pay("p-1", 500_00), "ACCEPTED", null);

        assertThat(BANK.received()).containsExactly("p-1");
        mvc.perform(get("/payments/p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value("p-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void payeeBankRejectionIsPassedBack() throws Exception {
        expect(pay("p-1", "BANK_A", "BANK_B", "nobody", 100_00), "REJECTED", "Unknown account: nobody");

        mvc.perform(get("/payments/p-1")).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        mvc.perform(get("/payments/nope")).andExpect(status().isNotFound());
    }

    @Test
    void nonPositiveAmountIsRejectedWithoutForwarding() throws Exception {
        expect(pay("p-1", 0), "REJECTED", "Amount must be positive");
        expect(pay("p-2", -100), "REJECTED", "Amount must be positive");

        assertThat(BANK.received()).isEmpty();
    }

    @Test
    void schemeLimitIsEnforced() throws Exception {
        expect(pay("p-1", 3_000_00), "ACCEPTED", null);
        expect(pay("p-2", 3_000_01), "REJECTED", "Amount exceeds the scheme limit of R3,000.00");

        assertThat(BANK.received()).containsExactly("p-1");
    }

    @Test
    void unknownBanksAreRejected() throws Exception {
        expect(pay("p-1", "BANK_Z", "BANK_B", "lerato", 100), "REJECTED", "Unknown debtor bank: BANK_Z");
        expect(pay("p-2", "BANK_A", "BANK_Z", "lerato", 100), "REJECTED", "Unknown creditor bank: BANK_Z");

        assertThat(BANK.received()).isEmpty();
    }

    @Test
    void missingPaymentIdIsRejected() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"debtorBank": "BANK_A", "creditorBank": "BANK_B", "creditorAccount": "lerato", "amountCents": 100}
                        """))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("paymentId is required"));
    }

    @Test
    void repeatedPaymentIdReturnsOriginalWithoutForwardingAgain() throws Exception {
        expect(pay("p-1", 500_00), "ACCEPTED", null);
        // Same id, different content: still the original payment.
        expect(pay("p-1", "BANK_A", "BANK_B", "nobody", 1_00), "ACCEPTED", null);

        assertThat(BANK.received()).containsExactly("p-1");
    }

    @Test
    void timeoutLeavesPaymentPendingAndRetryResolvesIt() throws Exception {
        long start = System.nanoTime();
        expect(pay("p-1", "BANK_A", "BANK_B", "slow", 100_00), "PENDING",
                "No answer from BANK_B within 1s; retry with the same paymentId");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2_500);
        mvc.perform(get("/payments/p-1")).andExpect(jsonPath("$.status").value("PENDING"));

        BANK.slow = false;
        expect(pay("p-1", "BANK_A", "BANK_B", "slow", 100_00), "ACCEPTED", null);

        mvc.perform(get("/payments/p-1")).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(BANK.received()).containsExactly("p-1", "p-1");
    }

    @Test
    void unreachableBankIsRejected() throws Exception {
        expect(pay("p-1", "BANK_A", "BANK_DOWN", "lerato", 100_00), "REJECTED", "BANK_DOWN: Bank is unreachable");
    }

    @Test
    void bankRefusingTheRequestIsRejected() throws Exception {
        expect(pay("p-1", "BANK_A", "BANK_B", "broken", 100_00), "REJECTED",
                "BANK_B: Bank refused the request: 400 BAD_REQUEST");
    }

    @Test
    void settlementNetsTodaysAcceptedPayments() throws Exception {
        pay("p-1", "BANK_A", "BANK_B", "lerato", 500_00);
        pay("p-2", "BANK_B", "BANK_A", "sipho", 150_00);
        pay("p-3", "BANK_A", "BANK_B", "nobody", 999_00); // rejected: not settled
        pay("p-4", "BANK_A", "BANK_B", "slow", 999_00);   // pending: not settled

        String today = LocalDate.now(ZoneId.of("Africa/Johannesburg")).toString();
        mvc.perform(get("/settlement"))
                .andExpect(jsonPath("$.date").value(today))
                .andExpect(jsonPath("$.acceptedPayments").value(2))
                .andExpect(jsonPath("$.totalCents").value(650_00))
                .andExpect(jsonPath("$.netPositionsCents.BANK_A").value(-350_00))
                .andExpect(jsonPath("$.netPositionsCents.BANK_B").value(350_00))
                .andExpect(jsonPath("$.netPositionsCents.BANK_DOWN").value(0))
                .andExpect(jsonPath("$.obligations.length()").value(1))
                .andExpect(jsonPath("$.obligations[0].from").value("BANK_A"))
                .andExpect(jsonPath("$.obligations[0].to").value("BANK_B"))
                .andExpect(jsonPath("$.obligations[0].amountCents").value(350_00))
                .andExpect(jsonPath("$.obligations[0].summary").value("BANK_A owes BANK_B R350.00"));
    }

    @Test
    void settlementForAnotherDayExcludesTodaysPayments() throws Exception {
        pay("p-1", 500_00);

        String yesterday = LocalDate.now(ZoneId.of("Africa/Johannesburg")).minusDays(1).toString();
        mvc.perform(get("/settlement").param("date", yesterday))
                .andExpect(jsonPath("$.acceptedPayments").value(0))
                .andExpect(jsonPath("$.obligations").isEmpty());
    }

    @Test
    void metricsCountPaymentsByOutcome() throws Exception {
        pay("p-1", 500_00);
        pay("p-2", 0);
        pay("p-3", "BANK_A", "BANK_B", "nobody", 100_00);

        mvc.perform(get("/actuator/metrics/paypen.hub.payments.received"))
                .andExpect(jsonPath("$.measurements[0].value").value(3.0));
        mvc.perform(get("/actuator/metrics/paypen.hub.payments.decided").param("tag", "status:ACCEPTED"))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(get("/actuator/metrics/paypen.hub.payments.decided").param("tag", "rejected_by:hub"))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(get("/actuator/metrics/paypen.hub.payments.decided").param("tag", "rejected_by:payee_bank"))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(get("/actuator/metrics/paypen.hub.payee.latency"))
                .andExpect(jsonPath("$.measurements[0].value").value(2.0)); // COUNT: two were forwarded
    }
}
