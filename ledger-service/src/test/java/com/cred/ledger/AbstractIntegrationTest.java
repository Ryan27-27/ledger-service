package com.cred.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared base for tests that need the real stack: one Postgres container for
 * the whole test run (singleton pattern -- started once, reaped by Ryuk when
 * the JVM exits), the no-redis profile (in-memory rate limiter), and a
 * bootstrapped admin. Skipped automatically when Docker is unavailable.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("no-redis")
@Testcontainers(disabledWithoutDocker = true)
public abstract class AbstractIntegrationTest {

    protected static final String ADMIN_USER = "test-admin";
    protected static final String ADMIN_PASSWORD = "admin-password-123";
    protected static final String USER_PASSWORD = "password-123";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("app.admin.username", () -> ADMIN_USER);
        registry.add("app.admin.password", () -> ADMIN_PASSWORD);
        // the suite hammers one client IP / a few accounts; don't let the limiter interfere
        registry.add("app.rate-limit.auth.capacity", () -> "100000");
        registry.add("app.rate-limit.debit.capacity", () -> "100000");
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    protected String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected JsonNode register(String username) throws Exception {
        return readBody(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", USER_PASSWORD))))
                .andExpect(status().isCreated()));
    }

    protected JsonNode login(String username, String password) throws Exception {
        return readBody(mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()));
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_USER, ADMIN_PASSWORD).get("token").asText();
    }

    protected ResultActions postJson(String url, String token, Object body) throws Exception {
        return mvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    protected ResultActions getJson(String url, String token) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    protected ResultActions credit(String token, String accountId, String amount, String key) throws Exception {
        return postJson("/api/v1/accounts/" + accountId + "/credits", token,
                Map.of("amount", amount, "referenceId", "test-credit", "idempotencyKey", key));
    }

    protected ResultActions debit(String token, String accountId, String amount, String key) throws Exception {
        return postJson("/api/v1/accounts/" + accountId + "/debits", token,
                Map.of("amount", amount, "referenceId", "test-debit", "idempotencyKey", key));
    }

    protected JsonNode readBody(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
