package com.schmebb.bank;

import java.util.List;
import java.util.Map;

/** The demo customers each bank starts with, keyed by {@code bank.code}. */
final class SeedData {

    record Customer(String accountId, String phone, long openingBalanceCents, boolean frozen) {
    }

    static final Map<String, List<Customer>> CUSTOMERS = Map.of(
            "BANK_A", List.of(
                    new Customer("thandi", "+27821234567", 500_00, false),
                    new Customer("lerato", "+27721234567", 300_00, false),
                    new Customer("zanele", "+27841234567", 50_00, true)),
            "BANK_B", List.of(
                    new Customer("sipho", "+27831234567", 250_00, false),
                    new Customer("bongani", "+27731234567", 100_00, true)));

    private SeedData() {
    }
}
