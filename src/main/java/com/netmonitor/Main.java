package com.netmonitor;

import com.netmonitor.core.ConnectionMonitor;
import com.netmonitor.core.HttpRequestSender;
import com.netmonitor.core.ProxyServer;
import com.netmonitor.core.RuleEngine;
import com.netmonitor.ui.MainFrame;
import com.netmonitor.util.AppLogger;

import javax.swing.*;
import java.awt.HeadlessException;
import java.util.List;

/**
 * Точка входа. По умолчанию запускает GUI.
 * Поддерживает консольный режим: --cli connections | --cli send ...
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("--cli")) {
            runCli(args);
            return;
        }
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            printHelp();
            return;
        }

        // GUI-режим
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> {
            try {
                new MainFrame().setVisible(true);
            } catch (HeadlessException he) {
                System.err.println("Графическая среда недоступна (headless). "
                        + "Используйте консольный режим: java -jar netmonitor.jar --cli connections");
            }
        });
    }

    private static void runCli(String[] args) {
        String cmd = args.length > 1 ? args[1] : "connections";
        switch (cmd.toLowerCase()) {
            case "connections" -> {
                ConnectionMonitor m = new ConnectionMonitor();
                List<com.netmonitor.core.Connection> conns = m.snapshot();
                System.out.printf("%-6s %-22s %-22s %-12s %-8s %s%n",
                        "PROTO", "LOCAL", "REMOTE", "STATE", "PID", "PROCESS");
                for (var c : conns) {
                    System.out.printf("%-6s %-22s %-22s %-12s %-8s %s%n",
                            c.getProtocol(), c.localEndpoint(), c.remoteEndpoint(),
                            c.getState(), c.getPid(), c.getProcess());
                }
                System.out.println("Всего: " + conns.size() + " (ОС=" + m.getOs() + ")");
                m.shutdown();
            }
            case "send" -> {
                String method = args.length > 2 ? args[2] : "GET";
                String url = args.length > 3 ? args[3] : "https://httpbin.org/get";
                HttpRequestSender sender = new HttpRequestSender();
                HttpRequestSender.Result r = sender.send(method, url, "", "", false, 0);
                if (r.isError()) {
                    System.out.println("ОШИБКА: " + r.error());
                } else {
                    System.out.println("HTTP " + r.statusCode() + " " + r.statusInfo()
                            + " (" + r.elapsedMs() + " мс)");
                    System.out.println(r.body());
                }
            }
            case "proxy" -> {
                int port = args.length > 2 ? parsePort(args[2], 8888) : 8888;
                RuleEngine engine = new RuleEngine();
                ProxyServer proxy = new ProxyServer(engine);
                proxy.addListener(cap -> System.out.println(
                        (cap.isBlocked() ? "[BLOCK] " : "[ OK  ] ")
                                + cap.getMethod() + " " + cap.getFullUrl()
                                + " -> " + cap.getStatusCode()));
                try {
                    proxy.start(port);
                    System.out.println("Прокси запущен на 127.0.0.1:" + port
                            + ". Правил: " + engine.getRules().size()
                            + ". Нажмите Ctrl+C для остановки.");
                    Thread.currentThread().join();
                } catch (Exception e) {
                    System.out.println("Не удалось запустить прокси: " + e.getMessage());
                }
            }
            default -> printHelp();
        }
    }

    private static int parsePort(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static void printHelp() {
        System.out.println("""
                NetMonitor — мониторинг соединений и HTTP(S) запросов

                Использование:
                  java -jar netmonitor.jar                      запустить GUI
                  java -jar netmonitor.jar --cli connections    вывести соединения в консоль
                  java -jar netmonitor.jar --cli send GET <url> отправить запрос из консоли
                  java -jar netmonitor.jar --cli proxy [порт]   запустить прокси без GUI (по умолч. 8888)
                  java -jar netmonitor.jar --help               эта справка
                """);
        AppLogger.get().info("Main", "Показана справка");
    }
}
