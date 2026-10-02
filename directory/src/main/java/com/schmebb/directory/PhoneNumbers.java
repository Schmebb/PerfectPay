package com.schmebb.directory;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * South African mobile numbers, accepted as {@code 0821234567} or {@code +27821234567} and
 * stored as the latter, so the same number can't be registered twice in different formats.
 */
final class PhoneNumbers {

    private static final Pattern LOCAL = Pattern.compile("0([6-8]\\d{8})");
    private static final Pattern INTERNATIONAL = Pattern.compile("\\+27([6-8]\\d{8})");

    private PhoneNumbers() {
    }

    /** The number in {@code +27} form, or empty if it isn't a valid SA mobile number. */
    static Optional<String> normalise(String phone) {
        if (phone == null) {
            return Optional.empty();
        }
        var local = LOCAL.matcher(phone);
        if (local.matches()) {
            return Optional.of("+27" + local.group(1));
        }
        return INTERNATIONAL.matcher(phone).matches() ? Optional.of(phone) : Optional.empty();
    }
}
