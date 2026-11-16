package com.paymentsystem.security;

public final class RequestContext {

    private static final ThreadLocal<String> CURRENT_REQUEST_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_API_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_CUSTOMER_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static String getRequestId() {
        return CURRENT_REQUEST_ID.get();
    }

    public static void setRequestId(String requestId) {
        CURRENT_REQUEST_ID.set(requestId);
    }

    public static String getApiKey() {
        String key = CURRENT_API_KEY.get();
        return (key != null && !key.trim().isEmpty()) ? key : "default";
    }

    public static void setApiKey(String apiKey) {
        CURRENT_API_KEY.set(apiKey);
    }

    public static String getCustomerId() {
        return CURRENT_CUSTOMER_ID.get();
    }

    public static void setCustomerId(String customerId) {
        CURRENT_CUSTOMER_ID.set(customerId);
    }

    public static void clear() {
        CURRENT_REQUEST_ID.remove();
        CURRENT_API_KEY.remove();
        CURRENT_CUSTOMER_ID.remove();
    }
}
