package com.netmonitor.ui;

import com.netmonitor.core.BlockRule;
import com.netmonitor.core.CapturedRequest;
import com.netmonitor.core.ProxyServer;
import com.netmonitor.core.RuleEngine;
import com.netmonitor.core.Settings;
import com.netmonitor.util.AppLogger;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Вкладка "Прокси / Перехват": запуск прокси, таблица перехваченных запросов,
 * детальный просмотр, быстрые действия (заблокировать хост, переслать в отправщик).
 */
public final class ProxyPanel extends JPanel {

    private final ProxyServer proxy;
    private final RuleEngine ruleEngine;
    private final Settings settings;

    private final CapTableModel model = new CapTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<CapTableModel> sorter = new TableRowSorter<>(model);

    private final JButton startStop = new JButton("Запустить прокси");
    private final JSpinner portSpinner;
    private final JTextField filterField = new JTextField(18);
    private final JTextArea detail = new JTextArea();
    private final JLabel status = new JLabel(" ");

    private Consumer<CapturedRequest> resendHandler;
    private Runnable rulesChangedCallback;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    public ProxyPanel(ProxyServer proxy, RuleEngine ruleEngine, Settings settings) {
        super(new BorderLayout(6, 6));
        this.proxy = proxy;
        this.ruleEngine = ruleEngine;
        this.settings = settings;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        portSpinner = new JSpinner(new SpinnerNumberModel(
                settings.getInt("proxy.port", 8888), 1, 65535, 1));

        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(40);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        table.getColumnModel().getColumn(3).setPreferredWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(340);
        table.getColumnModel().getColumn(5).setPreferredWidth(60);
        table.getColumnModel().getColumn(6).setPreferredWidth(80);
        table.getSelectionModel().addListSelectionListener(e -> showDetail());

        detail.setEditable(false);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(table), new JScrollPane(detail));
        split.setResizeWeight(0.6);

