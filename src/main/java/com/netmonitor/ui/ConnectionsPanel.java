package com.netmonitor.ui;

import com.netmonitor.core.Connection;
import com.netmonitor.core.ConnectionMonitor;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Вкладка "Соединения": таблица активных сетевых соединений ОС с привязкой к процессам,
 * фильтр по имени процесса / тексту, выбор конкретного приложения.
 */
public final class ConnectionsPanel extends JPanel {

    private final ConnectionMonitor monitor;
    private final ConnTableModel model = new ConnTableModel();
    private final JTable table = new JTable(model);
    private final TableRowSorter<ConnTableModel> sorter = new TableRowSorter<>(model);

    private final JComboBox<String> processFilter = new JComboBox<>();
    private final JTextField textFilter = new JTextField(16);
    private final JLabel statusLabel = new JLabel(" ");
    private final JToggleButton autoRefresh = new JToggleButton("Авто-обновление");
    private final JSpinner intervalSpinner = new JSpinner(new SpinnerNumberModel(3, 1, 60, 1));

    public ConnectionsPanel(ConnectionMonitor monitor) {
        super(new BorderLayout(6, 6));
        this.monitor = monitor;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setRowSorter(sorter);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        table.getColumnModel().getColumn(1).setPreferredWidth(170);
        table.getColumnModel().getColumn(2).setPreferredWidth(190);
        table.getColumnModel().getColumn(3).setPreferredWidth(110);
        table.getColumnModel().getColumn(4).setPreferredWidth(70);
        table.getColumnModel().getColumn(5).setPreferredWidth(200);

        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        monitor.addListener(conns -> SwingUtilities.invokeLater(() -> updateData(conns)));

        processFilter.addActionListener(e -> applyFilter());
        textFilter.getDocument().addDocumentListener(new SimpleDocListener(this::applyFilter));

        autoRefresh.addActionListener(e -> toggleAuto());
        intervalSpinner.addChangeListener(e -> {
            if (autoRefresh.isSelected()) {
                monitor.stop();
                monitor.start((Integer) intervalSpinner.getValue());
            }
        });
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton refresh = new JButton("Обновить сейчас");
        refresh.addActionListener(e -> refreshOnce());

        bar.add(refresh);
        bar.add(autoRefresh);
        bar.add(new JLabel("интервал, с:"));
        bar.add(intervalSpinner);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(new JLabel("Процесс:"));
        processFilter.addItem("<все>");
        processFilter.setPreferredSize(new Dimension(200, 26));
        bar.add(processFilter);
        bar.add(new JLabel("Поиск:"));
        bar.add(textFilter);
        return bar;
    }

    private void toggleAuto() {
        if (autoRefresh.isSelected()) {
            monitor.start((Integer) intervalSpinner.getValue());
        } else {
            monitor.stop();
        }
    }

    public void refreshOnce() {
        new SwingWorker<List<Connection>, Void>() {
            @Override
            protected List<Connection> doInBackground() {
                return monitor.snapshot();
            }

            @Override
            protected void done() {
                try {
                    updateData(get());
                } catch (Exception ignored) {
                }
            }
        }.execute();
    }

    private void updateData(List<Connection> conns) {
        // обновляем список процессов в фильтре
        TreeSet<String> processes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Connection c : conns) {
            if (c.getProcess() != null && !c.getProcess().isBlank()) {
                processes.add(c.getProcess());
            }
        }
        Object selected = processFilter.getSelectedItem();
        DefaultComboBoxModel<String> cbModel = new DefaultComboBoxModel<>();
        cbModel.addElement("<все>");
        for (String p : processes) {
            cbModel.addElement(p);
        }
        processFilter.setModel(cbModel);
        if (selected != null) {
            cbModel.setSelectedItem(selected);
        }

        model.setData(conns);
        applyFilter();
        statusLabel.setText("Соединений: " + conns.size()
                + " | ОС: " + monitor.getOs()
                + " | процессов: " + processes.size());
    }

    private void applyFilter() {
        List<RowFilter<Object, Object>> filters = new ArrayList<>();
        Object proc = processFilter.getSelectedItem();
        if (proc != null && !"<все>".equals(proc)) {
            String p = proc.toString();
            filters.add(new RowFilter<>() {
                @Override
                public boolean include(Entry<?, ?> entry) {
                    Object v = entry.getValue(5); // колонка "Процесс"
                    return v != null && v.toString().equalsIgnoreCase(p);
                }
            });
        }
        String text = textFilter.getText().trim();
        if (!text.isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
        }
        sorter.setRowFilter(filters.isEmpty() ? null : RowFilter.andFilter(filters));
    }

    // -------------------- модель таблицы --------------------
    private static final class ConnTableModel extends AbstractTableModel {
        private final String[] cols = {"Proto", "Локальный адрес", "Удалённый адрес", "Состояние", "PID", "Процесс"};
        private List<Connection> data = new ArrayList<>();

        void setData(List<Connection> d) {
            this.data = d;
            fireTableDataChanged();
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
        public Object getValueAt(int rowIndex, int columnIndex) {
            Connection c = data.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> c.getProtocol();
                case 1 -> c.localEndpoint();
                case 2 -> c.remoteEndpoint();
                case 3 -> c.getState();
                case 4 -> c.getPid();
                case 5 -> c.getProcess();
                default -> "";
            };
        }
    }

    /** Упрощённый слушатель изменений документа. */
    private static final class SimpleDocListener implements javax.swing.event.DocumentListener {
        private final Runnable action;

        SimpleDocListener(Runnable action) {
            this.action = action;
        }

        @Override
        public void insertUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void removeUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }

        @Override
        public void changedUpdate(javax.swing.event.DocumentEvent e) {
            action.run();
        }
    }

    static {
        // на некоторых локалях regexFilter чувствителен — фиксируем
        Locale.setDefault(Locale.Category.FORMAT, Locale.ROOT);
    }
}
