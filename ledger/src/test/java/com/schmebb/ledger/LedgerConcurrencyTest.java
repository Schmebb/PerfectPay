package com.schmebb.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;

class LedgerConcurrencyTest {

    private static final int THREADS = 50;
    private static final int TRANSFERS = 10_000;
    private static final int ACCOUNTS = 20;
    private static final long OPENING_BALANCE = 100_00;

    @RepeatedTest(5)
    void randomConcurrentTransfersKeepTheBooksBalanced() throws InterruptedException {
        Ledger ledger = new Ledger();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            String id = "customer-" + i;
            ledger.openAccount(id, AccountType.CUSTOMER);
            ledger.transfer(Ledger.FUNDING, id, OPENING_BALANCE, "open-" + id);
            ids.add(id);
        }

        AtomicInteger nextTx = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int t = 0; t < THREADS; t++) {
            pool.submit(() -> {
                start.await();
                ThreadLocalRandom random = ThreadLocalRandom.current();
                int n;
                while ((n = nextTx.getAndIncrement()) < TRANSFERS) {
                    String from = ids.get(random.nextInt(ACCOUNTS));
                    String to = ids.get(random.nextInt(ACCOUNTS));
                    if (from.equals(to)) {
                        continue;
                    }
                    try {
                        ledger.transfer(from, to, 1 + random.nextLong(OPENING_BALANCE), "tx-" + n);
                    } catch (InsufficientFundsException expected) {
                        // Rejecting an overdraft is correct behaviour.
                    } catch (Throwable e) {
                        unexpected.add(e);
                    }
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "transfers did not finish");

        assertTrue(unexpected.isEmpty(), () -> "unexpected exceptions: " + unexpected);
        long total = ledger.balance(Ledger.FUNDING);
        for (String id : ids) {
            long balance = ledger.balance(id);
            assertTrue(balance >= 0, id + " went negative: " + balance);
            total += balance;
        }
        assertEquals(0, total, "sum of all balances");
    }
}
