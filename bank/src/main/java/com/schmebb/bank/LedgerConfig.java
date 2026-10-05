package com.schmebb.bank;

import com.schmebb.ledger.Ledger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class LedgerConfig {

    @Bean
    Ledger ledger() {
        return new Ledger();
    }
}
