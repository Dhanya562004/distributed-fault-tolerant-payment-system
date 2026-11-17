package com.paymentsystem.entity;
import com.paymentsystem.payment.PaymentTransaction;
import com.paymentsystem.payment.PaymentStatus;

import com.paymentsystem.exception.InvalidTransactionStateException;
import com.paymentsystem.test.PaymentTestHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PaymentStatusTransitionTest {

    /**
     * Hardcoded truth table of all 25 state pairs (5x5).
     * Written explicitly in the test to ensure tests do not circular-read
     * the enum's internal transition table.
     */
    static Stream<Arguments> provideAll25StatePairs() {
        return Stream.of(
                // From PENDING (2 allowed, 3 forbidden)
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.PROCESSING, true),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.SUCCESS, false),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.FAILED, true),
                Arguments.of(PaymentStatus.PENDING, PaymentStatus.REFUNDED, false),

                // From PROCESSING (2 allowed, 3 forbidden)
                Arguments.of(PaymentStatus.PROCESSING, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.PROCESSING, PaymentStatus.PROCESSING, false),
                Arguments.of(PaymentStatus.PROCESSING, PaymentStatus.SUCCESS, true),
                Arguments.of(PaymentStatus.PROCESSING, PaymentStatus.FAILED, true),
                Arguments.of(PaymentStatus.PROCESSING, PaymentStatus.REFUNDED, false),

                // From SUCCESS (1 allowed, 4 forbidden)
                Arguments.of(PaymentStatus.SUCCESS, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.SUCCESS, PaymentStatus.PROCESSING, false),
                Arguments.of(PaymentStatus.SUCCESS, PaymentStatus.SUCCESS, false),
                Arguments.of(PaymentStatus.SUCCESS, PaymentStatus.FAILED, false),
                Arguments.of(PaymentStatus.SUCCESS, PaymentStatus.REFUNDED, true),

                // From FAILED (terminal: 0 allowed, 5 forbidden)
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.PROCESSING, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.SUCCESS, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.FAILED, false),
                Arguments.of(PaymentStatus.FAILED, PaymentStatus.REFUNDED, false),

                // From REFUNDED (terminal: 0 allowed, 5 forbidden)
                Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.PENDING, false),
                Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.PROCESSING, false),
                Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.SUCCESS, false),
                Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.FAILED, false),
                Arguments.of(PaymentStatus.REFUNDED, PaymentStatus.REFUNDED, false)
        );
    }

    @ParameterizedTest(name = "{index} => From {0} to {1} expected allowed={2}")
    @MethodSource("provideAll25StatePairs")
    @DisplayName("Verify all 25 state transitions against hardcoded truth table")
    void testAll25Transitions(PaymentStatus from, PaymentStatus to, boolean expectedAllowed) {
        // 1. Verify enum query method
        assertEquals(expectedAllowed, from.canTransitionTo(to),
                () -> String.format("Transition from %s to %s must be %s", from, to, expectedAllowed));

        // 2. Verify entity transitionTo enforcement
        PaymentTransaction tx = PaymentTestHelper.createInState(from);
        assertEquals(from, tx.getStatus(), "Test entity should be initialized in state " + from);

        if (expectedAllowed) {
            assertDoesNotThrow(() -> tx.transitionTo(to),
                    () -> String.format("Allowed transition %s -> %s threw an unexpected exception", from, to));
            assertEquals(to, tx.getStatus(), "Entity status should be updated to " + to);
        } else {
            InvalidTransactionStateException ex = assertThrows(InvalidTransactionStateException.class,
                    () -> tx.transitionTo(to),
                    () -> String.format("Forbidden transition %s -> %s must throw InvalidTransactionStateException", from, to));
            assertTrue(ex.getMessage().contains("Illegal state transition from " + from + " to " + to));
            assertEquals(from, tx.getStatus(), "Entity status must remain unchanged at " + from + " after rejected transition");
        }
    }

    @Test
    @DisplayName("transitionTo with null target status throws InvalidTransactionStateException")
    void testTransitionTo_NullTarget_Throws() {
        PaymentTransaction tx = PaymentTestHelper.createInState(PaymentStatus.PENDING);
        assertThrows(InvalidTransactionStateException.class, () -> tx.transitionTo(null));
    }
}
