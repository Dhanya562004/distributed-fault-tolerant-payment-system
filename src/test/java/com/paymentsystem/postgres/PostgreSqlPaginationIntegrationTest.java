package com.paymentsystem.postgres;

import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PagedPaymentResponse;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.entity.*;
import com.paymentsystem.exception.InvalidCursorException;
import com.paymentsystem.repository.IdempotencyKeyRepository;
import com.paymentsystem.repository.OutboxEventRepository;
import com.paymentsystem.repository.PaymentTransactionRepository;
import com.paymentsystem.repository.UserRepository;
import com.paymentsystem.service.CursorCodecService;
import com.paymentsystem.service.PaymentPaginationService;
import com.paymentsystem.service.PaymentProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Genuine PostgreSQL 16 Testcontainers Integration Test Suite for Step 5:
 * Production-Style Cursor-Based Payment Transaction Pagination.
 *
 * Verifies keyset query correctness, index verification, deterministic composite ordering,
 * equal-timestamp tie-breaking, filter bindings, and regression invariants on PostgreSQL 16.
 */
public class PostgreSqlPaginationIntegrationTest extends AbstractPostgreSqlIntegrationTest {

    @Autowired
    private PaymentTransactionRepository paymentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PaymentPaginationService paginationService;

    @Autowired
    private PaymentProcessingService paymentProcessingService;

    @Autowired
    private CursorCodecService cursorCodecService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String API_KEY = "apexpay_admin_secret_key_2026";

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        idempotencyRepository.deleteAll();
        paymentRepository.deleteAll();
        userRepository.deleteAll();

