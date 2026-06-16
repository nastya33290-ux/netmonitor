package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Настройки приложения с сохранением в ~/.netmonitor/settings.properties
 */
public final class Settings {

    private static final String SRC = "Settings";

    private final Path dir;
    private final Path file;
    private final Properties props = new Properties();

    public Settings() {
        this.dir = Paths.get(System.getProperty("user.home"), ".netmonitor");
        this.file = dir.resolve("settings.properties");
        loadDefaults();
        load();
    }

    private void loadDefaults() {
        props.setProperty("proxy.port", "8888");
        props.setProperty("proxy.autostart", "false");
        props.setProperty("monitor.refreshSeconds", "3");
        props.setProperty("monitor.autostart", "true");
        props.setProperty("log.level", "DEBUG");
        props.setProperty("proxy.logBodies", "true");
        props.setProperty("proxy.maxBodyLogBytes", "8192");
    }

    public synchronized void load() {
        if (Files.exists(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
                AppLogger.get().info(SRC, "Настройки загружены из " + file);
            } catch (IOException e) {
                AppLogger.get().error(SRC, "Не удалось загрузить настройки", e);
            }
        } else {
            AppLogger.get().info(SRC, "Файл настроек не найден, используются значения по умолчанию");
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(dir);
            try (OutputStream out = Files.newOutputStream(file)) {
                props.store(out, "NetMonitor settings");
                AppLogger.get().info(SRC, "Настройки сохранены в " + file);
            }
        } catch (IOException e) {
            AppLogger.get().error(SRC, "Не удалось сохранить настройки", e);
        }
    }

    public String get(String key) {
        return props.getProperty(key);
    }

    public void set(String key, String value) {
        props.setProperty(key, value);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        if (v == null) {
            return def;
        }
        return Boolean.parseBoolean(v.trim());
    }

    public Path getFile() {
        return file;
    }
}
