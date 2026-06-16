package com.netmonitor.ui;

import com.netmonitor.core.Settings;
import com.netmonitor.util.AppLogger;

import javax.swing.*;
import java.awt.*;

/**
 * Вкладка "Настройки": параметры прокси, мониторинга и логирования.
 */
public final class SettingsPanel extends JPanel {

    private final Settings settings;

    private final JSpinner proxyPort;
    private final JCheckBox proxyAutostart;
    private final JCheckBox logBodies;
    private final JSpinner maxBodyBytes;
    private final JSpinner refreshSeconds;
    private final JCheckBox monitorAutostart;
    private final JComboBox<AppLogger.Level> logLevel =
            new JComboBox<>(AppLogger.Level.values());

    public SettingsPanel(Settings settings) {
        super(new BorderLayout(6, 6));
        this.settings = settings;
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        proxyPort = new JSpinner(new SpinnerNumberModel(settings.getInt("proxy.port", 8888), 1, 65535, 1));
        proxyAutostart = new JCheckBox("Запускать прокси при старте", settings.getBool("proxy.autostart", false));
        logBodies = new JCheckBox("Логировать тела запросов", settings.getBool("proxy.logBodies", true));
        maxBodyBytes = new JSpinner(new SpinnerNumberModel(
                settings.getInt("proxy.maxBodyLogBytes", 8192), 0, 1_048_576, 256));
        refreshSeconds = new JSpinner(new SpinnerNumberModel(
                settings.getInt("monitor.refreshSeconds", 3), 1, 60, 1));
        monitorAutostart = new JCheckBox("Запускать мониторинг при старте",
                settings.getBool("monitor.autostart", true));
        try {
            logLevel.setSelectedItem(AppLogger.Level.valueOf(settings.get("log.level")));
        } catch (Exception ignored) {
            logLevel.setSelectedItem(AppLogger.Level.DEBUG);
        }

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 10));
        form.add(sectionLabel("— Прокси —"));
        form.add(new JLabel());
        form.add(new JLabel("Порт прокси:"));
        form.add(proxyPort);
        form.add(new JLabel());
        form.add(proxyAutostart);
        form.add(new JLabel());
        form.add(logBodies);
        form.add(new JLabel("Макс. байт тела в логе:"));
        form.add(maxBodyBytes);

        form.add(sectionLabel("— Мониторинг —"));
        form.add(new JLabel());
        form.add(new JLabel("Интервал обновления, с:"));
        form.add(refreshSeconds);
        form.add(new JLabel());
        form.add(monitorAutostart);

        form.add(sectionLabel("— Логирование —"));
        form.add(new JLabel());
        form.add(new JLabel("Минимальный уровень:"));
        form.add(logLevel);

        JButton save = new JButton("Сохранить настройки");
        save.addActionListener(e -> save());

        JPanel south = new JPanel(new FlowLayout(FlowLayout.LEFT));
        south.add(save);
        south.add(new JLabel("Файл: " + settings.getFile()));

        add(form, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return l;
    }

    private void save() {
        settings.set("proxy.port", String.valueOf(proxyPort.getValue()));
        settings.set("proxy.autostart", String.valueOf(proxyAutostart.isSelected()));
        settings.set("proxy.logBodies", String.valueOf(logBodies.isSelected()));
        settings.set("proxy.maxBodyLogBytes", String.valueOf(maxBodyBytes.getValue()));
        settings.set("monitor.refreshSeconds", String.valueOf(refreshSeconds.getValue()));
        settings.set("monitor.autostart", String.valueOf(monitorAutostart.isSelected()));
        settings.set("log.level", String.valueOf(logLevel.getSelectedItem()));
        settings.save();
        AppLogger.get().setMinLevel((AppLogger.Level) logLevel.getSelectedItem());
        JOptionPane.showMessageDialog(this,
                "Настройки сохранены.\nНекоторые параметры применяются при следующем запуске прокси/мониторинга.",
                "Сохранено", JOptionPane.INFORMATION_MESSAGE);
    }
}
