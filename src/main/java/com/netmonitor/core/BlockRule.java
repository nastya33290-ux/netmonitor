package com.netmonitor.core;

import java.util.regex.Pattern;

/**
 * Правило блокировки HTTP(S) запроса.
 * Сопоставление по хосту и/или URL/пути, с учётом направления и метода.
 */
public final class BlockRule {

    public enum Direction {
        ANY,      // любое
        OUTGOING, // исходящие (от нас на сервер) — для прокси это все запросы
        INCOMING  // условно: блокировка ответов (соединение рвётся)
    }

    private boolean enabled = true;
    private String name = "rule";
    private String hostPattern = "";   // glob-подобный: *.example.com
    private String urlPattern = "";    // подстрока или regex (если useRegex)
    private String method = "ANY";     // GET/POST/.. или ANY
    private Direction direction = Direction.ANY;
    private boolean useRegex = false;

    private transient Pattern compiledHost;
    private transient Pattern compiledUrl;

    public BlockRule() {
    }

    public BlockRule(String name, String hostPattern, String urlPattern) {
        this.name = name;
        this.hostPattern = hostPattern == null ? "" : hostPattern;
        this.urlPattern = urlPattern == null ? "" : urlPattern;
    }

    /** Проверка соответствия запроса правилу. */
    public boolean matches(String host, String url, String method, Direction dir) {
        if (!enabled) {
            return false;
        }
        if (direction != Direction.ANY && dir != Direction.ANY && direction != dir) {
            return false;
        }
        if (!this.method.equalsIgnoreCase("ANY")
                && method != null
                && !this.method.equalsIgnoreCase(method)) {
            return false;
        }
        if (hostPattern != null && !hostPattern.isBlank()) {
            if (!matchHost(host)) {
                return false;
            }
        }
        if (urlPattern != null && !urlPattern.isBlank()) {
            if (!matchUrl(url)) {
                return false;
            }
        }
        // Если оба паттерна пустые — правило не матчит ничего (защита от блокировки всего)
        return (hostPattern != null && !hostPattern.isBlank())
                || (urlPattern != null && !urlPattern.isBlank());
    }

    private boolean matchHost(String host) {
        if (host == null) {
            return false;
        }
        if (useRegex) {
            ensureCompiled();
            return compiledHost != null && compiledHost.matcher(host).find();
        }
        return globMatch(hostPattern.toLowerCase(), host.toLowerCase());
    }

    private boolean matchUrl(String url) {
        if (url == null) {
            return false;
        }
        if (useRegex) {
            ensureCompiled();
            return compiledUrl != null && compiledUrl.matcher(url).find();
        }
        return url.toLowerCase().contains(urlPattern.toLowerCase());
    }

    private void ensureCompiled() {
        if (compiledHost == null && hostPattern != null && !hostPattern.isBlank()) {
            try {
                compiledHost = Pattern.compile(hostPattern);
            } catch (Exception ignored) {
            }
        }
        if (compiledUrl == null && urlPattern != null && !urlPattern.isBlank()) {
            try {
                compiledUrl = Pattern.compile(urlPattern);
            } catch (Exception ignored) {
            }
        }
    }

    /** Простой glob: поддержка '*' (любая последовательность). */
    private static boolean globMatch(String pattern, String text) {
        StringBuilder regex = new StringBuilder("^");
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '.' -> regex.append("\\.");
                case '?' -> regex.append('.');
                default -> {
                    if ("\\+()[]{}^$|".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                }
            }
        }
        regex.append('$');
        try {
            return Pattern.matches(regex.toString(), text);
        } catch (Exception e) {
            return false;
        }
    }

    // ---- сериализация в одну строку для хранения ----
    public String serialize() {
        return String.join("\t",
                bool(enabled),
                esc(name),
                esc(hostPattern),
                esc(urlPattern),
                esc(method),
                direction.name(),
                bool(useRegex));
    }

    public static BlockRule deserialize(String line) {
        String[] p = line.split("\t", -1);
        BlockRule r = new BlockRule();
        if (p.length > 0) r.enabled = Boolean.parseBoolean(p[0]);
        if (p.length > 1) r.name = unesc(p[1]);
        if (p.length > 2) r.hostPattern = unesc(p[2]);
        if (p.length > 3) r.urlPattern = unesc(p[3]);
        if (p.length > 4) r.method = unesc(p[4]);
        if (p.length > 5) {
            try {
                r.direction = Direction.valueOf(p[5]);
            } catch (Exception e) {
                r.direction = Direction.ANY;
            }
        }
        if (p.length > 6) r.useRegex = Boolean.parseBoolean(p[6]);
        return r;
    }

    private static String bool(boolean b) {
        return Boolean.toString(b);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\t", " ").replace("\n", " ");
    }

    private static String unesc(String s) {
        return s == null ? "" : s;
    }

    // ---- getters / setters ----
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getHostPattern() {
        return hostPattern;
    }

    public void setHostPattern(String hostPattern) {
        this.hostPattern = hostPattern;
        this.compiledHost = null;
    }

    public String getUrlPattern() {
        return urlPattern;
    }

    public void setUrlPattern(String urlPattern) {
        this.urlPattern = urlPattern;
        this.compiledUrl = null;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Direction getDirection() {
        return direction;
    }

    public void setDirection(Direction direction) {
        this.direction = direction;
    }

    public boolean isUseRegex() {
        return useRegex;
    }

    public void setUseRegex(boolean useRegex) {
        this.useRegex = useRegex;
        this.compiledHost = null;
        this.compiledUrl = null;
    }
}
