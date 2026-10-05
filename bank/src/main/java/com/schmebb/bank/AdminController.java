package com.schmebb.bank;

import com.schmebb.ledger.Account;
import com.schmebb.ledger.Ledger;
import com.schmebb.ledger.LedgerException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operator and test endpoints. Not something a real bank would expose without authentication. */
@RestController
@RequestMapping("/admin")
class AdminController {

    /** {@code totalCents} is always 0 in a healthy double-entry ledger. */
    record LedgerView(String bankCode, Map<String, Long> balancesCents, long totalCents) {
    }

    private final Ledger ledger;
    private final Chaos chaos;
    private final String bankCode;

    AdminController(Ledger ledger, Chaos chaos, @Value("${bank.code}") String bankCode) {
        this.ledger = ledger;
        this.chaos = chaos;
        this.bankCode = bankCode;
    }

    @PostMapping("/accounts/{id}/freeze")
    ResponseEntity<Account> freeze(@PathVariable String id) {
        return setFrozen(id, true);
    }

    @PostMapping("/accounts/{id}/unfreeze")
    ResponseEntity<Account> unfreeze(@PathVariable String id) {
        return setFrozen(id, false);
    }

    private ResponseEntity<Account> setFrozen(String id, boolean frozen) {
        if (ledger.account(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(ledger.setFrozen(id, frozen));
    }

    record Deposit(long amountCents) {
    }

    /** Adds money to an account from FUNDING, like a cash deposit. Used by the demo. */
    @PostMapping("/accounts/{id}/deposit")
    ResponseEntity<Map<String, Object>> deposit(@PathVariable String id, @RequestBody Deposit deposit) {
        if (ledger.account(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            ledger.transfer(BankAccounts.FUNDING, id, deposit.amountCents(), "deposit:" + UUID.randomUUID());
        } catch (LedgerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("id", id, "balanceCents", ledger.balance(id)));
    }

    @GetMapping("/ledger")
    LedgerView ledger() {
        Map<String, Long> balances = new LinkedHashMap<>();
        long total = 0;
        for (Account account : ledger.accounts()) {
            long balance = ledger.balance(account.id());
            balances.put(account.id(), balance);
            total += balance;
        }
        return new LedgerView(bankCode, balances, total);
    }

    @GetMapping("/chaos")
    Chaos.Settings chaos() {
        return chaos.settings();
    }

    /** Body: {@code {"mode": "SLOW" | "DROP_RESPONSE" | "NONE", "delayMs": 8000}}. */
    @PutMapping("/chaos")
    Chaos.Settings setChaos(@RequestBody Chaos.Settings settings) {
        chaos.set(settings);
        return chaos.settings();
    }
}
