package com.schmebb.bank;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Called by the hub. A business rejection is still a 200: the request worked, the payment didn't. */
@RestController
class IncomingPaymentController {

    private final IncomingPayments incomingPayments;
    private final Chaos chaos;

    IncomingPaymentController(IncomingPayments incomingPayments, Chaos chaos) {
        this.incomingPayments = incomingPayments;
        this.chaos = chaos;
    }

    @PostMapping("/incoming-payments")
    PaymentResult receive(@RequestBody PaymentInstruction payment) {
        Chaos.Settings settings = chaos.settings();
        if (settings.mode() == Chaos.Mode.SLOW) {
            Chaos.sleep(settings.delayMs());
        }
        IncomingPayments.Received received = incomingPayments.receive(payment);
        if (settings.mode() == Chaos.Mode.DROP_RESPONSE && received.firstTime()) {
            Chaos.sleep(settings.delayMs());
        }
        return received.result();
    }
}
