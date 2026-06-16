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

    /** Обнаруженный процесс, связанный с отслеживаемым приложением. */
    public record Detected(long pid, long openedByPid, String command, String linkInfo) {
    }

    /** Слушатели обнаружения нового процесса, связанного с отслеживаемым приложением. */
    private final List<Consumer<Detected>> processListeners = new CopyOnWriteArrayList<>();
    /** PID-ы, относящиеся к отслеживаемому "дереву" (запущенные нами + транзитивно связанные). */
    private final Set<Long> trackedPids = ConcurrentHashMap.newKeySet();
    /** Уже обработанные PID, чтобы не сообщать повторно. */
    private final Set<Long> reportedPids = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService watcher;

    public List<Launched> getLaunched() {
        // чистим завершённые
        launched.removeIf(l -> !l.isAlive());
        return new ArrayList<>(launched);
    }

    public void addProcessListener(Consumer<Detected> listener) {
        processListeners.add(listener);
    }

    /**
     * Запускает фоновое наблюдение за процессами.
     * <p>
     * В отличие от простого обхода дерева потомков, здесь сканируются ВСЕ процессы
     * системы, и новый процесс считается "нашим", если в цепочке его предков есть
     * любой уже отслеживаемый PID. Это позволяет ловить случаи, когда одно
     * приложение запускает ДРУГОЕ приложение, которое затем "отвязывается" от
     * родителя (классический паттерн bootstrapper -> launcher, где лаунчер
     * переподвязывается к init/explorer). Однажды обнаруженный процесс остаётся
     * отслеживаемым, поэтому связь не теряется даже после завершения промежуточного
     * (bootstrapper) процесса.
     * <p>
     * Перехват трафика таких процессов работает автоматически: окружение (включая
     * HTTP(S)_PROXY) копируется при exec и наследуется по всей цепочке запусков,
     * независимо от переподвязки в дереве процессов.
     */
    public synchronized void startProcessWatcher() {
        if (watcher != null) {
            return;
        }
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "process-watcher");
            t.setDaemon(true);
            return t;
        });
        watcher.scheduleWithFixedDelay(this::scanProcesses, 1, 1, TimeUnit.SECONDS);
        AppLogger.get().info(SRC, "Наблюдение за процессами запущено (детект открытых приложений)");
    }

    private void scanProcesses() {
        try {
            // гарантируем, что запущенные нами процессы помечены как отслеживаемые
            for (Launched l : launched) {
                if (l.isAlive()) {
                    trackedPids.add(l.pid());
                }
            }
            ProcessHandle.allProcesses().forEach(ph -> {
                long pid = ph.pid();
                if (reportedPids.contains(pid) || trackedPids.contains(pid)) {
                    return;
                }
                long ancestor = linkedAncestor(ph);
                if (ancestor > 0) {
                    trackedPids.add(pid);
                    reportedPids.add(pid);
                    long ppid = ph.parent().map(ProcessHandle::pid).orElse(-1L);
                    String cmd = ph.info().commandLine().orElse(ph.info().command().orElse("?"));
                    String link = (ppid == ancestor)
                            ? "запущено процессом pid=" + ancestor
                            : "связано с отслеживаемым процессом pid=" + ancestor + " (через цепочку)";
                    AppLogger.get().info(SRC, "ДЕТЕКТ приложения: pid=" + pid
                            + " ppid=" + ppid + " : " + cmd + " — " + link
                            + " (перехват через унаследованный прокси)");
                    Detected d = new Detected(pid, ancestor, cmd, link);
                    for (Consumer<Detected> listener : processListeners) {
                        try {
                            listener.accept(d);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
        } catch (Exception e) {
            AppLogger.get().debug(SRC, "Ошибка сканирования процессов: " + e.getMessage());
        }
    }

    /** Возвращает PID отслеживаемого предка в цепочке, либо -1, если связи нет. */
    private long linkedAncestor(ProcessHandle ph) {
        ProcessHandle cur = ph;
        for (int depth = 0; depth < 40; depth++) {
            ProcessHandle parent = cur.parent().orElse(null);
            if (parent == null) {
                return -1;
            }
            if (trackedPids.contains(parent.pid())) {
                return parent.pid();
            }
            cur = parent;
        }
        return -1;
    }

    /** Все отслеживаемые процессы, открытые приложениями (без тех, что мы запускали сами). */
    public List<ProcessHandle> getDetectedProcesses() {
        List<ProcessHandle> out = new ArrayList<>();
        Set<Long> launchedPids = new java.util.HashSet<>();
        for (Launched l : launched) {
            launchedPids.add(l.pid());
        }
        for (Long pid : trackedPids) {
            if (launchedPids.contains(pid)) {
                continue;
            }
            ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(out::add);
        }
        return out;
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
        // помечаем как корень отслеживаемого дерева и не сообщаем о нём как о "стороннем"
        trackedPids.add(l.pid());
        reportedPids.add(l.pid());

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
