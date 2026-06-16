package com.netmonitor.ui;

import com.netmonitor.core.ConnectionMonitor;
import com.netmonitor.core.ProxyServer;
import com.netmonitor.core.RuleEngine;
import com.netmonitor.core.Settings;
import com.netmonitor.util.AppLogger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Главное окно приложения с вкладками.
 */
public final class MainFrame extends JFrame {

    private final Settings settings;
    private final ConnectionMonitor monitor;
    private final RuleEngine ruleEngine;
    private final ProxyServer proxy;

    public MainFrame() {
        super("NetMonitor — мониторинг соединений и HTTP(S) запросов");

        this.settings = new Settings();
        this.monitor = new ConnectionMonitor();
        this.ruleEngine = new RuleEngine();
        this.proxy = new ProxyServer(ruleEngine);

        try {
            AppLogger.get().setMinLevel(AppLogger.Level.valueOf(settings.get("log.level")));
        } catch (Exception ignored) {
        }

        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setSize(1100, 720);
        setLocationRelativeTo(null);

        JTabbedPane tabs = new JTabbedPane();

        ConnectionsPanel connectionsPanel = new ConnectionsPanel(monitor);
        ProxyPanel proxyPanel = new ProxyPanel(proxy, ruleEngine, settings);
        RequestSenderPanel senderPanel = new RequestSenderPanel(settings);
        RulesPanel rulesPanel = new RulesPanel(ruleEngine);
        SettingsPanel settingsPanel = new SettingsPanel(settings);
        LogPanel logPanel = new LogPanel();

        // связи между вкладками
        proxyPanel.setResendHandler(cap -> {
            senderPanel.loadFrom(cap);
            tabs.setSelectedComponent(senderPanel);
        });
        proxyPanel.setRulesChangedCallback(rulesPanel::reload);

        tabs.addTab("Соединения", connectionsPanel);
        tabs.addTab("Прокси / Перехват", proxyPanel);
        tabs.addTab("Отправить запрос", senderPanel);
        tabs.addTab("Правила блокировки", rulesPanel);
        tabs.addTab("Настройки", settingsPanel);
        tabs.addTab("Лог / Отладка", logPanel);

        add(tabs, BorderLayout.CENTER);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                shutdown();
            }
        });

        // автозапуск согласно настройкам
        if (settings.getBool("monitor.autostart", true)) {
            SwingUtilities.invokeLater(() ->
                    monitor.start(settings.getInt("monitor.refreshSeconds", 3)));
        } else {
            connectionsPanel.refreshOnce();
        }

        if (settings.getBool("proxy.autostart", false)) {
            try {
                proxy.setLogBodies(settings.getBool("proxy.logBodies", true));
                proxy.setMaxBodyLogBytes(settings.getInt("proxy.maxBodyLogBytes", 8192));
                proxy.start(settings.getInt("proxy.port", 8888));
            } catch (Exception ex) {
                AppLogger.get().error("MainFrame", "Автозапуск прокси не удался", ex);
            }
        }

        AppLogger.get().info("MainFrame", "Приложение запущено (ОС=" + monitor.getOs() + ")");
    }

    private void shutdown() {
        int r = JOptionPane.showConfirmDialog(this, "Закрыть NetMonitor?",
                "Выход", JOptionPane.YES_NO_OPTION);
        if (r != JOptionPane.YES_OPTION) {
            return;
        }
        AppLogger.get().info("MainFrame", "Завершение работы...");
        try {
            proxy.stop();
        } catch (Exception ignored) {
        }
        try {
            monitor.shutdown();
        } catch (Exception ignored) {
        }
        dispose();
        System.exit(0);
    }
}
