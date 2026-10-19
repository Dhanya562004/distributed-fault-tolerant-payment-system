package com.paymentsystem.dto.response;

/**
 * Metadata block accompanying keyset paginated responses.
 */
public class PaginationMetadata {

    private int limit;
    private boolean hasMore;
    private String nextCursor;

    public PaginationMetadata() {
    }

    public PaginationMetadata(int limit, boolean hasMore, String nextCursor) {
        this.limit = limit;
        this.hasMore = hasMore;
        this.nextCursor = nextCursor;
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
}
