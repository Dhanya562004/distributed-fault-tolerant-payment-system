package com.paymentsystem.dto.response;

import java.util.ArrayList;
import java.util.List;

/**
 * Standardized generic envelope for all paginated list responses across the API.
 * Structure: { "data": [...], "pagination": { "limit": ..., "hasMore": ..., "nextCursor": ... } }
 */
public class PagedResponse<T> {

    private List<T> data = new ArrayList<>();
    private PaginationMetadata pagination;

    public PagedResponse() {
    }

    public PagedResponse(List<T> data, PaginationMetadata pagination) {
        this.data = data != null ? data : new ArrayList<>();
        this.pagination = pagination;
    }

    public static <T> PagedResponse<T> of(List<T> data, PaginationMetadata pagination) {
        return new PagedResponse<>(data, pagination);
    }

    public static <T> PagedResponse<T> paginate(List<T> allItems, int page, int limit) {
        int safePage = Math.max(0, page);
        int safeLimit = (limit <= 0 || limit > 100) ? 20 : limit;
        int total = allItems.size();
        int fromIndex = Math.min(safePage * safeLimit, total);
        int toIndex = Math.min(fromIndex + safeLimit, total);
        List<T> sublist = allItems.subList(fromIndex, toIndex);
        boolean hasMore = toIndex < total;
        String nextCursor = hasMore ? String.valueOf(safePage + 1) : null;

        PaginationMetadata meta = new PaginationMetadata(safeLimit, hasMore, nextCursor, safePage, (long) total);
        return new PagedResponse<>(new ArrayList<>(sublist), meta);
    }

    public List<T> getData() {
        return data;
    }

    public void setData(List<T> data) {
        this.data = data != null ? data : new ArrayList<>();
    }

    public PaginationMetadata getPagination() {
        return pagination;
    }

    public void setPagination(PaginationMetadata pagination) {
        this.pagination = pagination;
    }
}
