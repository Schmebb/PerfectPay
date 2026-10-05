package com.schmebb.hub;

import com.schmebb.common.PaymentInstruction;
import com.schmebb.common.PaymentResult;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Forwards payments to payee banks and says how sure we can be about what happened. */
@Component
class BankClient {

    sealed interface Delivery {
        /** The bank answered ACCEPTED or REJECTED. */
        record Answered(PaymentResult result) implements Delivery {
        }

        /** The bank certainly never processed the payment (couldn't connect, or it refused the request). */
        record NotDelivered(String reason) implements Delivery {
        }

        /** No answer in time. The bank may or may not have credited the customer. */
        record Unknown(String reason) implements Delivery {
        }
    }

    private final RestClient http;

    BankClient(HubProperties properties) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(properties.forwardTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(properties.forwardTimeout());
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    Delivery send(URI bank, PaymentInstruction payment) {
        try {
            PaymentResult result = http.post().uri(bank.resolve("/incoming-payments"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payment)
                    .retrieve()
                    .body(PaymentResult.class);
            return new Delivery.Answered(result);
        } catch (HttpClientErrorException e) {
            return new Delivery.NotDelivered("Bank refused the request: " + e.getStatusCode());
        } catch (RestClientException e) {
            if (hasCause(e, ConnectException.class) || hasCause(e, HttpConnectTimeoutException.class)) {
                return new Delivery.NotDelivered("Bank is unreachable");
            }
            return new Delivery.Unknown(e.getMessage());
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
