package com.cred.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With app.demo-mode=true an account owner may credit/reverse on their OWN account only. */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "app.demo-mode=true")
class DemoModeApiIntegrationTest extends AbstractIntegrationTest {

    @Test
    void metaEndpoint_reportsDemoMode_withoutAuthentication() throws Exception {
        mvc.perform(get("/api/v1/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true));
    }

    @Test
    void ownerCanCreditAndReverseOnTheirOwnAccount() throws Exception {
        JsonNode user = register(uniqueName("demo"));
        String token = user.get("token").asText();
        String account = user.get("accountId").asText();

        JsonNode credit = readBody(credit(token, account, "25.00", "c-" + UUID.randomUUID()).andExpect(status().isCreated()));

        postJson("/api/v1/accounts/" + account + "/entries/" + credit.get("id").asText() + "/reverse", token,
                Map.of("reason", "changed my mind", "idempotencyKey", "r-" + UUID.randomUUID()))
                .andExpect(status().isCreated());
    }

    @Test
    void demoModeDoesNotLetYouCreditSomeoneElse() throws Exception {
        JsonNode mallory = register(uniqueName("mallory"));
        JsonNode victim = register(uniqueName("victim"));

        credit(mallory.get("token").asText(), victim.get("accountId").asText(), "10.00", "c-" + UUID.randomUUID())
                .andExpect(status().isForbidden());
    }
}
