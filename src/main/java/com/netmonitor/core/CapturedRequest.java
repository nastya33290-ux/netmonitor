package com.netmonitor.core;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Перехваченный прокси-сервером запрос (и краткая информация об ответе).
 */
public final class CapturedRequest {

    private static final AtomicLong SEQ = new AtomicLong();

    private final long id = SEQ.incrementAndGet();
    private final LocalDateTime time = LocalDateTime.now();

    private String method;
    private String scheme;   // http / https(CONNECT)
    private String host;
    private int port;
    private String path;
    private String fullUrl;
    private String requestHeaders = "";
    private String requestBodyPreview = "";

    private int statusCode = -1;
    private String responseHeaders = "";
    private long responseBytes = -1;

    private boolean blocked = false;
    private String blockedByRule = "";
    private String error = "";

    public long getId() {
        return id;
    }

    public LocalDateTime getTime() {
        return time;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getScheme() {
        return scheme;
    }

    public void setScheme(String scheme) {
        this.scheme = scheme;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getFullUrl() {
        return fullUrl;
    }

    public void setFullUrl(String fullUrl) {
        this.fullUrl = fullUrl;
    }

    public String getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(String requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public String getRequestBodyPreview() {
        return requestBodyPreview;
    }

    public void setRequestBodyPreview(String requestBodyPreview) {
        this.requestBodyPreview = requestBodyPreview;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(String responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public long getResponseBytes() {
        return responseBytes;
    }

    public void setResponseBytes(long responseBytes) {
        this.responseBytes = responseBytes;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public String getBlockedByRule() {
        return blockedByRule;
    }

    public void setBlockedByRule(String blockedByRule) {
        this.blockedByRule = blockedByRule;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
