# PayPen

A small, working model of a real-time payment scheme in the style of South Africa's PayShap:
two banks, a central hub, and a phone-number directory, each a separate service. You can pay
someone by phone number, watch the money move through double-entry ledgers, then break
things on purpose (slow banks, lost responses, frozen accounts) and see that no money is ever
created, lost, or paid twice.

It's a learning project. It is not affiliated with PayShap, its operator, or any bank, and
it doesn't implement the real scheme's messages or rules.

## What is PayShap?

PayShap is South Africa's rapid payments service, launched in 2023 as part of the Rapid
Payments Programme led by the South African Reserve Bank and the Payments Association of
South Africa, and operated by BankservAfrica (now PayInc). It lets someone at one bank pay
someone at another bank **instantly, 24/7**, and pay them by a **ShapID**, a proxy such as a
mobile number, instead of an account number and branch code. Each payment has a
per-transaction limit (R3,000 at launch).

To make that work, a scheme needs the same pieces PayPen models:

- a **directory** that turns a phone number into "bank X, account Y"
- a **hub** that every bank connects to, which routes each payment to the right bank, sets the
  rules (such as the limit), and keeps track of what the banks owe each other
- **banks** that hold their customers' money in a ledger, send payments, and credit incoming ones





## Project structure

```
PayPen/
├── settings.gradle.kts          ← lists the modules
├── build.gradle.kts             ← shared config: Java 21, JUnit, Spring Boot version
├── gradlew, gradle/wrapper/     ← Gradle wrapper (no local Gradle needed)
├── .github/workflows/ci.yml     ← runs every test on every push
├── Dockerfile                   ← one image recipe for every service
├── docker-compose.yml           ← runs all four services (+ Prometheus with --profile metrics)
├── demo.sh                      ← scripted tour of the happy path and failures
├── ledger/                      ← double-entry ledger, plain Java, no Spring
├── common/                      ← messages shared by hub and banks (PaymentInstruction, PaymentResult)
├── directory/                   ← Spring Boot: phone number → bank + account
├── hub/                         ← Spring Boot: routing, limits, status enquiry, settlement
├── bank/                        ← Spring Boot: accounts, outgoing and incoming payments, admin/chaos
└── integration-tests/           ← starts all four services in one JVM and runs every scenario
```



### Without Docker

Needs Java 21.

```bash
./gradlew bootJar
java -jar directory/build/libs/directory-0.1.0-SNAPSHOT.jar
java -jar hub/build/libs/hub-0.1.0-SNAPSHOT.jar
java -jar bank/build/libs/bank-0.1.0-SNAPSHOT.jar                                  # BANK_A
java -jar bank/build/libs/bank-0.1.0-SNAPSHOT.jar --spring.profiles.active=bank-b  # BANK_B
```

Run each `java -jar` in its own terminal.




## API

| Service | Endpoint | Purpose |
|---|---|---|
| directory | `POST /proxies` | Register `{phone, bankCode, accountId}`; 409 if taken, 400 if not a valid SA mobile number |
| directory | `GET /proxies/{phone}` | Resolve a number; 404 if unknown |
| hub | `POST /payments` | Validate, forward to the payee bank, return ACCEPTED / REJECTED / PENDING |
| hub | `GET /payments/{id}` | Status enquiry: what actually happened to this payment? |
| hub | `GET /settlement[?date=YYYY-MM-DD]` | Net positions and who owes whom for a day |
| bank | `POST /customer-payments` | A customer pays a phone number |
| bank | `GET /customer-payments/{id}` | Status of an outgoing payment |
| bank | `POST /incoming-payments` | Called by the hub to credit a customer |
| bank | `GET /accounts/{id}` | Balance and every entry |
| bank | `POST /admin/accounts/{id}/freeze`, `/unfreeze` | Freeze or unfreeze an account |
| bank | `POST /admin/accounts/{id}/deposit` | Add money from FUNDING, `{amountCents}` |
| bank | `GET /admin/ledger` | Every balance and the total (always 0) |
| bank | `PUT /admin/chaos` | `{"mode": "SLOW" \| "DROP_RESPONSE" \| "NONE", "delayMs": 8000}` |

Amounts are always integer cents: `12000` is R120.00.

## Failure scenarios

Each one is an end-to-end test in `integration-tests/`.

