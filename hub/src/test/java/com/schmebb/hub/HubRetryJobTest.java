package com.schmebb.hub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"hub.forward-timeout=1s", "hub.retry-interval=200ms"})
@AutoConfigureMockMvc
class HubRetryJobTest {

    private static final FakeBank BANK;

    static {
        try {
            BANK = new FakeBank();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void participants(DynamicPropertyRegistry registry) {
        registry.add("hub.participants[BANK_A]", BANK::url);
        registry.add("hub.participants[BANK_B]", BANK::url);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void pendingPaymentIsResolvedWithoutTheSenderRetrying() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"paymentId": "p-1", "debtorBank": "BANK_A", "debtorAccount": "thandi",
                         "creditorBank": "BANK_B", "creditorAccount": "slow", "amountCents": 100}
                        """))
                .andExpect(jsonPath("$.status").value("PENDING"));

        BANK.slow = false;

        long deadline = System.currentTimeMillis() + 5_000;
        String status;
        do {
            Thread.sleep(100);
            status = mvc.perform(get("/payments/p-1")).andReturn().getResponse().getContentAsString();
        } while (!status.contains("ACCEPTED") && System.currentTimeMillis() < deadline);
        assertThat(status).contains("\"status\":\"ACCEPTED\"");
    }
}
