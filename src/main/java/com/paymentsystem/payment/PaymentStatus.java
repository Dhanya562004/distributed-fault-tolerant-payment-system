package com.paymentsystem.payment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PROCESSING, FAILED),
            PROCESSING, EnumSet.of(SUCCESS, FAILED),
            SUCCESS, EnumSet.of(REFUNDED),
            FAILED, Collections.emptySet(),
            REFUNDED, Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) {
            return false;
        }
        return ALLOWED_TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }
}
