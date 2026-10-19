package com.paymentsystem.dto.response;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level response container for cursor-based payment transaction pagination.
 */
public class PagedPaymentResponse {

    private List<PaymentTransactionResponse> data = new ArrayList<>();
    private PaginationMetadata pagination;

    public PagedPaymentResponse() {
    }

    public PagedPaymentResponse(List<PaymentTransactionResponse> data, PaginationMetadata pagination) {
        this.data = data != null ? data : new ArrayList<>();
        this.pagination = pagination;
    }

    public List<PaymentTransactionResponse> getData() {
        return data;
    }

    public void setData(List<PaymentTransactionResponse> data) {
        this.data = data != null ? data : new ArrayList<>();
    }

    public PaginationMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PaginationMetadata pagination) {
        this.pagination = pagination;
    }
}
