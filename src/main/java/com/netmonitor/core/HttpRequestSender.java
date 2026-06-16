package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Отправка собственных HTTP-запросов (мини-Postman).
 * Поддерживает методы, произвольные заголовки и тело, опционально через локальный прокси.
 */
public final class HttpRequestSender {

    private static final String SRC = "Sender";

    public record Result(int statusCode, String statusInfo, String headers, String body, long elapsedMs, String error) {
        public boolean isError() {
            return error != null && !error.isBlank();
        }
    }

    /**
     * @param method   GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS
     * @param url      полный URL
     * @param headers  заголовки в формате "Name: value" по строкам
     * @param body     тело (для методов с телом)
     * @param useProxy слать через локальный прокси
     * @param proxyPort порт прокси
     */
    public Result send(String method, String url, String headers, String body,
                       boolean useProxy, int proxyPort) {
        long start = System.currentTimeMillis();
        try {
            HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL);

            if (useProxy) {
                clientBuilder.proxy(java.net.ProxySelector.of(
                        new java.net.InetSocketAddress("127.0.0.1", proxyPort)));
            }
            HttpClient client = clientBuilder.build();

            HttpRequest.Builder rb = HttpRequest.newBuilder()
                    .uri(URI.create(url.trim()))
                    .timeout(Duration.ofSeconds(30));

            HttpRequest.BodyPublisher publisher =
                    (body == null || body.isEmpty())
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body);

            String m = method == null ? "GET" : method.trim().toUpperCase();
            rb.method(m, publisher);

            // заголовки
            if (headers != null) {
                for (String line : headers.split("\\r?\\n")) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int idx = line.indexOf(':');
                    if (idx <= 0) {
                        continue;
                    }
                    String name = line.substring(0, idx).trim();
                    String value = line.substring(idx + 1).trim();
                    if (isRestrictedHeader(name)) {
                        AppLogger.get().debug(SRC, "Пропущен системный заголовок: " + name);
                        continue;
                    }
                    try {
                        rb.header(name, value);
                    } catch (IllegalArgumentException ex) {
                        AppLogger.get().warn(SRC, "Некорректный заголовок пропущен: " + name);
                    }
                }
            }

            AppLogger.get().info(SRC, m + " " + url + (useProxy ? " (через прокси :" + proxyPort + ")" : ""));
            HttpResponse<String> resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofString());

            StringBuilder hb = new StringBuilder();
            for (Map.Entry<String, List<String>> e : resp.headers().map().entrySet()) {
                hb.append(e.getKey()).append(": ").append(String.join(", ", e.getValue())).append('\n');
            }
            long elapsed = System.currentTimeMillis() - start;
            AppLogger.get().info(SRC, "Ответ " + resp.statusCode() + " за " + elapsed + "мс");
            return new Result(resp.statusCode(), httpStatusText(resp.statusCode()),
                    hb.toString(), resp.body(), elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            AppLogger.get().error(SRC, "Ошибка запроса", e);
            return new Result(-1, "ERROR", "", "", elapsed,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private boolean isRestrictedHeader(String name) {
        String n = name.toLowerCase();
        return n.equals("host") || n.equals("connection") || n.equals("content-length")
                || n.equals("upgrade") || n.equals("expect") || n.startsWith("sec-")
                || n.equals("date") || n.equals("via") || n.equals("transfer-encoding");
    }

    private String httpStatusText(int code) {
        return switch (code) {
            case 200 -> "OK";
            case 201 -> "Created";
            case 204 -> "No Content";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 304 -> "Not Modified";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 429 -> "Too Many Requests";
            case 500 -> "Internal Server Error";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            default -> "";
        };
    }
}
