package com.schmebb.bank;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Sends outgoing payments to the hub and says how sure we can be about what happened. */
@Component
class HubClient {

    sealed interface Delivery {
        /** The hub answered: ACCEPTED, REJECTED, or PENDING if it is still waiting on the payee bank. */
        record Answered(PaymentResult result) implements Delivery {
        }

        /** The hub certainly never took the payment (couldn't connect, or it refused the request). */
        record NotDelivered(String reason) implements Delivery {
        }

        /** No answer in time. The hub may or may not have the payment. */
        record Unknown(String reason) implements Delivery {
        }
    }

    sealed interface Enquiry {
        /** The hub has the payment: ACCEPTED, REJECTED, or still PENDING. */
        record Known(PaymentResult result) implements Enquiry {
        }

        /** The hub has no record of the payment. */
        record NotFound() implements Enquiry {
        }

        /** Couldn't ask: the hub is down or didn't answer. */
        record Unavailable(String reason) implements Enquiry {
        }
    }

    private final RestClient http;

    HubClient(@Value("${hub.url}") String hubUrl, @Value("${hub.timeout}") Duration timeout) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(hubUrl).requestFactory(factory).build();
    }

    Delivery send(PaymentInstruction payment) {
        try {
            PaymentResult result = http.post().uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payment)
                    .retrieve()
                    .body(PaymentResult.class);
            return new Delivery.Answered(result);
        } catch (HttpClientErrorException e) {
            return new Delivery.NotDelivered("Hub refused the request: " + e.getStatusCode());
        } catch (RestClientException e) {
            if (hasCause(e, ConnectException.class) || hasCause(e, HttpConnectTimeoutException.class)) {
                return new Delivery.NotDelivered("Hub is unreachable");
            }
            return new Delivery.Unknown(e.getMessage());
        }
    }

    /** Status enquiry: "what actually happened to this payment?" */
    Enquiry enquire(String paymentId) {
        try {
            return new Enquiry.Known(http.get().uri("/payments/{id}", paymentId).retrieve().body(PaymentResult.class));
        } catch (HttpClientErrorException.NotFound e) {
            return new Enquiry.NotFound();
        } catch (RestClientException e) {
            return new Enquiry.Unavailable(e.getMessage());
        }
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
