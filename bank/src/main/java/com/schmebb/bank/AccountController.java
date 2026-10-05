package com.schmebb.bank;

import com.schmebb.ledger.AccountType;
import com.schmebb.ledger.Ledger;
import com.schmebb.ledger.Posting;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountController {

    record AccountView(String id, AccountType type, boolean frozen, long balanceCents, List<Posting> entries) {
    }

    private final Ledger ledger;

    AccountController(Ledger ledger) {
        this.ledger = ledger;
    }

    @GetMapping("/accounts/{id}")
    ResponseEntity<AccountView> account(@PathVariable String id) {
        return ResponseEntity.of(ledger.account(id).map(account -> new AccountView(
                account.id(), account.type(), account.frozen(), ledger.balance(id), ledger.postings(id))));
    }
}
