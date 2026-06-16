package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Хранилище и движок применения правил блокировки.
 * Правила сохраняются в ~/.netmonitor/rules.tsv
 */
public final class RuleEngine {

    private static final String SRC = "RuleEngine";

    private final List<BlockRule> rules = new CopyOnWriteArrayList<>();
    private final Path file;

    public RuleEngine() {
        Path dir = Paths.get(System.getProperty("user.home"), ".netmonitor");
        this.file = dir.resolve("rules.tsv");
        load();
    }

    /** Возвращает совпавшее правило или null, если запрос разрешён. */
    public BlockRule findBlocking(String host, String url, String method, BlockRule.Direction dir) {
        for (BlockRule r : rules) {
            if (r.matches(host, url, method, dir)) {
                return r;
            }
        }
        return null;
    }

    public boolean isBlocked(String host, String url, String method, BlockRule.Direction dir) {
        return findBlocking(host, url, method, dir) != null;
    }

    public List<BlockRule> getRules() {
        return new ArrayList<>(rules);
    }

    public void addRule(BlockRule rule) {
        rules.add(rule);
        save();
    }

    public void removeRule(BlockRule rule) {
        rules.remove(rule);
        save();
    }

    public void replaceAll(List<BlockRule> newRules) {
        rules.clear();
        rules.addAll(newRules);
        save();
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            List<String> lines = new ArrayList<>();
            for (BlockRule r : rules) {
                lines.add(r.serialize());
            }
            Files.write(file, lines, StandardCharsets.UTF_8);
            AppLogger.get().debug(SRC, "Сохранено правил: " + rules.size());
        } catch (IOException e) {
            AppLogger.get().error(SRC, "Не удалось сохранить правила", e);
        }
    }

    public synchronized void load() {
        rules.clear();
        if (!Files.exists(file)) {
            AppLogger.get().info(SRC, "Файл правил отсутствует, начинаем с пустого списка");
            return;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                rules.add(BlockRule.deserialize(line));
            }
            AppLogger.get().info(SRC, "Загружено правил: " + rules.size());
        } catch (IOException e) {
            AppLogger.get().error(SRC, "Не удалось загрузить правила", e);
        }
    }
}