| Scenario | What happens | Customer sees | Money |
|---|---|---|---|
| Happy path | Hold, send, payee credits, capture | COMPLETED | Payer −R120, payee +R120 |
| Unknown number | Directory has no such number; stopped before any hold | REJECTED | Nothing moves |
| Insufficient funds | The ledger refuses the hold | REJECTED | Nothing moves |
| Frozen payer | The bank refuses before the hold | REJECTED | Nothing moves |
| Frozen payee | Held and sent; payee bank says REJECTED | REFUNDED | Held, then returned |
| Over the scheme limit | Held and sent; hub says REJECTED | REFUNDED | Held, then returned |
| Payee bank down | Hub can't connect, so the bank certainly never got it | REFUNDED | Held, then returned |
| Duplicate instruction | Same `paymentId` sent again to the bank, hub, or payee bank | The original answer | Moved once |
| Slow payee bank | The paying bank times out first, asks the hub, captures once the hub knows | SENT, then COMPLETED | Held until the outcome is known |
| Lost response | Payee credits, answer lost; hub stays PENDING and retries; paying bank keeps asking | SENT, then COMPLETED | Held until the outcome is known; credited once |

## Design decisions

**Double-entry.** Every transaction is a set of entries that must sum to exactly zero, so money
only ever moves between accounts. It can't appear or vanish because of a bug, only be in the
wrong account, and a wrong account is something you can find. Every step of a payment is
an ordinary transfer:

| Event | From | To |
|---|---|---|
| Opening balance | FUNDING | customer |
| Hold funds before sending | customer | OUTGOING_SUSPENSE |
| Payment accepted | OUTGOING_SUSPENSE | SCHEME_SETTLEMENT |
| Payment failed, refund | OUTGOING_SUSPENSE | customer |
| Money received | SCHEME_SETTLEMENT | customer |

Mistakes are never edited: `reverse(txId)` posts the opposite transaction, so the history shows
both the mistake and the fix.

**Suspense accounts.** Between "Thandi pressed pay" and "BANK_B confirmed", the money belongs to
nobody yet. Taking it out of Thandi's account straight away stops her spending it twice;
keeping it in `OUTGOING_SUSPENSE`, not yet in `SCHEME_SETTLEMENT`, means the bank hasn't
promised it to anyone. The suspense balance always equals "payments we're still waiting to
hear about". Anything stuck there is visible and can be found by a query.

**Settlement accounts.** `SCHEME_SETTLEMENT` is the bank's running position with the rest of the
scheme. Because both banks book the same payment from opposite sides, BANK_A's balance and
BANK_B's always cancel out, and they match the hub's settlement report. That cross-check is what
catches a lost-response bug: in testing, a deliberately broken "refund on timeout" left each
bank's own ledger perfectly balanced, and only the cross-bank comparison showed the R120 that
had been paid out twice.

**Idempotency keys.** Networks fail in the worst place: after the work is done but before the
answer arrives. So every payment carries a `paymentId` chosen by the sender, and every party
(paying bank, hub, payee bank) treats a repeated id as the same payment and repeats its original
answer. That makes retrying always safe, and retrying is how every lost response gets
recovered. A repeat gets the original answer, not "REJECTED: duplicate", because a retry that
hears "rejected" would refund a customer who was in fact paid.

**Never guess on a timeout.** A timeout means "I don't know", not "it failed". The bank only
refunds when it knows nobody was credited: the payee rejected it, the hub rejected it, or the hub
couldn't even be connected to. Otherwise it asks the hub (`GET /payments/{id}`), and a scheduled
job keeps asking until there's an answer. Timeouts are layered so the bank (10s) waits longer
than the hub waits for a payee bank (5s), so normally the hub answers PENDING rather than
leaving the bank to time out.

**Integer cents (`long`).** `0.1 + 0.2 != 0.3` in floating point, and a ledger that must sum to
exactly zero can't afford rounding. A `long` of cents is exact, fast, and covers far more money
than exists. `BigDecimal` would also be exact, but is slower and easier to misuse
(`new BigDecimal(0.1)`, `equals` vs `compareTo`).

**Per-account locks in a fixed order.** Transfers on unrelated accounts run in parallel; a
transfer locks the accounts it touches, always sorted by id, so two opposite transfers can never
deadlock. See `ledger/CONCURRENCY_NOTES.md`.



