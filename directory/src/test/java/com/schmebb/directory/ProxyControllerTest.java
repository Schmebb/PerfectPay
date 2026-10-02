package com.schmebb.directory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@WebMvcTest(ProxyController.class)
@Import(ProxyRegistry.class)
// The registry is in-memory, so give every test a fresh one.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProxyControllerTest {

    @Autowired
    private MockMvc mvc;

    private ResultActions register(String phone, String bankCode, String accountId) throws Exception {
        String body = """
                {"phone": "%s", "bankCode": "%s", "accountId": "%s"}
                """.formatted(phone, bankCode, accountId);
        return mvc.perform(post("/proxies").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void registersAndResolvesAProxy() throws Exception {
        register("+27821234567", "BANK_A", "thandi")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+27821234567"))
                .andExpect(jsonPath("$.bankCode").value("BANK_A"))
                .andExpect(jsonPath("$.accountId").value("thandi"));

        mvc.perform(get("/proxies/+27821234567"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankCode").value("BANK_A"))
                .andExpect(jsonPath("$.accountId").value("thandi"));
    }

    @Test
    void localFormatIsStoredInInternationalForm() throws Exception {
        register("0821234567", "BANK_A", "thandi")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+27821234567"));

        mvc.perform(get("/proxies/0821234567")).andExpect(status().isOk());
        mvc.perform(get("/proxies/+27821234567")).andExpect(status().isOk());
    }

    @Test
    void unknownNumberIs404() throws Exception {
        mvc.perform(get("/proxies/+27821234567")).andExpect(status().isNotFound());
    }

    @Test
    void badlyFormattedLookupIs404() throws Exception {
        mvc.perform(get("/proxies/hello")).andExpect(status().isNotFound());
    }

    @Test
    void alreadyRegisteredNumberIsRejected() throws Exception {
        register("+27821234567", "BANK_A", "thandi").andExpect(status().isCreated());

        register("+27821234567", "BANK_B", "someone-else")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Phone number is already registered: +27821234567"));

        mvc.perform(get("/proxies/+27821234567"))
                .andExpect(jsonPath("$.bankCode").value("BANK_A"));
    }

    @Test
    void sameNumberInOtherFormatIsRejected() throws Exception {
        register("+27821234567", "BANK_A", "thandi").andExpect(status().isCreated());

        register("0821234567", "BANK_B", "someone-else").andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",               // empty
            "hello",          // not a number
            "821234567",      // missing leading 0
            "082123456",      // too short
            "08212345678",    // too long
            "0121234567",     // landline, not mobile
            "+44821234567",   // not South African
            "+27 82 123 4567" // spaces
    })
    void badlyFormattedNumberIsRejected(String phone) throws Exception {
        register(phone, "BANK_A", "thandi")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Not a valid South African mobile number: " + phone));
    }

    @Test
    void missingBankCodeIsRejected() throws Exception {
        mvc.perform(post("/proxies").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone": "+27821234567", "accountId": "thandi"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankAccountIdIsRejected() throws Exception {
        register("+27821234567", "BANK_A", " ").andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        mvc.perform(post("/proxies").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }
}
