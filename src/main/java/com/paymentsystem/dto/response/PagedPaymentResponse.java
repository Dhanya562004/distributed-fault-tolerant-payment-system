package com.paymentsystem.dto.response;

import java.util.List;

/**
 * Top-level response container for cursor-based payment transaction pagination.
 */
public class PagedPaymentResponse extends PagedResponse<PaymentTransactionResponse> {

    public PagedPaymentResponse() {
        super();
    }

    public PagedPaymentResponse(List<PaymentTransactionResponse> data, PaginationMetadata pagination) {
        super(data, pagination);
    }
}
