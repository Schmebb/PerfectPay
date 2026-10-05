package com.schmebb.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "bank.code=BANK_A")
@AutoConfigureMockMvc
// The ledger is in-memory, so give every test a freshly seeded bank.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class BankApplicationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DirectoryClient directory;

    private ResultActions pay(String paymentId, String creditorBank, String creditorAccount, long amountCents)
            throws Exception {
        String body = """
                {"paymentId": "%s", "debtorBank": "BANK_B", "debtorAccount": "lerato",
                 "creditorBank": "%s", "creditorAccount": "%s", "amountCents": %d}
                """.formatted(paymentId, creditorBank, creditorAccount, amountCents);
        return mvc.perform(post("/incoming-payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private ResultActions rejected(ResultActions result, String reason) throws Exception {
        return result.andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value(reason));
    }

    @Test
    void seedsSystemAndCustomerAccountsFromFunding() throws Exception {
        mvc.perform(get("/accounts/thandi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.balanceCents").value(500_00))
                .andExpect(jsonPath("$.entries[0].transactionId").value("opening-balance:thandi"));
        mvc.perform(get("/accounts/zanele")).andExpect(jsonPath("$.frozen").value(true));
        mvc.perform(get("/accounts/OUTGOING_SUSPENSE")).andExpect(jsonPath("$.balanceCents").value(0));
        mvc.perform(get("/accounts/SCHEME_SETTLEMENT")).andExpect(jsonPath("$.balanceCents").value(0));
        // 500 + 300 + 50 issued to customers.
        mvc.perform(get("/accounts/FUNDING")).andExpect(jsonPath("$.balanceCents").value(-850_00));
    }

    @Test
    void registersCustomerPhonesWithDirectory() {
        // The mock answers false ("directory unreachable"), so the retry job may have tried again.
        verify(directory, atLeastOnce()).register("+27821234567", "BANK_A", "thandi");
        verify(directory, atLeastOnce()).register("+27721234567", "BANK_A", "lerato");
        verify(directory, atLeastOnce()).register("+27841234567", "BANK_A", "zanele");
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/accounts/nobody")).andExpect(status().isNotFound());
    }

    @Test
    void acceptedPaymentMovesMoneyFromSchemeSettlementToCustomer() throws Exception {
        pay("p-1", "BANK_A", "thandi", 75_00)
                .andExpect(jsonPath("$.paymentId").value("p-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(get("/accounts/thandi"))
                .andExpect(jsonPath("$.balanceCents").value(575_00))
                .andExpect(jsonPath("$.entries[1].transactionId").value("incoming:p-1"))
                .andExpect(jsonPath("$.entries[1].balanceAfterCents").value(575_00));
        mvc.perform(get("/accounts/SCHEME_SETTLEMENT")).andExpect(jsonPath("$.balanceCents").value(-75_00));
    }

    @Test
    void repeatedPaymentIdGetsSameAnswerAndCreditsOnce() throws Exception {
        pay("p-1", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));
        pay("p-1", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.balanceCents").value(575_00));
    }

    @Test
    void repeatedRejectedPaymentIdStaysRejected() throws Exception {
        rejected(pay("p-1", "BANK_A", "nobody", 75_00), "Unknown account: nobody");
        rejected(pay("p-1", "BANK_A", "thandi", 75_00), "Unknown account: nobody");

        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.balanceCents").value(500_00));
    }

    @Test
    void unknownAccountIsRejected() throws Exception {
        rejected(pay("p-1", "BANK_A", "nobody", 75_00), "Unknown account: nobody");
    }

    @Test
    void frozenAccountIsRejected() throws Exception {
        rejected(pay("p-1", "BANK_A", "zanele", 75_00), "Account is frozen: zanele");

        mvc.perform(get("/accounts/zanele")).andExpect(jsonPath("$.balanceCents").value(50_00));
    }

    @Test
    void systemAccountCannotBePaidInto() throws Exception {
        rejected(pay("p-1", "BANK_A", "OUTGOING_SUSPENSE", 75_00), "Unknown account: OUTGOING_SUSPENSE");
    }

    @Test
    void paymentForAnotherBankIsRejected() throws Exception {
        rejected(pay("p-1", "BANK_B", "thandi", 75_00), "Payment is for BANK_B, this is BANK_A");
    }

    @Test
    void nonPositiveAmountIsRejected() throws Exception {
        rejected(pay("p-1", "BANK_A", "thandi", 0), "Transfer amount must be positive, got 0");
        rejected(pay("p-2", "BANK_A", "thandi", -100), "Transfer amount must be positive, got -100");
    }

    @Test
    void missingPaymentIdIsRejected() throws Exception {
        mvc.perform(post("/incoming-payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"creditorBank": "BANK_A", "creditorAccount": "thandi", "amountCents": 100}
                        """))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("paymentId is required"));
    }

    @Test
    void adminCanFreezeAndUnfreezeAnAccount() throws Exception {
        mvc.perform(post("/admin/accounts/thandi/freeze")).andExpect(jsonPath("$.frozen").value(true));
        rejected(pay("p-1", "BANK_A", "thandi", 75_00), "Account is frozen: thandi");

        mvc.perform(post("/admin/accounts/thandi/unfreeze")).andExpect(jsonPath("$.frozen").value(false));
        pay("p-2", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));

        mvc.perform(post("/admin/accounts/nobody/freeze")).andExpect(status().isNotFound());
    }

    @Test
    void adminLedgerViewShowsEveryBalanceAndSumsToZero() throws Exception {
        pay("p-1", "BANK_A", "thandi", 75_00);

        mvc.perform(get("/admin/ledger"))
                .andExpect(jsonPath("$.bankCode").value("BANK_A"))
                .andExpect(jsonPath("$.balancesCents.thandi").value(575_00))
                .andExpect(jsonPath("$.balancesCents.SCHEME_SETTLEMENT").value(-75_00))
                .andExpect(jsonPath("$.totalCents").value(0));
    }

    @Test
    void slowChaosDelaysTheAnswer() throws Exception {
        setChaos("SLOW", 300);

        long start = System.nanoTime();
        pay("p-1", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(tookMs).isGreaterThanOrEqualTo(300);
    }

    @Test
    void dropResponseChaosCreditsFirstThenDelaysOnlyTheFirstAnswer() throws Exception {
        setChaos("DROP_RESPONSE", 300);

        long start = System.nanoTime();
        pay("p-1", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);

        // The retry is recognised, answered at once, and not credited again.
        start = System.nanoTime();
        pay("p-1", "BANK_A", "thandi", 75_00).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(300);
        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.balanceCents").value(575_00));

        setChaos("NONE", 0);
        mvc.perform(get("/admin/chaos")).andExpect(jsonPath("$.mode").value("NONE"));
    }

    private void setChaos(String mode, long delayMs) throws Exception {
        mvc.perform(put("/admin/chaos").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\": \"%s\", \"delayMs\": %d}".formatted(mode, delayMs)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value(mode));
    }

    @Test
    void adminDepositAddsMoneyFromFunding() throws Exception {
        mvc.perform(post("/admin/accounts/thandi/deposit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 5000000}"))
                .andExpect(jsonPath("$.balanceCents").value(50_500_00));
        mvc.perform(get("/admin/ledger")).andExpect(jsonPath("$.totalCents").value(0));

        mvc.perform(post("/admin/accounts/thandi/deposit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/accounts/nobody/deposit").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amountCents\": 100}"))
                .andExpect(status().isNotFound());
    }

    @Autowired
    private AccountSeeder seeder;

    @Test
    void phoneRegistrationIsRetriedUntilTheDirectoryAnswers() {
        // Seeding already tried once and the mock said "unreachable". Now the directory is up.
        when(directory.register(any(), any(), any())).thenReturn(true);
        seeder.registerPhones();
        clearInvocations(directory);

        seeder.registerPhones();

        verify(directory, never()).register(any(), any(), any());
    }

    @Test
    void metricsAreExposedForPrometheus() throws Exception {
        pay("p-1", "BANK_A", "thandi", 75_00);
        pay("p-2", "BANK_A", "nobody", 75_00);

        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/paypen.bank.incoming.payments").param("tag", "status:ACCEPTED"))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
        mvc.perform(get("/actuator/metrics/paypen.bank.incoming.payments").param("tag", "status:REJECTED"))
                .andExpect(jsonPath("$.measurements[0].value").value(1.0));
    }
}
