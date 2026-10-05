package com.schmebb.bank;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import com.schmebb.common.PaymentStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

// The enquiry job is effectively off; tests call enquireSent() themselves so they control the timing.
@SpringBootTest(properties = {"bank.code=BANK_A", "bank.enquiry-interval=1h", "bank.refund-unknown-after=60s"})
@AutoConfigureMockMvc
// The ledger is in-memory, so give every test a freshly seeded bank.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class OutgoingPaymentsTest {

    private static final String SIPHO_PHONE = "+27831234567";
    private static final PaymentInstruction TO_SIPHO =
            new PaymentInstruction("pay-1", "BANK_A", "thandi", "BANK_B", "sipho", 120_00);

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DirectoryClient directory;

    @MockitoBean
    private HubClient hub;

    @MockitoBean
    private Clock clock;

    @Autowired
    private OutgoingPayments outgoingPayments;

    private static final Instant SENT_AT = Instant.parse("2026-10-12T10:00:00Z");

    @BeforeEach
    void siphoIsAtBankB() {
        when(directory.resolve(SIPHO_PHONE))
                .thenReturn(Optional.of(new DirectoryClient.Proxy(SIPHO_PHONE, "BANK_B", "sipho")));
        when(clock.instant()).thenReturn(SENT_AT);
    }

    private void hubTimesOut() {
        when(hub.send(any())).thenReturn(new HubClient.Delivery.Unknown("timed out"));
    }

    private void enquiryAnswers(PaymentStatus status, String reason) {
        when(hub.enquire("pay-1")).thenReturn(new HubClient.Enquiry.Known(new PaymentResult("pay-1", status, reason)));
    }

    private void hubAnswers(PaymentStatus status, String reason) {
        when(hub.send(any())).thenReturn(new HubClient.Delivery.Answered(new PaymentResult("pay-1", status, reason)));
    }

    private ResultActions pay(String from, String toPhone, long amountCents) throws Exception {
        String body = """
                {"paymentId": "pay-1", "fromAccount": "%s", "toPhone": "%s", "amountCents": %d}
                """.formatted(from, toPhone, amountCents);
        return mvc.perform(post("/customer-payments").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    private void expectBalances(long thandi, long suspense, long settlement) throws Exception {
        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.balanceCents").value(thandi));
        mvc.perform(get("/accounts/OUTGOING_SUSPENSE")).andExpect(jsonPath("$.balanceCents").value(suspense));
        mvc.perform(get("/accounts/SCHEME_SETTLEMENT")).andExpect(jsonPath("$.balanceCents").value(settlement));
    }

    @Test
    void acceptedPaymentIsCapturedIntoSchemeSettlement() throws Exception {
        hubAnswers(PaymentStatus.ACCEPTED, null);

        pay("thandi", SIPHO_PHONE, 120_00)
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.creditorBank").value("BANK_B"))
                .andExpect(jsonPath("$.creditorAccount").value("sipho"));

        verify(hub).send(TO_SIPHO);
        expectBalances(380_00, 0, 120_00);
        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.entries[1].transactionId").value("hold:pay-1"));
        mvc.perform(get("/accounts/SCHEME_SETTLEMENT"))
                .andExpect(jsonPath("$.entries[0].transactionId").value("capture:pay-1"));
        mvc.perform(get("/customer-payments/pay-1")).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void rejectedPaymentIsRefunded() throws Exception {
        hubAnswers(PaymentStatus.REJECTED, "Account is frozen: sipho");

        pay("thandi", SIPHO_PHONE, 120_00)
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reason").value("Account is frozen: sipho"));

        expectBalances(500_00, 0, 0);
        mvc.perform(get("/accounts/thandi")).andExpect(jsonPath("$.entries[2].transactionId").value("refund:pay-1"));
    }

    @Test
    void unreachableHubIsRefunded() throws Exception {
        when(hub.send(any())).thenReturn(new HubClient.Delivery.NotDelivered("Hub is unreachable"));

        pay("thandi", SIPHO_PHONE, 120_00)
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reason").value("Hub is unreachable"));

        expectBalances(500_00, 0, 0);
    }

    @Test
    void lostAnswerIsCapturedWhenEnquirySaysAccepted() throws Exception {
        hubTimesOut();
        enquiryAnswers(PaymentStatus.ACCEPTED, null);

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(hub).enquire("pay-1");
        expectBalances(380_00, 0, 120_00);
    }

    @Test
    void lostAnswerIsRefundedWhenEnquirySaysRejected() throws Exception {
        hubTimesOut();
        enquiryAnswers(PaymentStatus.REJECTED, "Account is frozen: sipho");

        pay("thandi", SIPHO_PHONE, 120_00)
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reason").value("Account is frozen: sipho"));

        expectBalances(500_00, 0, 0);
    }

    @Test
    void stillPendingAtHubKeepsMoneyHeldUntilTheJobSettlesIt() throws Exception {
        hubTimesOut();
        enquiryAnswers(PaymentStatus.PENDING, null);

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("SENT"));
        expectBalances(380_00, 120_00, 0);

        outgoingPayments.enquireSent();
        expectBalances(380_00, 120_00, 0);

        enquiryAnswers(PaymentStatus.ACCEPTED, null);
        outgoingPayments.enquireSent();

        mvc.perform(get("/customer-payments/pay-1")).andExpect(jsonPath("$.status").value("COMPLETED"));
        expectBalances(380_00, 0, 120_00);
        verify(hub, times(1)).send(any());
    }

    @Test
    void hubAnsweringPendingIsSettledByTheJob() throws Exception {
        hubAnswers(PaymentStatus.PENDING, null);

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("SENT"));
        verify(hub, never()).enquire(any());

        enquiryAnswers(PaymentStatus.REJECTED, "BANK_B: Bank is unreachable");
        outgoingPayments.enquireSent();

        mvc.perform(get("/customer-payments/pay-1")).andExpect(jsonPath("$.status").value("REFUNDED"));
        expectBalances(500_00, 0, 0);
    }

    @Test
    void customerRetryOfASentPaymentEnquiresInsteadOfSendingAgain() throws Exception {
        hubTimesOut();
        when(hub.enquire("pay-1")).thenReturn(new HubClient.Enquiry.Unavailable("down"));
        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("SENT"));

        enquiryAnswers(PaymentStatus.ACCEPTED, null);
        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(hub, times(1)).send(any());
        expectBalances(380_00, 0, 120_00);
    }

    @Test
    void hubWithNoRecordIsOnlyRefundedOnceItCannotStillArrive() throws Exception {
        hubTimesOut();
        when(hub.enquire("pay-1")).thenReturn(new HubClient.Enquiry.NotFound());

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("SENT"));

        when(clock.instant()).thenReturn(SENT_AT.plusSeconds(59));
        outgoingPayments.enquireSent();
        expectBalances(380_00, 120_00, 0);

        when(clock.instant()).thenReturn(SENT_AT.plusSeconds(61));
        outgoingPayments.enquireSent();

        mvc.perform(get("/customer-payments/pay-1"))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.reason").value("The hub never received the payment"));
        expectBalances(500_00, 0, 0);
    }

    @Test
    void unreachableHubDuringEnquiryKeepsMoneyHeld() throws Exception {
        hubTimesOut();
        when(hub.enquire("pay-1")).thenReturn(new HubClient.Enquiry.Unavailable("down"));

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("SENT"));
        when(clock.instant()).thenReturn(SENT_AT.plusSeconds(3600));
        outgoingPayments.enquireSent();

        expectBalances(380_00, 120_00, 0);
    }

    @Test
    void finishedPaymentIsNotSentAgain() throws Exception {
        hubAnswers(PaymentStatus.ACCEPTED, null);

        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("COMPLETED"));
        pay("thandi", SIPHO_PHONE, 120_00).andExpect(jsonPath("$.status").value("COMPLETED"));

        verify(hub, times(1)).send(any());
        expectBalances(380_00, 0, 120_00);
    }

    @Test
    void unregisteredPhoneIsRejectedWithoutHolding() throws Exception {
        when(directory.resolve("+27611111111")).thenReturn(Optional.empty());

        pay("thandi", "+27611111111", 120_00)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("No account is registered for +27611111111"));

        verify(hub, never()).send(any());
        expectBalances(500_00, 0, 0);
    }

    @Test
    void directoryDownIsRejectedWithoutHolding() throws Exception {
        when(directory.resolve(SIPHO_PHONE))
                .thenThrow(new DirectoryClient.DirectoryUnavailableException(new RuntimeException()));

        pay("thandi", SIPHO_PHONE, 120_00)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Directory is unavailable, try again later"));

        expectBalances(500_00, 0, 0);
    }

    @Test
    void insufficientFundsIsRejectedWithoutSending() throws Exception {
        pay("thandi", SIPHO_PHONE, 500_01)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Insufficient funds"));

        verify(hub, never()).send(any());
        expectBalances(500_00, 0, 0);
    }

    @Test
    void frozenPayerIsRejected() throws Exception {
        pay("zanele", SIPHO_PHONE, 10_00)
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Account is frozen: zanele"));

        verify(hub, never()).send(any());
    }

    @Test
    void unknownPayerIsRejected() throws Exception {
        pay("nobody", SIPHO_PHONE, 10_00).andExpect(jsonPath("$.reason").value("Unknown account: nobody"));
        verify(directory, never()).resolve(any());
    }

    @Test
    void systemAccountCannotPay() throws Exception {
        pay("SCHEME_SETTLEMENT", SIPHO_PHONE, 10_00)
                .andExpect(jsonPath("$.reason").value("Unknown account: SCHEME_SETTLEMENT"));
    }

    @Test
    void nonPositiveAmountIsRejected() throws Exception {
        pay("thandi", SIPHO_PHONE, 0).andExpect(jsonPath("$.reason").value("Amount must be positive"));
    }

    @Test
    void unknownPaymentIs404() throws Exception {
        mvc.perform(get("/customer-payments/nope")).andExpect(status().isNotFound());
    }
}
