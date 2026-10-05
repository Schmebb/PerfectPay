package com.schmebb.bank;

import org.springframework.stereotype.Component;

/**
 * Fault injection for /incoming-payments, switched on through /admin/chaos. Lets you see what
 * the hub and the paying bank do when this bank misbehaves.
 */
@Component
class Chaos {

    enum Mode {
        /** Behave normally. */
        NONE,
        /** Wait {@code delayMs} before processing each incoming payment. */
        SLOW,
        /**
         * Credit the customer, then wait {@code delayMs} before answering, so the caller has
         * given up by the time the answer comes. Only payments credited by this request are
         * affected; a retry of one already credited is answered at once, as a recovered network would be.
         */
        DROP_RESPONSE
    }

    record Settings(Mode mode, long delayMs) {
    }

    private volatile Settings settings = new Settings(Mode.NONE, 0);

    Settings settings() {
        return settings;
    }

    void set(Settings settings) {
        this.settings = settings.mode() == null ? new Settings(Mode.NONE, 0) : settings;
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