        add(buildToolbar(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        proxy.addListener(cap -> SwingUtilities.invokeLater(() -> {
            model.add(cap);
            updateStatus();
        }));

        filterField.getDocument().addDocumentListener(new DocListener(this::applyFilter));
    }

    public void setResendHandler(Consumer<CapturedRequest> handler) {
        this.resendHandler = handler;
    }

    public void setRulesChangedCallback(Runnable cb) {
        this.rulesChangedCallback = cb;
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        bar.add(new JLabel("Порт:"));
        bar.add(portSpinner);
        startStop.addActionListener(e -> toggleProxy());
        bar.add(startStop);

        JButton clear = new JButton("Очистить");
        clear.addActionListener(e -> {
            model.clear();
            detail.setText("");
            updateStatus();
        });
        bar.add(clear);

        JButton blockHost = new JButton("Блокировать хост");
        blockHost.addActionListener(e -> blockSelectedHost());
        bar.add(blockHost);

        JButton resend = new JButton("В отправщик →");
        resend.addActionListener(e -> resendSelected());
        bar.add(resend);

        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(new JLabel("Фильтр:"));
        bar.add(filterField);
        return bar;
    }

    private void toggleProxy() {
        if (proxy.isRunning()) {
            proxy.stop();
            startStop.setText("Запустить прокси");
            portSpinner.setEnabled(true);
        } else {
            int port = (Integer) portSpinner.getValue();
            try {
                proxy.setLogBodies(settings.getBool("proxy.logBodies", true));
                proxy.setMaxBodyLogBytes(settings.getInt("proxy.maxBodyLogBytes", 8192));
                proxy.start(port);
                settings.set("proxy.port", String.valueOf(port));
                settings.save();
                startStop.setText("Остановить прокси");
                portSpinner.setEnabled(false);
                JOptionPane.showMessageDialog(this,
                        "Прокси запущен на 127.0.0.1:" + port + "\n\n"
                                + "Настройте приложение/ОС на использование HTTP-прокси\n"
                                + "127.0.0.1:" + port + " чтобы видеть и блокировать запросы.",
                        "Прокси запущен", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Не удалось запустить прокси на порту " + port + ":\n" + ex.getMessage(),
                        "Ошибка", JOptionPane.ERROR_MESSAGE);
                AppLogger.get().error("ProxyPanel", "Запуск прокси не удался", ex);
            }
        }
        updateStatus();
    }

    private void blockSelectedHost() {
        CapturedRequest cap = selected();
        if (cap == null) {
            return;
        }
        BlockRule rule = new BlockRule("block " + cap.getHost(), cap.getHost(), "");
        ruleEngine.addRule(rule);
        if (rulesChangedCallback != null) {
            rulesChangedCallback.run();
        }
        JOptionPane.showMessageDialog(this,
                "Добавлено правило блокировки для хоста:\n" + cap.getHost(),
                "Правило добавлено", JOptionPane.INFORMATION_MESSAGE);
    }

    private void resendSelected() {
        CapturedRequest cap = selected();
        if (cap == null) {
            return;
        }
        if (resendHandler != null) {
            resendHandler.accept(cap);
        }
    }

    private CapturedRequest selected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return null;
        }
        return model.get(table.convertRowIndexToModel(row));
    }

    private void showDetail() {
        CapturedRequest cap = selected();
        if (cap == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#").append(cap.getId()).append("  ").append(TIME.format(cap.getTime())).append('\n');
        sb.append(cap.getMethod()).append(' ').append(cap.getFullUrl()).append('\n');
        sb.append("Host: ").append(cap.getHost()).append(':').append(cap.getPort()).append('\n');
        sb.append("Схема: ").append(cap.getScheme()).append('\n');
        if (cap.isBlocked()) {
            sb.append(">>> ЗАБЛОКИРОВАНО правилом: ").append(cap.getBlockedByRule()).append('\n');
        }
        sb.append("\n--- Заголовки запроса ---\n").append(cap.getRequestHeaders()).append('\n');
        if (cap.getRequestBodyPreview() != null && !cap.getRequestBodyPreview().isEmpty()) {
            sb.append("\n--- Тело запроса (превью) ---\n").append(cap.getRequestBodyPreview()).append('\n');
        }
        sb.append("\n--- Ответ ---\n");
        sb.append("Статус: ").append(cap.getStatusCode()).append('\n');
        if (cap.getResponseBytes() >= 0) {
            sb.append("Размер тела: ").append(cap.getResponseBytes()).append(" байт\n");
        }
        if (cap.getResponseHeaders() != null && !cap.getResponseHeaders().isEmpty()) {
            sb.append(cap.getResponseHeaders());
        }
        if (cap.getError() != null && !cap.getError().isEmpty()) {
            sb.append("\nОшибка: ").append(cap.getError());
        }
        detail.setText(sb.toString());
        detail.setCaretPosition(0);
    }

    private void applyFilter() {
        String text = filterField.getText().trim();
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
        }
    }

    private void updateStatus() {
        status.setText((proxy.isRunning()
                ? "Прокси РАБОТАЕТ на 127.0.0.1:" + proxy.getPort()
                : "Прокси остановлен")
                + " | перехвачено: " + model.getRowCount());
    }

    // -------------------- модель таблицы --------------------
    private static final class CapTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Время", "Метод", "Схема", "URL", "Статус", "Блок"};
        private final List<CapturedRequest> data = new ArrayList<>();

        void add(CapturedRequest c) {
            data.add(c);
            int row = data.size() - 1;
            fireTableRowsInserted(row, row);
        }

        void clear() {
            int n = data.size();
            if (n > 0) {
                data.clear();
                fireTableRowsDeleted(0, n - 1);
            }
        }

        CapturedRequest get(int row) {
            return data.get(row);
        }

        @Override
        public int getRowCount() {
            return data.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int column) {
            return cols[column];
        }

        @Override
        public Object getValueAt(int r, int c) {
            CapturedRequest x = data.get(r);
            return switch (c) {
                case 0 -> x.getId();
                case 1 -> TIME.format(x.getTime());
                case 2 -> x.getMethod();
                case 3 -> x.getScheme();
                case 4 -> x.getFullUrl();
                case 5 -> x.getStatusCode() < 0 ? "" : x.getStatusCode();
                case 6 -> x.isBlocked() ? "БЛОК" : "";
                default -> "";
            };
        }
    }

    /** Слушатель изменений документа. */
    private static final class DocListener implements javax.swing.event.DocumentListener {
        private final Runnable action;

        DocListener(Runnable action) {
            this.action = action;
        }

        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }
    }
}
