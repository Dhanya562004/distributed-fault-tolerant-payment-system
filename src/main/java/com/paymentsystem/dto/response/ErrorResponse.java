package com.paymentsystem.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Standardized consistent API error response format containing code, message, and requestId.
 */
public class ErrorResponse {

    private String code;
    private String message;
    private String requestId;
    private int status;
    private String error;
    private String path;
    private String traceId;
    private List<String> details;
    private Instant timestamp;

    public ErrorResponse() {
        this.timestamp = Instant.now();
    }

    public ErrorResponse(int status, String code, String message, String path, String requestId, List<String> details) {
        this.status = status;
        this.code = code;
        this.error = code;
        this.message = message;
        this.path = path;
        this.requestId = requestId;
        this.traceId = requestId;
        this.details = details;
        this.timestamp = Instant.now();
    }

    public String getCode() {
        return code != null ? code : error;
    }

    public void setCode(String code) {
        this.code = code;
        this.error = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRequestId() {
        return requestId != null ? requestId : traceId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
        this.traceId = requestId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public String getError() {
        return error != null ? error : code;
    }

    public void setError(String error) {
        this.error = error;
        this.code = error;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getTraceId() {
        return traceId != null ? traceId : requestId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
        this.requestId = traceId;
    }

    public List<String> getDetails() {
        return details;
    }

    public void setDetails(List<String> details) {
        this.details = details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }
}
