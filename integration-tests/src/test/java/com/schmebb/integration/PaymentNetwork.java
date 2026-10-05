package com.schmebb.integration;

import com.schmebb.bank.BankApplication;
import com.schmebb.directory.DirectoryApplication;
import com.schmebb.hub.HubApplication;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * The whole scheme running in this JVM: directory, hub, BANK_A and BANK_B, each a real Spring
 * Boot app on its own port, talking to each other over HTTP.
 *
 * <p>Timeouts are shortened so the tests run quickly, but keep their production ordering with
 * one deliberate exception: the banks wait less for the hub (1s) than the hub waits for a payee
 * bank (3s). That lets a test make BANK_A time out while the hub is still waiting, which is the
 * lost-response case a bank has to handle.
 */
final class PaymentNetwork implements AutoCloseable {

    static final long SCHEME_LIMIT_CENTS = 200_00;

    private static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() {
    };

    private final int directoryPort = freePort();
    private final int hubPort = freePort();
    private final int bankAPort = freePort();
    private final int bankBPort = freePort();
    private final List<ConfigurableApplicationContext> apps = new ArrayList<>();
    private ConfigurableApplicationContext bankB;

    final RestClient directory = client(directoryPort);
    final RestClient hub = client(hubPort);
    final RestClient bankA = client(bankAPort);
    final RestClient bankBClient = client(bankBPort);

    PaymentNetwork() {
        // Directory first: the banks register their customers' phone numbers with it on startup.
        apps.add(start(DirectoryApplication.class, "directory", directoryPort));
        apps.add(start(HubApplication.class, "hub", hubPort,
                "--hub.participants[BANK_A]=" + url(bankAPort),
                "--hub.participants[BANK_B]=" + url(bankBPort),
                "--hub.scheme-limit-cents=" + SCHEME_LIMIT_CENTS,
                "--hub.forward-timeout=3s",
                "--hub.retry-interval=500ms",
                "--hub.time-zone=Africa/Johannesburg"));
        apps.add(startBank("BANK_A", bankAPort));
        bankB = startBank("BANK_B", bankBPort);
        apps.add(bankB);
    }

    private ConfigurableApplicationContext startBank(String code, int port) {
        return start(BankApplication.class, "bank-" + code, port,
                "--bank.code=" + code,
                "--hub.url=" + url(hubPort),
                "--directory.url=" + url(directoryPort),
                "--hub.timeout=1s",
                "--bank.enquiry-interval=500ms",
                "--bank.refund-unknown-after=60s",
                "--bank.registration-retry-interval=500ms");
    }

    /**
     * Every module ships its own application.properties and only one of them wins on a shared
     * classpath, so every setting an app needs is passed explicitly here.
     */
    private static ConfigurableApplicationContext start(Class<?> app, String name, int port, String... extra) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.application.name=" + name,
                "--server.port=" + port,
                "--server.shutdown=immediate",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN"));
        args.addAll(List.of(extra));
        return new SpringApplicationBuilder(app).run(args.toArray(String[]::new));
    }

    void stopBankB() {
        bankB.close();
        apps.remove(bankB);
    }

    boolean bankBRunning() {
        return bankB.isActive();
    }

    // --- calls --------------------------------------------------------------------------------

    static Map<String, Object> customerPayment(RestClient bank, String paymentId, String from, String toPhone,
                                               long amountCents) {
        return bank.post().uri("/customer-payments")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("paymentId", paymentId, "fromAccount", from, "toPhone", toPhone, "amountCents", amountCents))
                .retrieve()
                .body(JSON_OBJECT);
    }

    static Map<String, Object> get(RestClient client, String path) {
        return client.get().uri(path).retrieve().body(JSON_OBJECT);
    }

    static Map<String, Object> post(RestClient client, String path, Object body) {
        var request = client.post().uri(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request = request.body(body);
        }
        return request.retrieve().body(JSON_OBJECT);
    }

    static void setChaos(RestClient bank, String mode, long delayMs) {
        bank.put().uri("/admin/chaos")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("mode", mode, "delayMs", delayMs))
                .retrieve()
                .toBodilessEntity();
    }

    static long balance(RestClient bank, String account) {
        return ((Number) get(bank, "/accounts/" + account).get("balanceCents")).longValue();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Number> ledger(RestClient bank) {
        return (Map<String, Number>) get(bank, "/admin/ledger").get("balancesCents");
    }

    static long ledgerTotal(RestClient bank) {
        return ((Number) get(bank, "/admin/ledger").get("totalCents")).longValue();
    }

    @Override
    public void close() {
        for (int i = apps.size() - 1; i >= 0; i--) {
            apps.get(i).close();
        }
    }

    private static RestClient client(int port) {
        return RestClient.create(url(port));
    }

    private static String url(int port) {
        return "http://localhost:" + port;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
