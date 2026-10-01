package com.cred.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end HTTP tests: auth, authorization, idempotency semantics, validation, pagination, reversals. */
@Testcontainers(disabledWithoutDocker = true)
class LedgerApiIntegrationTest extends AbstractIntegrationTest {

    private static void assertAmount(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                "expected " + expected + " but was " + actual);
    }

    // ------------------------------------------------------------------ auth

    @Test
    void requestsWithoutAToken_areRejectedWithJson401() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID() + "/balance"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void registration_isCaseInsensitive_andRejectsDuplicates() throws Exception {
        String name = uniqueName("dup");
        register(name);

        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name.toUpperCase(), "password", USER_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
    }

    @Test
    void wrongPassword_returns401() throws Exception {
        String name = uniqueName("pw");
        register(name);

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", name, "password", "not-the-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refreshTokens_rotate_andReplayingAnOldOneRevokesEverySession() throws Exception {
        String name = uniqueName("rot");
        String first = register(name).get("refreshToken").asText();

        JsonNode rotated = readBody(mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", first))))
                .andExpect(status().isOk()));
        String second = rotated.get("refreshToken").asText();
        assertTrue(!first.equals(second));

        // replaying the already-used token is rejected...
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", first))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // ...and treated as theft: the newer token is revoked too
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", second))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedJson_returns400InTheStandardShape() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    // --------------------------------------------------------- authorization

    @Test
    void aUserCannotTouchAnotherUsersAccount() throws Exception {
        JsonNode alice = register(uniqueName("alice"));
        JsonNode bob = register(uniqueName("bob"));

        getJson("/api/v1/accounts/" + alice.get("accountId").asText() + "/balance", bob.get("token").asText())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void aNormalUserCannotCreditTheirOwnAccount() throws Exception {
        JsonNode user = register(uniqueName("greedy"));

        credit(user.get("token").asText(), user.get("accountId").asText(), "1000.00", "k-" + UUID.randomUUID())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void adminEndpoints_requireTheAdminRole() throws Exception {
        JsonNode user = register(uniqueName("plain"));

        getJson("/api/v1/admin/accounts", user.get("token").asText()).andExpect(status().isForbidden());

        JsonNode page = readBody(getJson("/api/v1/admin/accounts?q=" + user.get("username").asText(), adminToken())
                .andExpect(status().isOk()));
        assertEquals(1, page.get("totalElements").asInt());
        assertEquals(user.get("username").asText(), page.get("items").get(0).get("username").asText());
    }

    // ---------------------------------------------------------------- ledger

    @Test
    void creditDebitHistoryAndAudit_addUp() throws Exception {
        JsonNode user = register(uniqueName("flow"));
        String account = user.get("accountId").asText();
        String userToken = user.get("token").asText();
        String admin = adminToken();

        credit(admin, account, "100.00", "c-" + UUID.randomUUID()).andExpect(status().isCreated());
        debit(userToken, account, "30.00", "d-" + UUID.randomUUID()).andExpect(status().isCreated());

        assertAmount("70.00", readBody(getJson("/api/v1/accounts/" + account + "/balance", userToken)).get("balance"));

        JsonNode history = readBody(getJson("/api/v1/accounts/" + account + "/entries", userToken)
                .andExpect(status().isOk()));
        assertEquals(2, history.get("totalElements").asInt());
        // newest first, each row carrying the balance right after it
        assertEquals("DEBIT", history.get("items").get(0).get("type").asText());
        assertAmount("70.00", history.get("items").get(0).get("runningBalance"));
        assertAmount("100.00", history.get("items").get(1).get("runningBalance"));

        getJson("/api/v1/accounts/" + account + "/audit", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").value(true));
    }

    @Test
    void replayingARequest_returns200WithTheOriginalEntry() throws Exception {
        JsonNode user = register(uniqueName("replay"));
        String account = user.get("accountId").asText();
        String admin = adminToken();
        String key = "c-" + UUID.randomUUID();

        JsonNode first = readBody(credit(admin, account, "10.00", key).andExpect(status().isCreated()));
        JsonNode second = readBody(credit(admin, account, "10.00", key).andExpect(status().isOk()));

        assertEquals(first.get("id").asText(), second.get("id").asText());
        assertAmount("10.00", readBody(getJson("/api/v1/accounts/" + account + "/balance", admin)).get("balance"));
    }

    @Test
    void reusingAKeyForADifferentRequest_returns409() throws Exception {
        JsonNode user = register(uniqueName("reuse"));
        String account = user.get("accountId").asText();
        String admin = adminToken();
        String key = "c-" + UUID.randomUUID();

        credit(admin, account, "10.00", key).andExpect(status().isCreated());
        credit(admin, account, "99.00", key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSE"));
    }

    @Test
    void redeemingMoreThanTheBalance_returns422() throws Exception {
        JsonNode user = register(uniqueName("broke"));

        debit(user.get("token").asText(), user.get("accountId").asText(), "5.00", "d-" + UUID.randomUUID())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void invalidAmounts_return400WithFieldErrors() throws Exception {
        JsonNode user = register(uniqueName("valid"));
        String account = user.get("accountId").asText();
        String admin = adminToken();

        credit(admin, account, "-5.00", "k-" + UUID.randomUUID())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors.amount").exists());
        credit(admin, account, "0.00", "k-" + UUID.randomUUID()).andExpect(status().isBadRequest());
        credit(admin, account, "1.234", "k-" + UUID.randomUUID()).andExpect(status().isBadRequest());
    }

    @Test
    void reversal_netsToZero_keepsAuditConsistent_andCannotBeRepeated() throws Exception {
        JsonNode user = register(uniqueName("rev"));
        String account = user.get("accountId").asText();
        String admin = adminToken();

        JsonNode credit = readBody(credit(admin, account, "50.00", "c-" + UUID.randomUUID()).andExpect(status().isCreated()));
        String entryId = credit.get("id").asText();

        JsonNode compensation = readBody(postJson("/api/v1/accounts/" + account + "/entries/" + entryId + "/reverse", admin,
                Map.of("reason", "granted in error", "idempotencyKey", "r-" + UUID.randomUUID()))
                .andExpect(status().isCreated()));
        assertEquals(entryId, compensation.get("reversalOf").asText());
        assertEquals("DEBIT", compensation.get("type").asText());

        assertAmount("0.00", readBody(getJson("/api/v1/accounts/" + account + "/balance", admin)).get("balance"));
        getJson("/api/v1/accounts/" + account + "/audit", admin).andExpect(jsonPath("$.consistent").value(true));

        postJson("/api/v1/accounts/" + account + "/entries/" + entryId + "/reverse", admin,
                Map.of("idempotencyKey", "r-" + UUID.randomUUID()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_REVERSAL"));
    }

    @Test
    void history_isPaginated_newestFirst() throws Exception {
        JsonNode user = register(uniqueName("pages"));
        String account = user.get("accountId").asText();
        String admin = adminToken();
        for (int i = 1; i <= 3; i++) {
            credit(admin, account, i + ".00", "c" + i + "-" + UUID.randomUUID()).andExpect(status().isCreated());
        }

        JsonNode first = readBody(getJson("/api/v1/accounts/" + account + "/entries?size=2&page=0", admin));
        JsonNode second = readBody(getJson("/api/v1/accounts/" + account + "/entries?size=2&page=1", admin));

        assertEquals(3, first.get("totalElements").asInt());
        assertEquals(2, first.get("totalPages").asInt());
        assertEquals(2, first.get("items").size());
        assertEquals(1, second.get("items").size());
        assertAmount("3.00", first.get("items").get(0).get("amount")); // newest first
        assertAmount("1.00", second.get("items").get(0).get("amount"));
        assertAmount("6.00", first.get("items").get(0).get("runningBalance"));
    }
}
