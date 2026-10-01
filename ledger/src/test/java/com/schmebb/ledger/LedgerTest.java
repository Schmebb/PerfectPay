package com.schmebb.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private Ledger ledger;

    @BeforeEach
    void setUp() {
        ledger = new Ledger();
        ledger.openAccount("thandi", AccountType.CUSTOMER);
        ledger.openAccount("sipho", AccountType.CUSTOMER);
    }

    private static Transaction tx(String id, Entry... entries) {
        return new Transaction(id, Instant.now(), List.of(entries));
    }

    @Test
    void fundingGivesOpeningBalanceAndKeepsTotalAtZero() {
        ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        assertEquals(500_00, ledger.balance("thandi"));
        assertEquals(-500_00, ledger.balance(Ledger.FUNDING));
    }

    @Test
    void balancedTransactionPosts() {
        ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        ledger.post(tx("tx-1", new Entry("thandi", -120_00), new Entry("sipho", 120_00)));

        assertEquals(380_00, ledger.balance("thandi"));
        assertEquals(120_00, ledger.balance("sipho"));
    }

    @Test
    void balancedTransactionWithMoreThanTwoEntriesPosts() {
        ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        ledger.post(tx("split",
                new Entry("thandi", -300_00),
                new Entry("sipho", 200_00),
                new Entry(Ledger.FUNDING, 100_00)));

        assertEquals(200_00, ledger.balance("thandi"));
        assertEquals(200_00, ledger.balance("sipho"));
        assertEquals(-400_00, ledger.balance(Ledger.FUNDING));
    }

    @Test
    void unbalancedTransactionIsRejected() {
        ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        assertThrows(LedgerException.class, () ->
                ledger.post(tx("bad", new Entry("thandi", -100_00), new Entry("sipho", 99_99))));

        assertEquals(500_00, ledger.balance("thandi"));
        assertEquals(0, ledger.balance("sipho"));
    }

    @Test
    void singleEntryTransactionIsRejected() {
        assertThrows(LedgerException.class, () ->
                ledger.post(tx("one-leg", new Entry("thandi", 0))));
    }

    @Test
    void insufficientFundsFails() {
        ledger.transfer(Ledger.FUNDING, "thandi", 50_00, "open-thandi");

        assertThrows(InsufficientFundsException.class, () ->
                ledger.transfer("thandi", "sipho", 50_01, "tx-1"));

        assertEquals(50_00, ledger.balance("thandi"));
        assertEquals(0, ledger.balance("sipho"));
    }

    @Test
    void systemAccountCanGoNegative() {
        ledger.transfer(Ledger.FUNDING, "thandi", 1_000_000_00, "open-thandi");

        assertEquals(-1_000_000_00, ledger.balance(Ledger.FUNDING));
    }

    @Test
    void zeroAmountFails() {
        assertThrows(LedgerException.class, () ->
                ledger.transfer(Ledger.FUNDING, "thandi", 0, "tx-1"));
    }

    @Test
    void negativeAmountFails() {
        ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        // A negative transfer would pull money from sipho into thandi's control.
        assertThrows(LedgerException.class, () ->
                ledger.transfer("thandi", "sipho", -100_00, "tx-1"));
    }

    @Test
    void unknownAccountFails() {
        assertThrows(LedgerException.class, () ->
                ledger.transfer(Ledger.FUNDING, "nobody", 100_00, "tx-1"));
        assertThrows(LedgerException.class, () ->
                ledger.post(tx("tx-2", new Entry(Ledger.FUNDING, -100_00), new Entry("nobody", 100_00))));

        assertEquals(0, ledger.balance(Ledger.FUNDING));
    }

    @Test
    void postingSameIdTwiceReturnsOriginalAndPostsOnce() {
        Transaction first = ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        Transaction second = ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        assertSame(first, second);
        assertEquals(500_00, ledger.balance("thandi"));
    }

    @Test
    void replayWithDifferentContentStillReturnsOriginal() {
        Transaction first = ledger.transfer(Ledger.FUNDING, "thandi", 500_00, "open-thandi");

        Transaction replay = ledger.post(tx("open-thandi",
                new Entry(Ledger.FUNDING, -900_00), new Entry("sipho", 900_00)));

        assertSame(first, replay);
        assertEquals(500_00, ledger.balance("thandi"));
        assertEquals(0, ledger.balance("sipho"));
    }
}
