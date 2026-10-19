package com.paymentsystem.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.entity.PaymentStatus;
import com.paymentsystem.entity.PaymentTransaction;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.service.CursorCodecService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class PaymentPaginationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private CursorCodecService cursorCodecService;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String API_KEY = "apexpay_admin_secret_key_2026";
    private static final String BASE_URL = "/api/v1/payments/transactions";

    @BeforeEach
    void setUp() {
        paymentRepository.deleteAll();
    }

    private PaymentTransaction createTx(String paymentId, Instant createdAt, PaymentStatus status) {
        PaymentTransaction tx = new PaymentTransaction(
                paymentId,
                "IDEM_" + paymentId,
                "USR_DEEKSHA",
                new BigDecimal("500.0000"),
                "USD",
                "CREDIT_CARD",
                "Transaction " + paymentId
        );
        tx.setStatus(status);
        tx.setCreatedAt(createdAt);
        tx.setUpdatedAt(createdAt);
        return paymentRepository.saveAndFlush(tx);
    }

    @Test
    @DisplayName("Scenario 1 & 2: First and second pages return newest transactions in correct keyset order")
    void testFirstAndSecondPageNavigation() throws Exception {
        Instant baseTime = Instant.parse("2026-10-19T10:00:00Z");
        List<PaymentTransaction> created = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            created.add(createTx("PAY_" + i, baseTime.plus(i, ChronoUnit.MINUTES), PaymentStatus.SUCCESS));
        }

        // Page 1 with limit 2
        MvcResult res1 = mockMvc.perform(get(BASE_URL + "?limit=2")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.limit").value(2))
                .andExpect(jsonPath("$.pagination.hasMore").value(true))
                .andExpect(jsonPath("$.pagination.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("PAY_5"))
                .andExpect(jsonPath("$.data[1].id").value("PAY_4"))
                .andReturn();

        JsonNode json1 = objectMapper.readTree(res1.getResponse().getContentAsString());
        String cursor1 = json1.get("pagination").get("nextCursor").asText();

        // Page 2 using cursor1
        MvcResult res2 = mockMvc.perform(get(BASE_URL + "?limit=2&cursor=" + cursor1)
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.limit").value(2))
                .andExpect(jsonPath("$.pagination.hasMore").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("PAY_3"))
                .andExpect(jsonPath("$.data[1].id").value("PAY_2"))
                .andReturn();

        JsonNode json2 = objectMapper.readTree(res2.getResponse().getContentAsString());
        String cursor2 = json2.get("pagination").get("nextCursor").asText();

        // Page 3 (final page)
        mockMvc.perform(get(BASE_URL + "?limit=2&cursor=" + cursor2)
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("PAY_1"));
    }

    @Test
    @DisplayName("Scenario 3, 4, 5: hasMore, nextCursor, and null cursor on final page are strictly correct")
    void testHasMoreAndNextCursorSemantics() throws Exception {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        createTx("PAY_A", now.plusSeconds(10), PaymentStatus.SUCCESS);
        createTx("PAY_B", now.plusSeconds(20), PaymentStatus.SUCCESS);

        // Fetch limit 1 -> hasMore = true, nextCursor present
        MvcResult page1 = mockMvc.perform(get(BASE_URL + "?limit=1")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.hasMore").value(true))
                .andExpect(jsonPath("$.pagination.nextCursor").isString())
                .andReturn();

        String cursor = objectMapper.readTree(page1.getResponse().getContentAsString())
                .get("pagination").get("nextCursor").asText();

        // Fetch limit 1 with cursor -> hasMore = false, nextCursor is null
        mockMvc.perform(get(BASE_URL + "?limit=1&cursor=" + cursor)
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("Scenario 6: Empty database returns empty page with hasMore=false and nextCursor=null")
    void testEmptyDatabaseReturnsEmptyPage() throws Exception {
        mockMvc.perform(get(BASE_URL)
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(0))
                .andExpect(jsonPath("$.pagination.limit").value(20))
                .andExpect(jsonPath("$.pagination.hasMore").value(false))
                .andExpect(jsonPath("$.pagination.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("Scenario 7: Default limit is 20 when omitted")
    void testDefaultLimitIs20() throws Exception {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        for (int i = 1; i <= 25; i++) {
            createTx("PAY_" + i, now.plusSeconds(i), PaymentStatus.SUCCESS);
        }

        mockMvc.perform(get(BASE_URL)
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.limit").value(20))
                .andExpect(jsonPath("$.data.length()").value(20))
                .andExpect(jsonPath("$.pagination.hasMore").value(true));
    }

    @Test
    @DisplayName("Scenario 8: Maximum limit of 100 works successfully")
    void testMaxLimit100Works() throws Exception {
        mockMvc.perform(get(BASE_URL + "?limit=100")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagination.limit").value(100));
    }

    @Test
    @DisplayName("Scenario 9, 10, 11, 12: Invalid limits (0, 101, negative, non-numeric) are rejected with 400")
    void testInvalidLimitsRejected() throws Exception {
        mockMvc.perform(get(BASE_URL + "?limit=0").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));

        mockMvc.perform(get(BASE_URL + "?limit=101").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));

        mockMvc.perform(get(BASE_URL + "?limit=-5").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));

        mockMvc.perform(get(BASE_URL + "?limit=abc").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PARAMETER"));
    }

    @Test
    @DisplayName("Scenario 13, 14, 15, 16: Malformed, unsupported, tampered, or filter-mismatched cursors rejected with 400")
    void testInvalidCursorsRejected() throws Exception {
        // 13. Malformed cursor
        mockMvc.perform(get(BASE_URL + "?cursor=bad_base64_!@#$").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));

        // 14. Unsupported cursor version
        String unsupported = cursorCodecService.encodeCursor(Instant.now(), 10L, null, null, null, null);
        byte[] decoded = java.util.Base64.getUrlDecoder().decode(unsupported);
        String v2Json = new String(decoded).replace("\"v\":\"v1\"", "\"v\":\"v2\"");
        String v2Cursor = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(v2Json.getBytes());

        mockMvc.perform(get(BASE_URL + "?cursor=" + v2Cursor).header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));

        // 15. Tampered cursor
        String validCursor = cursorCodecService.encodeCursor(Instant.now(), 10L, null, null, null, null);
        String tampered = validCursor.substring(0, validCursor.length() - 4) + "AAAA";
        mockMvc.perform(get(BASE_URL + "?cursor=" + tampered).header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));

        // 16. Cursor with changed filters
        String cursorWithStatus = cursorCodecService.encodeCursor(Instant.now(), 10L, PaymentStatus.SUCCESS, null, null, null);
        mockMvc.perform(get(BASE_URL + "?cursor=" + cursorWithStatus + "&status=FAILED").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_CURSOR"));
    }

    @Test
    @DisplayName("Scenario 17 & 18: Equal timestamps are ordered deterministically by ID with zero duplicates")
    void testEqualTimestampsTieBreakingAndNoDuplicates() throws Exception {
        Instant sameTimestamp = Instant.parse("2026-10-19T12:00:00Z");
        List<PaymentTransaction> records = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            records.add(createTx("PAY_EQ_" + i, sameTimestamp, PaymentStatus.SUCCESS));
        }

        Set<String> retrievedPaymentIds = new HashSet<>();
        String cursor = null;
        boolean hasMore = true;
        int pageCount = 0;

        while (hasMore) {
            String url = BASE_URL + "?limit=2" + (cursor != null ? "&cursor=" + cursor : "");
            MvcResult res = mockMvc.perform(get(url).header("X-Api-Key", API_KEY))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode page = objectMapper.readTree(res.getResponse().getContentAsString());
            JsonNode data = page.get("data");
            for (int i = 0; i < data.size(); i++) {
                String pid = data.get(i).get("paymentId").asText();
                assertFalse(retrievedPaymentIds.contains(pid), "Duplicate record detected across pages: " + pid);
                retrievedPaymentIds.add(pid);
            }

            hasMore = page.get("pagination").get("hasMore").asBoolean();
            cursor = hasMore ? page.get("pagination").get("nextCursor").asText() : null;
            pageCount++;
        }

        assertEquals(3, pageCount, "6 items with limit 2 must yield exactly 3 pages");
        assertEquals(6, retrievedPaymentIds.size(), "All 6 equal-timestamp items must be retrieved exactly once");
    }

    @Test
    @DisplayName("Scenario 19 & 21: Status filtering works and rejects invalid status")
    void testStatusFiltering() throws Exception {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        createTx("PAY_S1", now.plusSeconds(1), PaymentStatus.SUCCESS);
        createTx("PAY_F1", now.plusSeconds(2), PaymentStatus.FAILED);
        createTx("PAY_S2", now.plusSeconds(3), PaymentStatus.SUCCESS);

        // Filter status=SUCCESS
        mockMvc.perform(get(BASE_URL + "?status=SUCCESS").header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("PAY_S2"))
                .andExpect(jsonPath("$.data[1].id").value("PAY_S1"));

        // Reject invalid status with 400
        mockMvc.perform(get(BASE_URL + "?status=INVALID_STATUS").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));
    }

    @Test
    @DisplayName("Scenario 20 & 22: Creation-time date range filtering works and rejects invalid date range")
    void testDateRangeFiltering() throws Exception {
        Instant t1 = Instant.parse("2026-10-19T08:00:00Z");
        Instant t2 = Instant.parse("2026-10-19T10:00:00Z");
        Instant t3 = Instant.parse("2026-10-19T12:00:00Z");

        createTx("PAY_EARLY", t1, PaymentStatus.SUCCESS);
        createTx("PAY_MID", t2, PaymentStatus.SUCCESS);
        createTx("PAY_LATE", t3, PaymentStatus.SUCCESS);

        // Range 09:00 to 11:00 -> only PAY_MID
        mockMvc.perform(get(BASE_URL + "?from=2026-10-19T09:00:00Z&to=2026-10-19T11:00:00Z")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value("PAY_MID"));

        // Invalid date format
        mockMvc.perform(get(BASE_URL + "?from=not-a-date").header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));

        // from is after to
        mockMvc.perform(get(BASE_URL + "?from=2026-10-19T15:00:00Z&to=2026-10-19T10:00:00Z")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_PAGINATION_FILTER"));
    }

    @Test
    @DisplayName("Scenario 23: Sensitive internal fields are excluded from pagination response")
    void testSensitiveFieldsExcluded() throws Exception {
        createTx("PAY_PUBLIC", Instant.now(), PaymentStatus.SUCCESS);

        mockMvc.perform(get(BASE_URL).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].lockVersion").doesNotExist())
                .andExpect(jsonPath("$.data[0].rawPayload").doesNotExist())
                .andExpect(jsonPath("$.data[0].webhookSecret").doesNotExist())
                .andExpect(jsonPath("$.data[0].signingKey").doesNotExist());
    }

    @Test
    @DisplayName("Scenario 24 & 25: Existing payment creation and idempotency replay behavior remain intact")
    void testExistingPaymentCreationAndReplayIntact() throws Exception {
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                "IDEM_INTEG_TEST_5", "USR_ALICE", new BigDecimal("150.00"), "USD", "CREDIT_CARD", "Step 5 regression check"
        );

        // 1. First execution -> 201 Created without Idempotent-Replayed header
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", "IDEM_INTEG_TEST_5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // 2. Replay execution -> 201 Created with Idempotent-Replayed: true header
        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Idempotency-Key", "IDEM_INTEG_TEST_5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.isCachedIdempotentResponse").value(true));
    }

    @Test
    @DisplayName("Scenario 27: Authorization rules strictly enforced (401 on missing, 403 on invalid, 200 on valid)")
    void testAuthorizationEnforcement() throws Exception {
        // Missing auth -> 401 Unauthorized
        mockMvc.perform(get(BASE_URL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Invalid key -> 403 Forbidden
        mockMvc.perform(get(BASE_URL).header("X-Api-Key", "invalid_key"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // Valid key via X-Api-Key -> 200 OK
        mockMvc.perform(get(BASE_URL).header("X-Api-Key", API_KEY))
                .andExpect(status().isOk());

        // Valid key via Authorization: Bearer -> 200 OK
        mockMvc.perform(get(BASE_URL).header("Authorization", "Bearer " + API_KEY))
                .andExpect(status().isOk());
    }
}
