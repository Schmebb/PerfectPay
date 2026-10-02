# Ledger concurrency notes

What actually happened while making `Ledger` thread-safe. Workload throughout:
50 threads, 10,000 random transfers between 20 customer accounts that each start with R100
(`LedgerConcurrencyTest`).

## 1. No locking, plain collections: it crashes

`HashMap` + `ArrayList`, no synchronisation. Failed 5/5 runs.

- About 70% of transfers (6,600–7,200 of 10,000) died with `ConcurrentModificationException`:
  `balance()` iterated the transaction list while other threads appended to it.
- Balances happened to come out fine, but only because so many transfers aborted that the
  ones that would have raced mostly never ran. The crash hid the real bug.

## 2. Thread-safe collections, still no locking: it quietly loses money

Swapped in `ConcurrentHashMap` + `CopyOnWriteArrayList`. No exceptions, and the sum of
all balances was **still exactly zero** in every run, yet:

| run | customers overdrawn | worst balance |
|----:|--------------------:|--------------:|
| 1 | 8 / 20 | −R240.46 |
| 2 | 6 / 20 | −R241.43 |
| 3 | 9 / 20 | −R245.68 |
| 4 | 7 / 20 | −R336.26 |
| 5 | 8 / 20 | −R260.15 |

**Why the total survived but customers didn't:** "entries sum to zero" is a rule about one
transaction, which each thread checks on its own and gets right. "Customers can't go
negative" is a rule *across* transactions: it depends on the balance not changing between
the check and the write. Two threads both read Thandi's R100, both decide a R80 transfer is
fine, both post it, and she ends up at −R60. This is a classic check-then-act race.

**Lesson:** thread-safe collections make each individual operation safe, not the sequence
of operations. The invariant needs a lock around the whole read-check-write.

## 3. Per-account locks in a fixed order: fixed

Each account has a `ReentrantLock`. `post()` locks every account the transaction touches,
**sorted by account id**, then checks balances, then writes. 0 overdrafts and 0 unexpected
exceptions over 5 runs; about 3,400 transfers per run were correctly rejected as insufficient
funds.

Locking per account (rather than one global lock) lets transfers on unrelated accounts run
at the same time.

## 4. Why the order matters: deadlock

`oppositeTransfersDoNotDeadlock` runs 10,000 A→B and 10,000 B→A transfers at once.

- Locking in entry order (`from`, then `to`): thread 1 holds A and waits for B, while thread 2
  holds B and waits for A. **It hung until the 10 s timeout.**
- Locking in sorted order: both threads always take A first, so they can't each hold what
  the other needs. **It passed in about 0.35 s.**

## 5. Idempotency under concurrency

The id is claimed with `putIfAbsent` *after* validation, while the locks are held, and only
then are entries written. Two concurrent posts with the same id can't both apply: the loser
gets the winner's transaction back. Claiming the id *before* validation would be wrong: if
validation then failed, a retry would get back a transaction that was never posted.

## 6. Performance: running balance vs re-summing

Re-summing every entry on each balance read makes each transfer O(history), so a run of
transfers costs O(n²). A running `balance` field, updated only under the account lock and
only alongside an append, makes it O(1). The entry list is kept as the audit trail, and the
concurrency test checks that the running balance always equals the sum of the entries.

Measured on a MacBook (20 accounts, 1-cent transfers):

| transfers | re-summing, 1 thread | running balance, 1 thread | re-summing, 8 threads | running balance, 8 threads |
|----------:|---------------------:|--------------------------:|----------------------:|---------------------------:|
| 10,000 | 17 ms | 8 ms | 21 ms | 23 ms |
| 50,000 | 190 ms | 23 ms | 107 ms | 78 ms |
| 100,000 | 641 ms | 47 ms | 387 ms | 38 ms |
| 200,000 | 6,451 ms | 78 ms | 2,646 ms | 90 ms |

At 200k transfers the running balance is about 80× faster. More threads don't help much
here: with only 20 accounts and almost no work per transfer, threads mostly queue on the
same locks.

While re-summing was in place, the deadlock test with 100,000 rounds per direction timed out
*even with* correct lock ordering. It wasn't deadlocked, just quadratically slow. That's
why the test uses 10,000 rounds.
