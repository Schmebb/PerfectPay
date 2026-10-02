package com.schmebb.directory;

/** A registered proxy: payments to {@code phone} go to {@code accountId} at {@code bankCode}. */
public record Proxy(String phone, String bankCode, String accountId) {
}
