package com.schmebb.hub;

import static org.assertj.core.api.Assertions.assertThat;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SettlementTest {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 12);
    private static final Instant NOON = DAY.atTime(12, 0).atZone(SAST).toInstant();

    private static HubPayment accepted(String debtor, String creditor, long cents, Instant at) {
        var instruction = new PaymentInstruction("p", debtor, "x", creditor, "y", cents);
        return new HubPayment(instruction, PaymentStatus.ACCEPTED, null, at, at);
    }

    @Test
    void netsEachPairOfBanksSeparately() {
        var report = Settlement.report(List.of(
                accepted("A", "B", 500_00, NOON),
                accepted("B", "A", 150_00, NOON),
                accepted("B", "C", 100_00, NOON),
                accepted("C", "A", 40_00, NOON)), List.of("A", "B", "C"), DAY, SAST);

        assertThat(report.netPositionsCents()).isEqualTo(Map.of("A", -310_00L, "B", 250_00L, "C", 60_00L));
        assertThat(report.netPositionsCents().values().stream().mapToLong(Long::longValue).sum()).isZero();
        assertThat(report.obligations()).extracting(Settlement.Obligation::summary).containsExactly(
                "A owes B R350.00",
                "C owes A R40.00",
                "B owes C R100.00");
    }

    @Test
    void usesTheHubTimeZoneForTheDay() {
        // 23:30 SAST on the 12th is still the 12th, though it's 21:30 UTC.
        Instant lateEvening = DAY.atTime(23, 30).atZone(SAST).toInstant();
        // 00:30 SAST on the 13th is 22:30 UTC on the 12th, but belongs to the 13th.
        Instant afterMidnight = DAY.plusDays(1).atTime(0, 30).atZone(SAST).toInstant();

        var report = Settlement.report(List.of(
                accepted("A", "B", 100_00, lateEvening),
                accepted("A", "B", 999_00, afterMidnight)), List.of("A", "B"), DAY, SAST);

        assertThat(report.totalCents()).isEqualTo(100_00);
    }

    @Test
    void paymentsBetweenAccountsAtTheSameBankOweNothing() {
        var report = Settlement.report(List.of(accepted("A", "A", 100_00, NOON)), List.of("A", "B"), DAY, SAST);

        assertThat(report.netPositionsCents()).isEqualTo(Map.of("A", 0L, "B", 0L));
        assertThat(report.obligations()).isEmpty();
    }
}
