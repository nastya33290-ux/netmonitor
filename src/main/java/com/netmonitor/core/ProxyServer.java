package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * HTTP(S) прокси-сервер.
 * - Обычный HTTP: полный перехват (метод, URL, заголовки, тело, статус ответа).
 * - HTTPS (метод CONNECT): туннель; блокировка/лог по хосту (содержимое зашифровано).
 * Применяет правила блокировки из RuleEngine.
 */
public final class ProxyServer {

    private static final String SRC = "Proxy";
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private final RuleEngine ruleEngine;
    private final List<Consumer<CapturedRequest>> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean logBodies = true;
    private volatile int maxBodyLogBytes = 8192;

    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private volatile boolean running = false;
    private volatile int port = 8888;

    public ProxyServer(RuleEngine ruleEngine) {
        this.ruleEngine = ruleEngine;
    }

    public void addListener(Consumer<CapturedRequest> listener) {
        listeners.add(listener);
    }

    public void setLogBodies(boolean logBodies) {
        this.logBodies = logBodies;
    }

    public void setMaxBodyLogBytes(int maxBodyLogBytes) {
        this.maxBodyLogBytes = maxBodyLogBytes;
    }

    public boolean isRunning() {
        return running;
    }

    public int getPort() {
        return port;
    }

    public synchronized void start(int port) throws IOException {
        if (running) {
            return;
        }
        this.port = port;
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxy-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        acceptThread = new Thread(this::acceptLoop, "proxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        AppLogger.get().info(SRC, "Прокси запущен на порту " + port);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (pool != null) {
            pool.shutdownNow();
        }
        AppLogger.get().info(SRC, "Прокси остановлен");
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                pool.submit(() -> handle(client));
            } catch (IOException e) {
                if (running) {
                    AppLogger.get().debug(SRC, "accept прерван: " + e.getMessage());
                }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(30000);
            PushbackInputStream in = new PushbackInputStream(client.getInputStream(), MAX_HEADER_BYTES);
            OutputStream out = client.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) {
                client.close();
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                client.close();
                return;
            }
            String method = parts[0];
            String target = parts[1];
            String version = parts[2];

