package com.schmebb.directory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** In-memory store of proxies, keyed by phone number in {@code +27} form. Thread-safe. */
@Service
public class ProxyRegistry {

    private final Map<String, Proxy> proxies = new ConcurrentHashMap<>();

    public Proxy register(Proxy request) {
        String phone = PhoneNumbers.normalise(request.phone())
                .orElseThrow(() -> new InvalidProxyException("Not a valid South African mobile number: " + request.phone()));
        if (isBlank(request.bankCode())) {
            throw new InvalidProxyException("bankCode is required");
        }
        if (isBlank(request.accountId())) {
            throw new InvalidProxyException("accountId is required");
        }
        Proxy proxy = new Proxy(phone, request.bankCode(), request.accountId());
        // putIfAbsent is atomic, so two simultaneous registrations of one number can't both win.
        if (proxies.putIfAbsent(phone, proxy) != null) {
            throw new DuplicateProxyException(phone);
        }
        return proxy;
    }

    /** Empty if the number is unknown or not a valid number at all. */
    public Optional<Proxy> resolve(String phone) {
        return PhoneNumbers.normalise(phone).map(proxies::get);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
