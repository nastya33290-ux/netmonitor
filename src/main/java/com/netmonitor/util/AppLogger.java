package com.netmonitor.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Простой потокобезопасный логгер с уровнями и подпиской слушателей (для UI-панели логов).
 */
public final class AppLogger {

    public enum Level {
        DEBUG, INFO, WARN, ERROR
    }

    public record LogEntry(LocalDateTime time, Level level, String source, String message) {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        @Override
        public String toString() {
            return "[" + FMT.format(time) + "] [" + level + "] [" + source + "] " + message;
        }
    }

    private static final AppLogger INSTANCE = new AppLogger();

    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private volatile Level minLevel = Level.DEBUG;

    private AppLogger() {
    }

    public static AppLogger get() {
        return INSTANCE;
    }

    public void setMinLevel(Level level) {
        this.minLevel = level;
    }

    public Level getMinLevel() {
        return minLevel;
    }

    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    public void debug(String source, String message) {
        log(Level.DEBUG, source, message);
    }

    public void info(String source, String message) {
        log(Level.INFO, source, message);
    }

    public void warn(String source, String message) {
        log(Level.WARN, source, message);
    }

    public void error(String source, String message) {
        log(Level.ERROR, source, message);
    }

    public void error(String source, String message, Throwable t) {
        log(Level.ERROR, source, message + " :: " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    public void log(Level level, String source, String message) {
        if (level.ordinal() < minLevel.ordinal()) {
            return;
        }
        LogEntry entry = new LogEntry(LocalDateTime.now(), level, source, message);
        // дублируем в консоль для запуска из терминала
        System.out.println(entry);
        for (Consumer<LogEntry> l : listeners) {
            try {
                l.accept(entry);
            } catch (Exception ignored) {
                // слушатель UI не должен ломать логирование
            }
        }
    }
}
