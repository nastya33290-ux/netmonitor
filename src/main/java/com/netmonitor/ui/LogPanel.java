package com.netmonitor.ui;

import com.netmonitor.util.AppLogger;

import javax.swing.*;
import java.awt.*;

/**
 * Вкладка "Лог / Отладка": вывод сообщений логгера в реальном времени.
 */
public final class LogPanel extends JPanel {

    private static final int MAX_LINES = 5000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox autoscroll = new JCheckBox("Автопрокрутка", true);
    private final JComboBox<AppLogger.Level> levelBox =
            new JComboBox<>(AppLogger.Level.values());

    public LogPanel() {
        super(new BorderLayout(6, 6));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(area), BorderLayout.CENTER);

        levelBox.setSelectedItem(AppLogger.get().getMinLevel());
        levelBox.addActionListener(e ->
                AppLogger.get().setMinLevel((AppLogger.Level) levelBox.getSelectedItem()));

        AppLogger.get().addListener(entry ->
                SwingUtilities.invokeLater(() -> append(entry.toString())));
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("Уровень:"));
        bar.add(levelBox);
        bar.add(autoscroll);
        JButton clear = new JButton("Очистить");
        clear.addActionListener(e -> area.setText(""));
        bar.add(clear);
        return bar;
    }

    private void append(String line) {
        area.append(line + "\n");
        // ограничение размера буфера
        int lines = area.getLineCount();
        if (lines > MAX_LINES) {
            try {
                int end = area.getLineEndOffset(lines - MAX_LINES);
                area.replaceRange("", 0, end);
            } catch (Exception ignored) {
            }
        }
        if (autoscroll.isSelected()) {
            area.setCaretPosition(area.getDocument().getLength());
        }
    }
}
