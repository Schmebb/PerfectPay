package com.schmebb.ledger;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A double-entry ledger. Every posted transaction's entries sum to zero, so money only
 * ever moves between accounts — it is never created or destroyed.
 *
 * <p>Thread-safe. Each account has its own lock; a transaction locks every account it touches,
 * always in account-id order, so transfers on unrelated accounts run in parallel and two
 * transfers in opposite directions can never deadlock.
 */
public final class Ledger {

    /** System account that opening balances are drawn from. Goes negative as money is issued. */
    public static final String FUNDING = "FUNDING";

    private static final class AccountState {
        final Account account;
        final ReentrantLock lock = new ReentrantLock();
        /** Guarded by {@link #lock}. */
        final List<Entry> entries = new ArrayList<>();

        AccountState(Account account) {
            this.account = account;
        }

        /** Caller must hold {@link #lock}. */
        long balance() {
            long balance = 0;
            for (Entry entry : entries) {
                balance += entry.amountCents();
            }
            return balance;
        }
    }

    private final Clock clock;
    private final Map<String, AccountState> accounts = new ConcurrentHashMap<>();
    private final Map<String, Transaction> transactionsById = new ConcurrentHashMap<>();

    public Ledger() {
        this(Clock.systemUTC());
    }

    public Ledger(Clock clock) {
        this.clock = clock;
        openAccount(FUNDING, AccountType.SYSTEM);
    }

    public Account openAccount(String id, AccountType type) {
        Account account = new Account(id, type, false);
        if (accounts.putIfAbsent(id, new AccountState(account)) != null) {
            throw new LedgerException("Account already exists: " + id);
        }
        return account;
    }

    /**
     * Records a transaction, or throws {@link LedgerException} and records nothing.
     * All checks run before anything is written, so a rejected transaction leaves no trace.
     *
     * <p>Idempotent: posting an id that already exists returns the original transaction and
     * changes nothing, so a retried payment is never applied twice.
     */
    public Transaction post(Transaction transaction) {
        Transaction existing = transactionsById.get(transaction.id());
        if (existing != null) {
            return existing;
        }
        List<Entry> entries = transaction.entries();
        if (entries.size() < 2) {
            throw new LedgerException("A transaction needs at least two entries, got " + entries.size());
        }

        long sum = 0;
        // Net change per account, so an account appearing in several entries is checked once.
        Map<String, Long> changes = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (!accounts.containsKey(entry.accountId())) {
                throw new LedgerException("Unknown account: " + entry.accountId());
            }
            sum = Math.addExact(sum, entry.amountCents());
            changes.merge(entry.accountId(), entry.amountCents(), Math::addExact);
        }
        if (sum != 0) {
            throw new LedgerException("Entries must sum to zero, got " + sum);
        }

        // Fixed lock order (sorted by id) is what prevents deadlock: two threads can never
        // each hold a lock the other is waiting for.
        List<AccountState> touched = changes.keySet().stream().sorted().map(accounts::get).toList();
        for (AccountState state : touched) {
            state.lock.lock();
        }
        try {
            // Balances are read under the locks, so they can't change between check and write.
            for (AccountState state : touched) {
                long balance = state.balance();
                long change = changes.get(state.account.id());
                if (state.account.type() == AccountType.CUSTOMER && balance + change < 0) {
                    throw new InsufficientFundsException(state.account.id(), balance, change);
                }
            }
            // Claim the id last: a concurrent post of the same id may have won while we waited.
            existing = transactionsById.putIfAbsent(transaction.id(), transaction);
            if (existing != null) {
                return existing;
            }
            for (Entry entry : entries) {
                accounts.get(entry.accountId()).entries.add(entry);
            }
            return transaction;
        } finally {
            for (int i = touched.size() - 1; i >= 0; i--) {
                touched.get(i).lock.unlock();
            }
        }
    }

    /** Moves {@code amountCents} from one account to another as a two-entry transaction. */
    public Transaction transfer(String from, String to, long amountCents, String txId) {
        if (amountCents <= 0) {
            throw new LedgerException("Transfer amount must be positive, got " + amountCents);
        }
        if (from.equals(to)) {
            throw new LedgerException("Cannot transfer from an account to itself: " + from);
        }
        return post(new Transaction(txId, clock.instant(), List.of(
                new Entry(from, -amountCents),
                new Entry(to, amountCents))));
    }

    /** The account's balance in cents: the sum of every entry ever posted against it. */
    public long balance(String accountId) {
        AccountState state = state(accountId);
        state.lock.lock();
        try {
            return state.balance();
        } finally {
            state.lock.unlock();
        }
    }

    private AccountState state(String accountId) {
        AccountState state = accounts.get(accountId);
        if (state == null) {
            throw new LedgerException("Unknown account: " + accountId);
        }
        return state;
    }
}
