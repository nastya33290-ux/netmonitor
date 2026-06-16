package com.netmonitor.ui;

import com.netmonitor.core.BlockRule;
import com.netmonitor.core.RuleEngine;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Вкладка "Правила": управление правилами блокировки запросов.
 */
public final class RulesPanel extends JPanel {

    private final RuleEngine engine;
    private final RuleTableModel model = new RuleTableModel();
    private final JTable table = new JTable(model);

    public RulesPanel(RuleEngine engine) {
        super(new BorderLayout(6, 6));
        this.engine = engine;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.getColumnModel().getColumn(0).setPreferredWidth(50);  // вкл
        table.getColumnModel().getColumn(1).setPreferredWidth(140); // имя
        table.getColumnModel().getColumn(2).setPreferredWidth(180); // host
        table.getColumnModel().getColumn(3).setPreferredWidth(180); // url
        table.getColumnModel().getColumn(4).setPreferredWidth(70);  // метод
        table.getColumnModel().getColumn(5).setPreferredWidth(90);  // направление
        table.getColumnModel().getColumn(6).setPreferredWidth(60);  // regex

        add(buildToolbar(), BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(new JLabel("Совет: host-паттерн поддерживает '*' (напр. *.ads.com). "
                + "URL — подстрока или regex. Изменения применяются сразу."), BorderLayout.SOUTH);

        reload();
    }

    public void reload() {
        model.setData(engine.getRules());
    }

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));

        JButton add = new JButton("Добавить");
        add.addActionListener(e -> editRule(null));
        bar.add(add);

        JButton edit = new JButton("Изменить");
        edit.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                editRule(model.get(row));
            }
        });
        bar.add(edit);

        JButton del = new JButton("Удалить");
        del.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) {
                engine.removeRule(model.get(row));
                reload();
            }
        });
        bar.add(del);

        JButton reload = new JButton("Перезагрузить");
        reload.addActionListener(e -> {
            engine.load();
            reload();
        });
        bar.add(reload);
        return bar;
    }

    private void editRule(BlockRule existing) {
        BlockRule rule = existing != null ? existing : new BlockRule("new rule", "", "");

        JTextField name = new JTextField(rule.getName(), 20);
        JTextField host = new JTextField(rule.getHostPattern(), 20);
        JTextField url = new JTextField(rule.getUrlPattern(), 20);
        JComboBox<String> method = new JComboBox<>(
                new String[]{"ANY", "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "CONNECT"});
        method.setSelectedItem(rule.getMethod());
        JComboBox<BlockRule.Direction> dir = new JComboBox<>(BlockRule.Direction.values());
        dir.setSelectedItem(rule.getDirection());
        JCheckBox regex = new JCheckBox("Использовать regex", rule.isUseRegex());
        JCheckBox enabled = new JCheckBox("Включено", rule.isEnabled());

        JPanel form = new JPanel(new GridLayout(0, 2, 6, 6));
        form.add(new JLabel("Имя:"));
        form.add(name);
        form.add(new JLabel("Host-паттерн:"));
        form.add(host);
        form.add(new JLabel("URL-паттерн:"));
        form.add(url);
        form.add(new JLabel("Метод:"));
        form.add(method);
        form.add(new JLabel("Направление:"));
        form.add(dir);
        form.add(regex);
        form.add(enabled);

        int res = JOptionPane.showConfirmDialog(this, form,
                existing != null ? "Изменить правило" : "Новое правило",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res != JOptionPane.OK_OPTION) {
            return;
        }

        rule.setName(name.getText().trim());
        rule.setHostPattern(host.getText().trim());
        rule.setUrlPattern(url.getText().trim());
        rule.setMethod((String) method.getSelectedItem());
        rule.setDirection((BlockRule.Direction) dir.getSelectedItem());
        rule.setUseRegex(regex.isSelected());
        rule.setEnabled(enabled.isSelected());

        if (existing == null) {
            engine.addRule(rule);
        } else {
            engine.save();
        }
        reload();
    }

    // -------------------- модель таблицы --------------------
    private final class RuleTableModel extends AbstractTableModel {
        private final String[] cols = {"Вкл", "Имя", "Host", "URL", "Метод", "Направление", "Regex"};
        private List<BlockRule> data = new ArrayList<>();

        void setData(List<BlockRule> d) {
            this.data = d;
            fireTableDataChanged();
        }

        BlockRule get(int row) {
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
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? Boolean.class : String.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex == 0 && aValue instanceof Boolean b) {
                data.get(rowIndex).setEnabled(b);
                engine.save();
            }
        }

        @Override
        public Object getValueAt(int r, int c) {
            BlockRule x = data.get(r);
            return switch (c) {
                case 0 -> x.isEnabled();
                case 1 -> x.getName();
                case 2 -> x.getHostPattern();
                case 3 -> x.getUrlPattern();
                case 4 -> x.getMethod();
                case 5 -> x.getDirection();
                case 6 -> x.isUseRegex() ? "да" : "";
                default -> "";
            };
        }
    }
}
