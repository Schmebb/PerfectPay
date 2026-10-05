package com.schmebb.hub;

import java.util.Locale;

final class Money {

    private Money() {
    }

    /** 35000 → "R350.00", 300000 → "R3,000.00", the same on every machine. */
    static String format(long cents) {
        String sign = cents < 0 ? "-" : "";
        long abs = Math.abs(cents);
        return String.format(Locale.ROOT, "%sR%,d.%02d", sign, abs / 100, abs % 100);
    }
}
