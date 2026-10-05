package com.schmebb.hub;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SettlementController {

    private final Payments payments;
    private final HubProperties properties;
    private final Clock clock;

    SettlementController(Payments payments, HubProperties properties, Clock clock) {
        this.payments = payments;
        this.properties = properties;
        this.clock = clock;
    }

    /** Today's settlement by default; {@code ?date=2026-10-12} for another day. */
    @GetMapping("/settlement")
    Settlement.Report settlement(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(properties.timeZone()));
        return Settlement.report(payments.all(), properties.participants().keySet(), day, properties.timeZone());
    }
}
