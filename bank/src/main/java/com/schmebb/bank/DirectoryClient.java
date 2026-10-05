package com.schmebb.bank;

import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
class DirectoryClient {

    private static final Logger log = LoggerFactory.getLogger(DirectoryClient.class);

    /** Where payments to a phone number should go. */
    record Proxy(String phone, String bankCode, String accountId) {
    }

    static class DirectoryUnavailableException extends RuntimeException {
        DirectoryUnavailableException(Throwable cause) {
            super("Directory is unavailable", cause);
        }
    }

    private final RestClient http;

    DirectoryClient(@Value("${directory.url}") String directoryUrl) {
        this.http = RestClient.create(directoryUrl);
    }

    /**
     * Registers a phone number for an account. Failures are logged, not thrown, so the bank still
     * starts (and can be tested on its own) when the directory is down.
     *
     * @return false only if the directory couldn't be reached, meaning it's worth trying again later
     */
    boolean register(String phone, String bankCode, String accountId) {
        try {
            http.post().uri("/proxies")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("phone", phone, "bankCode", bankCode, "accountId", accountId))
                    .retrieve()
                    .toBodilessEntity();
            log.info("Registered {} -> {}/{}", phone, bankCode, accountId);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) {
                log.info("{} is already registered with the directory", phone);
            } else {
                log.warn("Directory rejected {}: {}", phone, e.getResponseBodyAsString());
            }
        } catch (RestClientException e) {
            log.warn("Could not register {} with the directory, will retry: {}", phone, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Looks up who a phone number belongs to. Empty if it isn't registered.
     *
     * @throws DirectoryUnavailableException if the directory can't be reached
     */
    Optional<Proxy> resolve(String phone) {
        try {
            return Optional.ofNullable(http.get().uri("/proxies/{phone}", phone).retrieve().body(Proxy.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            throw new DirectoryUnavailableException(e);
        }
    }
}
