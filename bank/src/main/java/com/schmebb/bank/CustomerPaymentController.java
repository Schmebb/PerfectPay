package com.schmebb.bank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by this bank's customers (their app). Every outcome is a 200 with a status. */
@RestController
@RequestMapping("/customer-payments")
class CustomerPaymentController {

    private final OutgoingPayments outgoingPayments;

    CustomerPaymentController(OutgoingPayments outgoingPayments) {
        this.outgoingPayments = outgoingPayments;
    }

    /** Body: {@code {"paymentId": "...", "fromAccount": "thandi", "toPhone": "0831234567", "amountCents": 10000}}. */
    @PostMapping
    OutgoingPayment pay(@RequestBody OutgoingPayments.Request request) {
        return outgoingPayments.pay(request);
    }

    @GetMapping("/{paymentId}")
    ResponseEntity<OutgoingPayment> status(@PathVariable String paymentId) {
        return ResponseEntity.of(outgoingPayments.find(paymentId));
    }
}
