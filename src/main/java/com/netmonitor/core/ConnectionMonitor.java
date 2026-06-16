package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Мониторинг активных сетевых соединений ОС с привязкой к процессам.
 * Кроссплатформенно: Windows (netstat -ano + tasklist), Linux (ss/netstat),
 * macOS (lsof). Работает без нативных библиотек.
 */
public final class ConnectionMonitor {

    private static final String SRC = "ConnectionMonitor";

    public enum OsType { WINDOWS, LINUX, MAC, UNKNOWN }

    private final OsType os = detectOs();
    private final List<Consumer<List<Connection>>> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "conn-monitor");
                t.setDaemon(true);
                return t;
            });

    private ScheduledFuture<?> task;
    private volatile boolean running = false;

    public OsType getOs() {
        return os;
    }

    public void addListener(Consumer<List<Connection>> listener) {
        listeners.add(listener);
    }

    public synchronized void start(int refreshSeconds) {
        if (running) {
            return;
        }
        running = true;
        int period = Math.max(1, refreshSeconds);
        task = scheduler.scheduleWithFixedDelay(this::tick, 0, period, TimeUnit.SECONDS);
        AppLogger.get().info(SRC, "Мониторинг запущен (ОС=" + os + ", интервал=" + period + "с)");
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        AppLogger.get().info(SRC, "Мониторинг остановлен");
    }

    public boolean isRunning() {
        return running;
    }

    private void tick() {
        try {
            List<Connection> conns = snapshot();
            for (Consumer<List<Connection>> l : listeners) {
                try {
                    l.accept(conns);
                } catch (Exception e) {
                    AppLogger.get().error(SRC, "Ошибка слушателя", e);
                }
            }
        } catch (Exception e) {
            AppLogger.get().error(SRC, "Ошибка опроса соединений", e);
        }
    }

    /** Получить мгновенный снимок соединений. */
    public List<Connection> snapshot() {
        return switch (os) {
            case WINDOWS -> parseWindows();
            case LINUX -> parseLinux();
            case MAC -> parseMac();
            default -> new ArrayList<>();
        };
    }

    // ---------------------------------------------------------------------
    // Windows: netstat -ano + tasklist для имени процесса
    // ---------------------------------------------------------------------
    private List<Connection> parseWindows() {
        List<Connection> result = new ArrayList<>();
        Map<String, String> pidToName = windowsPidNames();
        List<String> lines = runCommand(new String[]{"netstat", "-ano"});
        for (String raw : lines) {
            String line = raw.trim();
            if (!(line.startsWith("TCP") || line.startsWith("UDP"))) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 4) {
                continue;
            }
            String proto = parts[0];
            boolean tcp = proto.startsWith("TCP");
            String[] local = splitEndpoint(parts[1]);
            String[] remote = tcp ? splitEndpoint(parts[2]) : new String[]{"*", "0"};
            String state = tcp ? (parts.length >= 4 ? parts[3] : "") : "";
            String pid = parts[parts.length - 1];
            String name = pidToName.getOrDefault(pid, "?");
            result.add(new Connection(proto, local[0], parseInt(local[1]),
                    remote[0], parseInt(remote[1]), state, pid, name));
        }
        return result;
    }

    private Map<String, String> windowsPidNames() {
        Map<String, String> map = new HashMap<>();
        List<String> lines = runCommand(new String[]{"tasklist", "/FO", "CSV", "/NH"});
        for (String line : lines) {
            // "name.exe","1234","Console","1","12,345 K"
            String[] cols = line.split("\",\"");
            if (cols.length >= 2) {
                String name = cols[0].replace("\"", "").trim();
                String pid = cols[1].replace("\"", "").trim();
                map.put(pid, name);
            }
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // Linux: предпочтительно ss, иначе netstat
    // ---------------------------------------------------------------------
    private static final Pattern SS_PROC = Pattern.compile("\\(\\(\"([^\"]+)\",pid=(\\d+)");

    private List<Connection> parseLinux() {
        List<Connection> result = new ArrayList<>();
        List<String> lines = runCommand(new String[]{"ss", "-tunap"});
        if (lines.isEmpty()) {
            lines = runCommand(new String[]{"netstat", "-tunap"});
            return parseNetstatUnix(lines);
        }
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("Netid") || line.startsWith("State")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            String proto = parts[0];
            String state = parts[1];
            // Для udp столбец state может отсутствовать в некоторых версиях; берём по позиции адресов с конца
            String peer = parts[parts.length >= 6 ? parts.length - 2 : parts.length - 1];
            String localStr;
            String peerStr;
            // Надёжнее: ищем два последних поля адресов перед process-полем
            String process = "?";
            String pid = "?";
            Matcher m = SS_PROC.matcher(line);
            if (m.find()) {
                process = m.group(1);
                pid = m.group(2);
            }
            // local и peer — это поля по индексам 4 и 5 в стандартном выводе ss -tunap
            String[] addrParts = line.split("\\s+");
            try {
                localStr = addrParts[4];
                peerStr = addrParts[5];
            } catch (ArrayIndexOutOfBoundsException e) {
                continue;
            }
            String[] local = splitEndpoint(localStr);
            String[] rem = splitEndpoint(peerStr);
            result.add(new Connection(proto.toUpperCase(), local[0], parseInt(local[1]),
                    rem[0], parseInt(rem[1]), state, pid, process));
        }
        return result;
    }

    private List<Connection> parseNetstatUnix(List<String> lines) {
        List<Connection> result = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (!(line.startsWith("tcp") || line.startsWith("udp"))) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 6) {
                continue;
            }
            String proto = parts[0];
            String[] local = splitEndpoint(parts[3]);
            String[] rem = splitEndpoint(parts[4]);
            boolean tcp = proto.startsWith("tcp");
            String state = tcp ? parts[5] : "";
            String procField = parts[parts.length - 1]; // pid/name
            String pid = "?";
            String name = "?";
            if (procField.contains("/")) {
                String[] pn = procField.split("/", 2);
                pid = pn[0];
                name = pn[1];
            }
            result.add(new Connection(proto.toUpperCase(), local[0], parseInt(local[1]),
                    rem[0], parseInt(rem[1]), state, pid, name));
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // macOS: lsof -i -nP
    // ---------------------------------------------------------------------
    private static final Pattern MAC_NAME = Pattern.compile("\\(([A-Z]+)\\)\\s*$");

    private List<Connection> parseMac() {
        List<Connection> result = new ArrayList<>();
        List<String> lines = runCommand(new String[]{"lsof", "-i", "-nP"});
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("COMMAND")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 9) {
                continue;
            }
            String process = parts[0];
            String pid = parts[1];
            String type = parts[7]; // TCP/UDP
            String name = parts[8]; // local->remote
            String state = "";
            Matcher m = MAC_NAME.matcher(line);
            if (m.find()) {
                state = m.group(1);
            }
            String local;
            String remote;
            if (name.contains("->")) {
                String[] lr = name.split("->", 2);
                local = lr[0];
                remote = lr[1];
            } else {
                local = name;
                remote = "*:0";
            }
            String[] l = splitEndpoint(local);
            String[] r = splitEndpoint(remote);
            result.add(new Connection(type, l[0], parseInt(l[1]),
                    r[0], parseInt(r[1]), state, pid, process));
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Вспомогательные методы
    // ---------------------------------------------------------------------
    private String[] splitEndpoint(String endpoint) {
        if (endpoint == null) {
            return new String[]{"*", "0"};
        }
        endpoint = endpoint.trim();
        // IPv6: [::1]:443 или ::1.443 (mac). Берём последний разделитель.
        int idx = endpoint.lastIndexOf(':');
        if (idx < 0) {
            // mac иногда использует точку как разделитель порта
            idx = endpoint.lastIndexOf('.');
        }
        if (idx < 0) {
            return new String[]{endpoint, "0"};
        }
        String host = endpoint.substring(0, idx);
        String port = endpoint.substring(idx + 1);
        host = host.replace("[", "").replace("]", "");
        if (host.isEmpty() || host.equals("*")) {
            host = "*";
        }
        return new String[]{host, port};
    }

    private int parseInt(String s) {
        try {
            if (s == null || s.equals("*") || s.isBlank()) {
                return 0;
            }
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> runCommand(String[] cmd) {
        List<String> out = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(false);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    out.add(line);
                }
            }
            // дренируем stderr, чтобы процесс не завис
            try (InputStream err = p.getErrorStream()) {
                err.readAllBytes();
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            AppLogger.get().debug(SRC, "Команда не выполнена: " + String.join(" ", cmd)
                    + " (" + e.getMessage() + ")");
        }
        return out;
    }

    private static OsType detectOs() {
        String name = System.getProperty("os.name", "").toLowerCase();
        if (name.contains("win")) {
            return OsType.WINDOWS;
        }
        if (name.contains("mac") || name.contains("darwin")) {
            return OsType.MAC;
        }
        if (name.contains("nux") || name.contains("nix") || name.contains("aix")) {
            return OsType.LINUX;
        }
        return OsType.UNKNOWN;
    }

    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }
}
