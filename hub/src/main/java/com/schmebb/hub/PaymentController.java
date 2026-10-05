package com.schmebb.hub;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Called by the paying bank. Every outcome, including REJECTED and PENDING, is a 200. */
@RestController
@RequestMapping("/payments")
class PaymentController {

    private final Payments payments;

    PaymentController(Payments payments) {
        this.payments = payments;
    }

    @PostMapping
    PaymentResult submit(@RequestBody PaymentInstruction instruction) {
        return payments.submit(instruction);
    }

    /** Status enquiry: "what actually happened to this payment?" */
    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentResult> status(@PathVariable String paymentId) {
        return ResponseEntity.of(payments.status(paymentId));
    }
}
