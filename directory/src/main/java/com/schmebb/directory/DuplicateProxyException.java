package com.schmebb.directory;

public class DuplicateProxyException extends RuntimeException {

    public DuplicateProxyException(String phone) {
        super("Phone number is already registered: " + phone);
    }
}
