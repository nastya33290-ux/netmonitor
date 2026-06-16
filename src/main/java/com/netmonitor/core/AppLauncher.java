package com.netmonitor.core;

import com.netmonitor.util.AppLogger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Запуск внешних приложений с маршрутизацией трафика через локальный прокси.
 * Прокси прокидывается двумя способами одновременно:
 *  - переменные окружения HTTP_PROXY / HTTPS_PROXY / ALL_PROXY (и в нижнем регистре);
 *  - опционально аргументы запуска (например, для Chromium: --proxy-server=...).
 */
public final class AppLauncher {

    private static final String SRC = "Launcher";

    /** Описание запущенного процесса. */
    public static final class Launched {
        private final String command;
        private final Process process;
        private final long startTime;

        Launched(String command, Process process) {
            this.command = command;
            this.process = process;
            this.startTime = System.currentTimeMillis();
        }

        public String getCommand() {
            return command;
        }

        public Process getProcess() {
            return process;
        }

        public long pid() {
            try {
                return process.pid();
            } catch (Exception e) {
                return -1;
            }
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public long getStartTime() {
            return startTime;
        }

        public void stop() {
            process.destroy();
        }

        public void forceStop() {
            process.destroyForcibly();
        }
    }

    private final List<Launched> launched = new CopyOnWriteArrayList<>();

    /** Слушатели появления нового дочернего процесса: (родитель, дескриптор ребёнка). */
    private final List<BiConsumer<Launched, ProcessHandle>> childListeners = new CopyOnWriteArrayList<>();
    /** Уже известные дочерние PID, чтобы не сообщать о них повторно. */
    private final Set<Long> knownChildren = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService watcher;

    public List<Launched> getLaunched() {
        // чистим завершённые
        launched.removeIf(l -> !l.isAlive());
        return new ArrayList<>(launched);
    }

    public void addChildListener(BiConsumer<Launched, ProcessHandle> listener) {
        childListeners.add(listener);
    }

    /**
     * Запускает фоновое наблюдение за дочерними процессами запущенных приложений.
     * Когда приложение порождает новый процесс (например, браузер открывает рендерер
     * или хелпер), он обнаруживается здесь. Так как дочерние процессы наследуют
     * окружение родителя (включая HTTP(S)_PROXY), их трафик уже идёт через прокси.
     */
    public synchronized void startChildWatcher() {
        if (watcher != null) {
            return;
        }
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "child-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(this::scanChildren, 1, 1, TimeUnit.SECONDS);
        AppLogger.get().info(SRC, "Наблюдение за дочерними процессами запущено");
    }

    private void scanChildren() {
        try {
            for (Launched l : launched) {
                if (!l.isAlive()) {
                    continue;
                }
                l.getProcess().descendants().forEach(ph -> {
                    if (knownChildren.add(ph.pid())) {
                        String cmd = ph.info().commandLine()
                                .orElse(ph.info().command().orElse("?"));
                        AppLogger.get().info(SRC, "ДЕТЕКТ дочернего процесса: родитель pid="
                                + l.pid() + " -> ребёнок pid=" + ph.pid() + " : " + cmd
                                + " (перехват через унаследованный прокси)");
                        for (BiConsumer<Launched, ProcessHandle> listener : childListeners) {
                            try {
                                listener.accept(l, ph);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            }
        } catch (Exception e) {
            AppLogger.get().debug(SRC, "Ошибка сканирования дочерних процессов: " + e.getMessage());
        }
    }

    /** Все потомки запущенного процесса (рекурсивно), снимок на текущий момент. */
    public List<ProcessHandle> descendants(Launched l) {
        List<ProcessHandle> out = new ArrayList<>();
        try {
            l.getProcess().descendants().forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * Запустить приложение.
     *
     * @param executable    путь к исполняемому файлу
     * @param argsLine      аргументы (строка, делится по пробелам с учётом кавычек)
     * @param proxyPort     порт локального прокси
     * @param setEnvProxy   проставлять переменные окружения прокси
     * @param output        потребитель строк вывода процесса (stdout+stderr)
     * @return объект запущенного процесса
     */
    public Launched launch(String executable, String argsLine, int proxyPort,
                           boolean setEnvProxy, Consumer<String> output) throws IOException {
        if (executable == null || executable.isBlank()) {
            throw new IOException("Не указан путь к приложению");
        }
        List<String> command = new ArrayList<>();
        command.add(executable.trim());
        command.addAll(splitArgs(argsLine));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        if (setEnvProxy) {
            String proxyUrl = "http://127.0.0.1:" + proxyPort;
            Map<String, String> env = pb.environment();
            for (String key : new String[]{"HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
                    "http_proxy", "https_proxy", "all_proxy"}) {
                env.put(key, proxyUrl);
            }
            // не исключаем localhost из проксирования по умолчанию
            env.put("NO_PROXY", "");
            env.put("no_proxy", "");
        }

        AppLogger.get().info(SRC, "Запуск: " + String.join(" ", command)
                + (setEnvProxy ? " | proxy env -> 127.0.0.1:" + proxyPort : ""));

        Process process = pb.start();
        Launched l = new Launched(String.join(" ", command), process);
        launched.add(l);

        // читаем вывод процесса в отдельном потоке
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (output != null) {
                        output.accept(line);
                    }
                }
            } catch (IOException ignored) {
            }
            int code = -1;
            try {
                code = process.waitFor();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            AppLogger.get().info(SRC, "Процесс завершён (pid=" + l.pid() + ", код=" + code + ")");
            if (output != null) {
                output.accept("[процесс завершён, код выхода: " + code + "]");
            }
        }, "launched-reader-" + l.pid());
        reader.setDaemon(true);
        reader.start();

        return l;
    }

    public void stopAll() {
        for (Launched l : launched) {
            try {
                l.forceStop();
            } catch (Exception ignored) {
            }
        }
        launched.clear();
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }

    /** Делит строку аргументов по пробелам с поддержкой кавычек. */
    static List<String> splitArgs(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) {
            return out;
        }
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quoteChar = '"';
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == quoteChar) {
                    inQuotes = false;
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                inQuotes = true;
                quoteChar = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }
}
