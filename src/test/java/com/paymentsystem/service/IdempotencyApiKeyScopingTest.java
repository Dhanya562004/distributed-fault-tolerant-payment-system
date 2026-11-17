package com.paymentsystem.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsystem.dto.request.InitiatePaymentRequest;
import com.paymentsystem.dto.response.PaymentResponse;
import com.paymentsystem.idempotency.IdempotencyKeyRecord;
import com.paymentsystem.payment.PaymentStatus;
import com.paymentsystem.idempotency.IdempotencyKeyRepository;
import com.paymentsystem.security.RequestContext;
import com.paymentsystem.idempotency.IdempotencyServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IdempotencyApiKeyScopingTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private MetricsService metricsService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC);

    private IdempotencyServiceImpl idempotencyService;

    private static final String API_KEY_ALICE = "key_customer_alice_001";
    private static final String API_KEY_BOB = "key_customer_bob_002";
    private static final String SHARED_IDEM_KEY = "ORDER_ORD_99999";

    @BeforeEach
    void setUp() {
        idempotencyService = new IdempotencyServiceImpl(
                idempotencyKeyRepository, objectMapper, metricsService, clock
        );
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    @DisplayName("Two different API keys can independently acquire the same idempotency key without collision")
    void testIndependentScopingAcrossApiKeys() {
        InitiatePaymentRequest reqAlice = new InitiatePaymentRequest(
                SHARED_IDEM_KEY, "USR_ALICE", 5000L, "USD", "CREDIT_CARD", "Alice Subscription"
        );
        InitiatePaymentRequest reqBob = new InitiatePaymentRequest(
                SHARED_IDEM_KEY, "USR_BOB", 8500L, "EUR", "BANK_TRANSFER", "Bob Purchase"
        );

        // Neither key exists initially in repository
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue(API_KEY_ALICE, SHARED_IDEM_KEY))
                .thenReturn(Optional.empty());
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue(API_KEY_BOB, SHARED_IDEM_KEY))
                .thenReturn(Optional.empty());

        // Alice acquires key
        RequestContext.setApiKey(API_KEY_ALICE);
        Optional<IdempotencyKeyRecord> aliceAcquire = idempotencyService.tryAcquireOrGet(SHARED_IDEM_KEY, reqAlice);
        assertTrue(aliceAcquire.isEmpty(), "Alice should acquire new lock (first time seen for Alice)");

        // Verify save was called with Alice's API key
        verify(idempotencyKeyRepository).saveAndFlush(argThat(record ->
                API_KEY_ALICE.equals(record.getApiKey()) && SHARED_IDEM_KEY.equals(record.getKeyValue())
        ));

        // Bob acquires same idempotency key string under Bob's API key
        RequestContext.setApiKey(API_KEY_BOB);
        Optional<IdempotencyKeyRecord> bobAcquire = idempotencyService.tryAcquireOrGet(SHARED_IDEM_KEY, reqBob);
        assertTrue(bobAcquire.isEmpty(), "Bob should also acquire new lock (first time seen for Bob)");

        // Verify save was called with Bob's API key
        verify(idempotencyKeyRepository).saveAndFlush(argThat(record ->
                API_KEY_BOB.equals(record.getApiKey()) && SHARED_IDEM_KEY.equals(record.getKeyValue())
        ));
    }

    @Test
    @DisplayName("Replaying idempotency key returns cached response specific to that customer's API key")
    void testReplayReturnsCustomerSpecificCachedResponse() throws Exception {
        PaymentResponse aliceResponse = new PaymentResponse();
        aliceResponse.setPaymentId("PAY_ALICE_111");
        aliceResponse.setStatus(PaymentStatus.SUCCESS);
        aliceResponse.setAmount(5000L);
        aliceResponse.setCurrency("USD");

        PaymentResponse bobResponse = new PaymentResponse();
        bobResponse.setPaymentId("PAY_BOB_222");
        bobResponse.setStatus(PaymentStatus.SUCCESS);
        bobResponse.setAmount(8500L);
        bobResponse.setCurrency("EUR");

        InitiatePaymentRequest reqAlice = new InitiatePaymentRequest(
                SHARED_IDEM_KEY, "USR_ALICE", 5000L, "USD", "CREDIT_CARD", "Alice Subscription"
        );
        InitiatePaymentRequest reqBob = new InitiatePaymentRequest(
                SHARED_IDEM_KEY, "USR_BOB", 8500L, "EUR", "BANK_TRANSFER", "Bob Purchase"
        );

        String aliceHash = idempotencyService.computePayloadHash(reqAlice);
        String bobHash = idempotencyService.computePayloadHash(reqBob);

        IdempotencyKeyRecord aliceRecord = new IdempotencyKeyRecord(
                API_KEY_ALICE, SHARED_IDEM_KEY, aliceHash, Instant.now(clock), Instant.now(clock).plusSeconds(3600)
        );
        aliceRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        aliceRecord.setResponseBody(objectMapper.writeValueAsString(aliceResponse));
        aliceRecord.setHttpStatusCode(200);

        IdempotencyKeyRecord bobRecord = new IdempotencyKeyRecord(
                API_KEY_BOB, SHARED_IDEM_KEY, bobHash, Instant.now(clock), Instant.now(clock).plusSeconds(3600)
        );
        bobRecord.setStatus(IdempotencyKeyRecord.Status.COMPLETED);
        bobRecord.setResponseBody(objectMapper.writeValueAsString(bobResponse));
        bobRecord.setHttpStatusCode(200);

        when(idempotencyKeyRepository.findByApiKeyAndKeyValue(API_KEY_ALICE, SHARED_IDEM_KEY))
                .thenReturn(Optional.of(aliceRecord));
        when(idempotencyKeyRepository.findByApiKeyAndKeyValue(API_KEY_BOB, SHARED_IDEM_KEY))
                .thenReturn(Optional.of(bobRecord));

        // Replay Alice
        RequestContext.setApiKey(API_KEY_ALICE);
        Optional<IdempotencyKeyRecord> cachedAlice = idempotencyService.tryAcquireOrGet(SHARED_IDEM_KEY, reqAlice);
        assertTrue(cachedAlice.isPresent());
        PaymentResponse parsedAlice = objectMapper.readValue(cachedAlice.get().getResponseBody(), PaymentResponse.class);
        assertEquals("PAY_ALICE_111", parsedAlice.getPaymentId());
        assertEquals(5000L, parsedAlice.getAmount());

        // Replay Bob
        RequestContext.setApiKey(API_KEY_BOB);
        Optional<IdempotencyKeyRecord> cachedBob = idempotencyService.tryAcquireOrGet(SHARED_IDEM_KEY, reqBob);
        assertTrue(cachedBob.isPresent());
        PaymentResponse parsedBob = objectMapper.readValue(cachedBob.get().getResponseBody(), PaymentResponse.class);
        assertEquals("PAY_BOB_222", parsedBob.getPaymentId());
        assertEquals(8500L, parsedBob.getAmount());
    }
}
