package com.schmebb.bank;

import com.schmebb.ledger.AccountType;
import com.schmebb.ledger.Ledger;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * On startup: opens the system accounts and this bank's customers, then registers their phones.
 * Registrations that fail because the directory isn't up yet are retried until they succeed, so
 * the services can be started in any order.
 */
@Component
class AccountSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AccountSeeder.class);

    private final Ledger ledger;
    private final DirectoryClient directory;
    private final String bankCode;
    private final Queue<SeedData.Customer> unregistered = new ConcurrentLinkedQueue<>();

    AccountSeeder(Ledger ledger, DirectoryClient directory, @Value("${bank.code}") String bankCode) {
        this.ledger = ledger;
        this.directory = directory;
        this.bankCode = bankCode;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<SeedData.Customer> customers = SeedData.CUSTOMERS.get(bankCode);
        if (customers == null) {
            throw new IllegalStateException("No seed data for bank.code=" + bankCode
                    + "; known banks: " + SeedData.CUSTOMERS.keySet());
        }

        ledger.openAccount(BankAccounts.OUTGOING_SUSPENSE, AccountType.SYSTEM);
        ledger.openAccount(BankAccounts.SCHEME_SETTLEMENT, AccountType.SYSTEM);
        for (SeedData.Customer customer : customers) {
            ledger.openAccount(customer.accountId(), AccountType.CUSTOMER);
            ledger.transfer(BankAccounts.FUNDING, customer.accountId(), customer.openingBalanceCents(),
                    "opening-balance:" + customer.accountId());
            if (customer.frozen()) {
                ledger.setFrozen(customer.accountId(), true);
            }
        }
        log.info("{}: seeded {} customer accounts", bankCode, customers.size());

        unregistered.addAll(customers);
        registerPhones();
    }

    @Scheduled(initialDelayString = "${bank.registration-retry-interval}",
            fixedDelayString = "${bank.registration-retry-interval}")
    void registerPhones() {
        for (int i = unregistered.size(); i > 0; i--) {
            SeedData.Customer customer = unregistered.poll();
            if (customer != null && !directory.register(customer.phone(), bankCode, customer.accountId())) {
                unregistered.add(customer);
            }
        }
    }
}