        // Seed test user
        User user = new User("USR_PG_TEST", "Postgres User", "pg@example.com", new BigDecimal("50000.0000"));
        userRepository.saveAndFlush(user);
    }

    private PaymentTransaction seedPayment(String paymentId, Instant createdAt, PaymentStatus status) {
        PaymentTransaction tx = new PaymentTransaction(
                paymentId,
                "IDEM_" + paymentId,
                "USR_PG_TEST",
                new BigDecimal("100.0000"),
                "USD",
                "CREDIT_CARD",
                "Pg Payment " + paymentId
        );
        tx.setStatus(status);
        tx.setCreatedAt(createdAt);
        tx.setUpdatedAt(createdAt);
        return paymentRepository.saveAndFlush(tx);
    }

    @Test
    @DisplayName("PG Scenario 1: First-page ordering returns newest transactions first (createdAt DESC, id DESC)")
    void testFirstPageOrdering() {
        Instant base = Instant.parse("2026-10-19T10:00:00Z");
        for (int i = 1; i <= 5; i++) {
            seedPayment("PAY_PG_" + i, base.plus(i, ChronoUnit.MINUTES), PaymentStatus.SUCCESS);
        }

        PagedPaymentResponse page = paginationService.getTransactions(
                3, null, null, null, null, API_KEY, null, null
        );

        assertEquals(3, page.getData().size());
        assertTrue(page.getPagination().isHasMore());
        assertNotNull(page.getPagination().getNextCursor());

        // Verify newest first
        assertEquals("PAY_PG_5", page.getData().get(0).getId());
        assertEquals("PAY_PG_4", page.getData().get(1).getId());
        assertEquals("PAY_PG_3", page.getData().get(2).getId());
    }

    @Test
    @DisplayName("PG Scenario 2: Multi-page navigation seamlessly traverses entire dataset without duplicates or skips")
    void testMultiPageNavigation() {
        Instant base = Instant.parse("2026-10-19T10:00:00Z");
        for (int i = 1; i <= 7; i++) {
            seedPayment("PAY_NAV_" + i, base.plus(i, ChronoUnit.MINUTES), PaymentStatus.SUCCESS);
        }

        Set<String> collectedIds = new LinkedHashSet<>();
        String cursor = null;
        boolean hasMore = true;
        int pageCount = 0;

        while (hasMore) {
            PagedPaymentResponse page = paginationService.getTransactions(
                    3, cursor, null, null, null, API_KEY, null, null
            );
            page.getData().forEach(item -> {
                assertFalse(collectedIds.contains(item.getId()), "Duplicate item across pages: " + item.getId());
                collectedIds.add(item.getId());
            });

            hasMore = page.getPagination().isHasMore();
            cursor = page.getPagination().getNextCursor();
            pageCount++;
        }

        assertEquals(3, pageCount, "7 items with limit 3 must require 3 pages (3 + 3 + 1)");
        assertEquals(7, collectedIds.size());
        assertEquals(List.of("PAY_NAV_7", "PAY_NAV_6", "PAY_NAV_5", "PAY_NAV_4", "PAY_NAV_3", "PAY_NAV_2", "PAY_NAV_1"),
                new ArrayList<>(collectedIds));
    }

    @Test
    @DisplayName("PG Scenario 3: Equal-timestamp tie-breaking deterministically orders by primary key ID DESC")
    void testEqualTimestampTieBreaking() {
        Instant identicalTime = Instant.parse("2026-10-19T12:00:00Z");
        List<PaymentTransaction> records = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            records.add(seedPayment("PAY_TIE_" + i, identicalTime, PaymentStatus.SUCCESS));
        }

        // Fetch with limit 2 across pages
        PagedPaymentResponse page1 = paginationService.getTransactions(
                2, null, null, null, null, API_KEY, null, null
        );
        assertEquals(2, page1.getData().size());
        assertTrue(page1.getPagination().isHasMore());
        String cursor1 = page1.getPagination().getNextCursor();

        PagedPaymentResponse page2 = paginationService.getTransactions(
                2, cursor1, null, null, null, API_KEY, null, null
        );
        assertEquals(2, page2.getData().size());
        assertTrue(page2.getPagination().isHasMore());
        String cursor2 = page2.getPagination().getNextCursor();

        PagedPaymentResponse page3 = paginationService.getTransactions(
                2, cursor2, null, null, null, API_KEY, null, null
        );
        assertEquals(1, page3.getData().size());
        assertFalse(page3.getPagination().isHasMore());
        assertNull(page3.getPagination().getNextCursor());

        // Verify ID DESC order: highest ID first down to lowest ID
        Long id1 = records.get(4).getId(); // last inserted -> highest ID
        Long id2 = records.get(3).getId();
        Long id3 = records.get(2).getId();
        Long id4 = records.get(1).getId();
        Long id5 = records.get(0).getId();

        assertEquals("PAY_TIE_5", page1.getData().get(0).getId());
        assertEquals("PAY_TIE_4", page1.getData().get(1).getId());
        assertEquals("PAY_TIE_3", page2.getData().get(0).getId());
        assertEquals("PAY_TIE_2", page2.getData().get(1).getId());
        assertEquals("PAY_TIE_1", page3.getData().get(0).getId());
    }

    @Test
    @DisplayName("PG Scenario 4: Keyset query correctness fetches strictly limit + 1 without OFFSET")
    void testKeysetQueryCorrectness() {
        Instant base = Instant.parse("2026-10-19T10:00:00Z");
        for (int i = 1; i <= 10; i++) {
            seedPayment("PAY_KEYSET_" + i, base.plus(i, ChronoUnit.MINUTES), PaymentStatus.SUCCESS);
        }

        // Direct repository call with limit = 4 (simulating limit + 1 for page size 3)
        List<PaymentTransaction> raw = paymentRepository.findTransactionsKeyset(
                null, null, null, null, null, 4
        );
        assertEquals(4, raw.size());
        assertEquals("PAY_KEYSET_10", raw.get(0).getPaymentId());
        assertEquals("PAY_KEYSET_9", raw.get(1).getPaymentId());
        assertEquals("PAY_KEYSET_8", raw.get(2).getPaymentId());
        assertEquals("PAY_KEYSET_7", raw.get(3).getPaymentId());
    }

    @Test
    @DisplayName("PG Scenario 5: Filtered pagination correctly combines status and date range with keyset cursor")
    void testFilteredPagination() {
        Instant t1 = Instant.parse("2026-10-19T08:00:00Z");
        Instant t2 = Instant.parse("2026-10-19T09:00:00Z");
        Instant t3 = Instant.parse("2026-10-19T10:00:00Z");
        Instant t4 = Instant.parse("2026-10-19T11:00:00Z");
        Instant t5 = Instant.parse("2026-10-19T12:00:00Z");

        seedPayment("PAY_F_S1", t1, PaymentStatus.SUCCESS);
        seedPayment("PAY_F_F1", t2, PaymentStatus.FAILED);
        seedPayment("PAY_F_S2", t3, PaymentStatus.SUCCESS);
        seedPayment("PAY_F_S3", t4, PaymentStatus.SUCCESS);
        seedPayment("PAY_F_F2", t5, PaymentStatus.FAILED);

        // Filter: status=SUCCESS, from=08:30, to=11:30 -> expected: PAY_F_S3, PAY_F_S2
        PagedPaymentResponse page = paginationService.getTransactions(
                10, null, "SUCCESS", "2026-10-19T08:30:00Z", "2026-10-19T11:30:00Z",
                API_KEY, null, null
        );

        assertEquals(2, page.getData().size());
        assertEquals("PAY_F_S3", page.getData().get(0).getId());
        assertEquals("PAY_F_S2", page.getData().get(1).getId());
        assertFalse(page.getPagination().isHasMore());
    }

    @Test
    @DisplayName("PG Scenario 6: Empty results return empty array with hasMore=false and nextCursor=null")
    void testEmptyResults() {
        PagedPaymentResponse page = paginationService.getTransactions(
                20, null, "FAILED", null, null, API_KEY, null, null
        );

        assertNotNull(page.getData());
        assertTrue(page.getData().isEmpty());
        assertFalse(page.getPagination().isHasMore());
        assertNull(page.getPagination().getNextCursor());
    }

    @Test
    @DisplayName("PG Scenario 7: Stable pagination guarantees no duplicate or skipped items when new records inserted ahead of cursor")
    void testStablePaginationWithInterleavedInserts() {
        Instant t1 = Instant.parse("2026-10-19T10:00:00Z");
        Instant t2 = Instant.parse("2026-10-19T10:05:00Z");
        Instant t3 = Instant.parse("2026-10-19T10:10:00Z");

        seedPayment("PAY_INIT_1", t1, PaymentStatus.SUCCESS);
        seedPayment("PAY_INIT_2", t2, PaymentStatus.SUCCESS);
        seedPayment("PAY_INIT_3", t3, PaymentStatus.SUCCESS);

        // Fetch page 1 (limit 2) -> returns INIT_3, INIT_2
        PagedPaymentResponse page1 = paginationService.getTransactions(
                2, null, null, null, null, API_KEY, null, null
        );
        assertEquals(2, page1.getData().size());
        assertEquals("PAY_INIT_3", page1.getData().get(0).getId());
        assertEquals("PAY_INIT_2", page1.getData().get(1).getId());
        String cursor1 = page1.getPagination().getNextCursor();

        // CONCURRENT MUTATION: A newer transaction is inserted with createdAt = 10:15
        Instant tNew = Instant.parse("2026-10-19T10:15:00Z");
        seedPayment("PAY_NEW_CONCURRENT", tNew, PaymentStatus.SUCCESS);

        // Client requests page 2 with cursor1
        // Keyset condition createdAt < 10:05 guarantees PAY_NEW_CONCURRENT does NOT shift page 2
        // PAY_INIT_2 is not duplicated, and PAY_INIT_1 is not skipped!
        PagedPaymentResponse page2 = paginationService.getTransactions(
                2, cursor1, null, null, null, API_KEY, null, null
        );
        assertEquals(1, page2.getData().size());
        assertEquals("PAY_INIT_1", page2.getData().get(0).getId());
        assertFalse(page2.getPagination().isHasMore());
    }

    @Test
    @DisplayName("PG Scenario 8: Cursor integrity validation rejects tampered cursor and changed filter hashes")
    void testCursorIntegrityValidation() {
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        seedPayment("PAY_INT_1", now, PaymentStatus.SUCCESS);

        // Valid cursor for status=SUCCESS
        String validCursor = cursorCodecService.encodeCursor(now, 1L, PaymentStatus.SUCCESS, null, null, null);

        // Tampered signature should be rejected
        String tampered = validCursor.substring(0, validCursor.length() - 5) + "XYZ12";
        assertThrows(InvalidCursorException.class, () ->
                paginationService.getTransactions(10, tampered, "SUCCESS", null, null, API_KEY, null, null));

        // Changed filter (client changes status to FAILED while passing cursor for SUCCESS)
        assertThrows(InvalidCursorException.class, () ->
                paginationService.getTransactions(10, validCursor, "FAILED", null, null, API_KEY, null, null));
    }

    @Test
    @DisplayName("PG Scenario 9: Correct database index idx_payments_created_at_id exists in PostgreSQL pg_indexes")
    void testDatabaseIndexCreation() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'payments' AND indexname = 'idx_payments_created_at_id'",
                String.class
        );

        assertFalse(indexes.isEmpty(), "PostgreSQL index idx_payments_created_at_id must exist in pg_indexes");
        assertEquals("idx_payments_created_at_id", indexes.get(0));
    }

    @Test
    @DisplayName("PG Scenario 10: Existing payment transaction creation behaves correctly on PostgreSQL")
    void testExistingPaymentCreationIntact() {
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                "IDEM_PG_REG_01", "USR_PG_TEST", new BigDecimal("250.00"), "USD", "CREDIT_CARD", "PG Regression Check"
        );

        PaymentResponse resp = paymentProcessingService.initiatePayment(req);
        assertNotNull(resp);
        assertEquals(PaymentStatus.SUCCESS, resp.getStatus());
        assertEquals(new BigDecimal("250.00"), resp.getAmount());

        PaymentTransaction inDb = paymentRepository.findByPaymentId(resp.getPaymentId()).orElseThrow();
        assertEquals(PaymentStatus.SUCCESS, inDb.getStatus());
    }

    @Test
    @DisplayName("PG Scenario 11: Existing idempotency cached replay behaves correctly on PostgreSQL")
    void testExistingIdempotencyBehaviorIntact() {
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                "IDEM_PG_IDEM_REPLAY", "USR_PG_TEST", new BigDecimal("75.00"), "USD", "CREDIT_CARD", "PG Idempotency"
        );

        PaymentResponse first = paymentProcessingService.initiatePayment(req);
        assertFalse(first.getIsCachedIdempotentResponse());

        PaymentResponse second = paymentProcessingService.initiatePayment(req);
        assertTrue(second.getIsCachedIdempotentResponse());
        assertEquals(first.getPaymentId(), second.getPaymentId());
    }

    @Test
    @DisplayName("PG Scenario 12: Existing transactional outbox behavior atomically records events on PostgreSQL")
    void testExistingOutboxBehaviorIntact() {
        InitiatePaymentRequest req = new InitiatePaymentRequest(
                "IDEM_PG_OUTBOX_CHK", "USR_PG_TEST", new BigDecimal("80.00"), "USD", "CREDIT_CARD", "PG Outbox Check"
        );

        PaymentResponse resp = paymentProcessingService.initiatePayment(req);
        assertNotNull(resp);

        List<OutboxEvent> outboxList = outboxEventRepository.findByAggregateId(resp.getPaymentId());
        assertFalse(outboxList.isEmpty(), "Outbox event must be atomically persisted with payment");
        assertEquals("payment.succeeded", outboxList.get(0).getEventType());
    }
}
