package com.paymentsystem.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Metadata block accompanying paginated responses.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaginationMetadata {

    private int limit;
    private boolean hasMore;
    private String nextCursor;
    private Integer page;
    private Long total;

    public PaginationMetadata() {
    }

    public PaginationMetadata(int limit, boolean hasMore, String nextCursor) {
        this.limit = limit;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
    }

    public PaginationMetadata(int limit, boolean hasMore, String nextCursor, Integer page, Long total) {
        this.limit = limit;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
        this.page = page;
        this.total = total;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }

    public String getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(String nextCursor) {
        this.nextCursor = nextCursor;
    }

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }
}
