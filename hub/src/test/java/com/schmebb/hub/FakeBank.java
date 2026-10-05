package com.schmebb.hub;

import com.schmebb.common.PaymentInstruction;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * A stand-in for a bank's /incoming-payments, behaving according to the creditor account:
 * "nobody" is rejected, "broken" gets a 400, "slow" takes 3s while {@link #slow} is true,
 * anything else is accepted.
 */
final class FakeBank {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final Queue<String> received = new ConcurrentLinkedQueue<>();
    volatile boolean slow = true;

    FakeBank() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/incoming-payments", this::handle);
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    List<String> received() {
        return List.copyOf(received);
    }

    void reset() {
        received.clear();
        slow = true;
    }

    private void handle(HttpExchange exchange) throws IOException {
        PaymentInstruction payment = JSON.readValue(exchange.getRequestBody(), PaymentInstruction.class);
        received.add(payment.paymentId());
        String account = payment.creditorAccount();
        if (account.equals("broken")) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        if (account.equals("slow") && slow) {
            try {
                Thread.sleep(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        String body = account.equals("nobody")
                ? "{\"paymentId\":\"%s\",\"status\":\"REJECTED\",\"reason\":\"Unknown account: nobody\"}"
                : "{\"paymentId\":\"%s\",\"status\":\"ACCEPTED\",\"reason\":null}";
        byte[] bytes = body.formatted(payment.paymentId()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