            if ("CONNECT".equalsIgnoreCase(method)) {
                handleConnect(client, in, out, target);
            } else {
                handleHttp(client, in, out, method, target, version);
            }
        } catch (IOException e) {
            AppLogger.get().debug(SRC, "Ошибка обработки клиента: " + e.getMessage());
            closeQuietly(client);
        }
    }

    // ------------------------------------------------------------------
    // HTTPS туннель (CONNECT host:port)
    // ------------------------------------------------------------------
    private void handleConnect(Socket client, InputStream in, OutputStream out, String target) {
        CapturedRequest cap = new CapturedRequest();
        cap.setMethod("CONNECT");
        cap.setScheme("https");
        String host = target;
        int port = 443;
        int idx = target.lastIndexOf(':');
        if (idx > 0) {
            host = target.substring(0, idx);
            try {
                port = Integer.parseInt(target.substring(idx + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        cap.setHost(host);
        cap.setPort(port);
        cap.setFullUrl("https://" + host + ":" + port);
        cap.setPath("/");

        // дочитываем оставшиеся заголовки CONNECT
        StringBuilder headers = new StringBuilder();
        try {
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headers.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        cap.setRequestHeaders(headers.toString());

        BlockRule blocking = ruleEngine.findBlocking(host, cap.getFullUrl(), "CONNECT",
                BlockRule.Direction.OUTGOING);
        if (blocking != null) {
            cap.setBlocked(true);
            cap.setBlockedByRule(blocking.getName());
            writeQuietly(out, "HTTP/1.1 403 Forbidden\r\n"
                    + "Content-Length: 0\r\n"
                    + "X-Blocked-By: NetMonitor\r\n\r\n");
            publish(cap);
            AppLogger.get().warn(SRC, "ЗАБЛОКИРОВАН CONNECT " + host + ":" + port
                    + " правилом '" + blocking.getName() + "'");
            closeQuietly(client);
            return;
        }

        Socket server = null;
        try {
            server = new Socket();
            server.connect(new InetSocketAddress(host, port), 15000);
            out.write(("HTTP/1.1 200 Connection Established\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            cap.setStatusCode(200);
            publish(cap);
            AppLogger.get().info(SRC, "Туннель установлен: " + host + ":" + port);

            // двунаправленная перекачка
            Socket finalServer = server;
            Thread up = new Thread(() -> pump(client, finalServer));
            up.setDaemon(true);
            up.start();
            pump(finalServer, client);
            up.join();
        } catch (Exception e) {
            cap.setError(e.getMessage());
            publish(cap);
            AppLogger.get().debug(SRC, "CONNECT не удался для " + host + ":" + port + " :: " + e.getMessage());
            writeQuietly(out, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
        } finally {
            closeQuietly(server);
            closeQuietly(client);
        }
    }

    private void pump(Socket from, Socket to) {
        byte[] buf = new byte[16384];
        try {
            InputStream fin = from.getInputStream();
            OutputStream tout = to.getOutputStream();
            int n;
            while ((n = fin.read(buf)) != -1) {
                tout.write(buf, 0, n);
                tout.flush();
            }
        } catch (IOException ignored) {
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Обычный HTTP-прокси
    // ------------------------------------------------------------------
    private void handleHttp(Socket client, PushbackInputStream in, OutputStream out,
                            String method, String target, String version) {
        CapturedRequest cap = new CapturedRequest();
        cap.setMethod(method);
        cap.setScheme("http");

        String host;
        int port = 80;
        String path;
        String fullUrl = target;

        // target в absolute-form: http://host:port/path
        if (target.startsWith("http://")) {
            String rest = target.substring("http://".length());
            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            path = slash >= 0 ? rest.substring(slash) : "/";
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon);
                try {
                    port = Integer.parseInt(authority.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                }
            } else {
                host = authority;
            }
        } else {
            // origin-form — host берём из Host-заголовка позже
            path = target;
            host = null;
        }

        // читаем заголовки
        List<String> headerLines = new ArrayList<>();
        long contentLength = 0;
        try {
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                headerLines.add(line);
                String lower = line.toLowerCase();
                if (lower.startsWith("host:") && host == null) {
                    String hv = line.substring(line.indexOf(':') + 1).trim();
                    int colon = hv.indexOf(':');
                    if (colon >= 0) {
                        host = hv.substring(0, colon);
                        try {
                            port = Integer.parseInt(hv.substring(colon + 1));
                        } catch (NumberFormatException ignored) {
                        }
                    } else {
                        host = hv;
                    }
                }
                if (lower.startsWith("content-length:")) {
                    try {
                        contentLength = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            closeQuietly(client);
            return;
        }

        if (host == null) {
            writeQuietly(out, "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\n\r\n");
            closeQuietly(client);
            return;
        }
        if (fullUrl == null || !fullUrl.startsWith("http")) {
            fullUrl = "http://" + host + (port != 80 ? ":" + port : "") + path;
        }

        cap.setHost(host);
        cap.setPort(port);
        cap.setPath(path);
        cap.setFullUrl(fullUrl);
        cap.setRequestHeaders(String.join("\n", headerLines));

        // читаем тело (если есть) для пересылки и предпросмотра
        byte[] body = readBody(in, contentLength);
        if (logBodies && body.length > 0) {
            int n = Math.min(body.length, maxBodyLogBytes);
            cap.setRequestBodyPreview(new String(body, 0, n, StandardCharsets.UTF_8));
        }

        // проверка правил
        BlockRule blocking = ruleEngine.findBlocking(host, fullUrl, method, BlockRule.Direction.OUTGOING);
        if (blocking != null) {
            cap.setBlocked(true);
            cap.setBlockedByRule(blocking.getName());
            cap.setStatusCode(403);
            writeQuietly(out, "HTTP/1.1 403 Forbidden\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "X-Blocked-By: NetMonitor\r\n"
                    + "Connection: close\r\n\r\n"
                    + "Blocked by NetMonitor rule: " + blocking.getName());
            publish(cap);
            AppLogger.get().warn(SRC, "ЗАБЛОКИРОВАН " + method + " " + fullUrl
                    + " правилом '" + blocking.getName() + "'");
            closeQuietly(client);
            return;
        }

        // пересылка на целевой сервер
        try (Socket server = new Socket()) {
            server.connect(new InetSocketAddress(host, port), 15000);
            server.setSoTimeout(30000);
            OutputStream sOut = server.getOutputStream();

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            for (String h : headerLines) {
                // proxy-connection не нужен апстриму
                if (h.toLowerCase().startsWith("proxy-connection")) {
                    continue;
                }
                req.append(h).append("\r\n");
            }
            req.append("\r\n");
            sOut.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body.length > 0) {
                sOut.write(body);
            }
            sOut.flush();

            // читаем ответ: парсим статус + считаем байты, всё ретранслируем клиенту
            InputStream sIn = server.getInputStream();
            relayResponse(sIn, out, cap);
            publish(cap);
            AppLogger.get().info(SRC, method + " " + fullUrl + " -> " + cap.getStatusCode());
        } catch (IOException e) {
            cap.setError(e.getMessage());
            publish(cap);
            writeQuietly(out, "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n");
            AppLogger.get().debug(SRC, "Ошибка пересылки " + fullUrl + " :: " + e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    private void relayResponse(InputStream sIn, OutputStream clientOut, CapturedRequest cap) throws IOException {
        // первая строка статуса
        StringBuilder statusLine = new StringBuilder();
        int c;
        while ((c = sIn.read()) != -1) {
            clientOut.write(c);
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                statusLine.append((char) c);
            }
        }
        String sl = statusLine.toString().trim();
        String[] sp = sl.split(" ");
        if (sp.length >= 2) {
            try {
                cap.setStatusCode(Integer.parseInt(sp[1]));
            } catch (NumberFormatException ignored) {
            }
        }

        // заголовки ответа
        StringBuilder respHeaders = new StringBuilder();
        StringBuilder cur = new StringBuilder();
        long total = 0;
        boolean headersDone = false;
        while (!headersDone && (c = sIn.read()) != -1) {
            clientOut.write(c);
            if (c == '\n') {
                String line = cur.toString();
                if (line.isEmpty()) {
                    headersDone = true;
                } else {
                    respHeaders.append(line).append('\n');
                }
                cur.setLength(0);
            } else if (c != '\r') {
                cur.append((char) c);
            }
        }
        cap.setResponseHeaders(respHeaders.toString());

        // тело ответа — просто ретранслируем и считаем байты
        byte[] buf = new byte[16384];
        int n;
        while ((n = sIn.read(buf)) != -1) {
            clientOut.write(buf, 0, n);
            total += n;
        }
        clientOut.flush();
        cap.setResponseBytes(total);
    }

    private byte[] readBody(InputStream in, long contentLength) {
        if (contentLength <= 0) {
            return new byte[0];
        }
        try {
            byte[] body = new byte[(int) Math.min(contentLength, Integer.MAX_VALUE)];
            int off = 0;
            while (off < body.length) {
                int r = in.read(body, off, body.length - off);
                if (r == -1) {
                    break;
                }
                off += r;
            }
            if (off < body.length) {
                byte[] trimmed = new byte[off];
                System.arraycopy(body, 0, trimmed, 0, off);
                return trimmed;
            }
            return body;
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        int count = 0;
        while ((c = in.read()) != -1) {
            if (++count > MAX_HEADER_BYTES) {
                break;
            }
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    private void publish(CapturedRequest cap) {
        for (Consumer<CapturedRequest> l : listeners) {
            try {
                l.accept(cap);
            } catch (Exception e) {
                AppLogger.get().error(SRC, "Ошибка слушателя прокси", e);
            }
        }
    }

    private void writeQuietly(OutputStream out, String s) {
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }
}
