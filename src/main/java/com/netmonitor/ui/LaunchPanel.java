package com.netmonitor.ui;

import com.netmonitor.core.AppLauncher;
import com.netmonitor.core.ProxyServer;
import com.netmonitor.core.Settings;
import com.netmonitor.util.AppLogger;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Вкладка "Запуск через прокси": запускает выбранное приложение с трафиком,
 * направленным через встроенный прокси. Прокси при необходимости поднимается
 * автоматически, после чего запросы приложения видны во вкладке "Прокси / Перехват".
 */
public final class LaunchPanel extends JPanel {

    private final ProxyServer proxy;
    private final Settings settings;
    private final AppLauncher launcher = new AppLauncher();

    private final JTextField execField = new JTextField(34);
    private final JTextField argsField = new JTextField(34);
    private final JCheckBox setEnv = new JCheckBox("Проставить переменные окружения прокси (HTTP(S)_PROXY)", true);
    private final JComboBox<String> presets = new JComboBox<>(new String[]{
            "— шаблоны —",
            "Chrome / Chromium (флаг --proxy-server)",
            "curl (тест через прокси)",
            "Очистить аргументы"
    });
    private final JTextArea output = new JTextArea();
    private final ProcTableModel procModel = new ProcTableModel();
    private final JTable procTable = new JTable(procModel);
    private final JLabel status = new JLabel(" ");

    private Runnable openProxyTab;

    public LaunchPanel(ProxyServer proxy, Settings settings) {
        super(new BorderLayout(6, 6));
        this.proxy = proxy;
        this.settings = settings;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildForm(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        presets.addActionListener(e -> applyPreset());

        // детект дочерних процессов: лог + обновление таблицы
        launcher.startChildWatcher();
        launcher.addChildListener((parent, child) -> SwingUtilities.invokeLater(() -> {
            String cmd = child.info().commandLine().orElse(child.info().command().orElse("?"));
            appendOutput("[ДЕТЕКТ] приложение pid=" + parent.pid()
                    + " запустило дочерний процесс pid=" + child.pid()
                    + " : " + cmd + "  → перехват включён (наследует прокси)");
            refreshProcesses();
        }));

        // периодически обновляем таблицу процессов
        Timer timer = new Timer(2000, e -> refreshProcesses());
        timer.start();
        updateStatus();
    }

    public void setOpenProxyTab(Runnable r) {
        this.openProxyTab = r;
    }

    private JComponent buildForm() {
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(3, 3, 3, 3);
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;

        g.gridx = 0; g.gridy = 0;
        form.add(new JLabel("Приложение:"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(execField, g);
        g.gridx = 2; g.weightx = 0;
        JButton browse = new JButton("Обзор…");
        browse.addActionListener(e -> browse());
        form.add(browse, g);

        g.gridx = 0; g.gridy = 1; g.weightx = 0;
        form.add(new JLabel("Аргументы:"), g);
        g.gridx = 1; g.weightx = 1;
        form.add(argsField, g);
        g.gridx = 2; g.weightx = 0;
        form.add(presets, g);

        g.gridx = 1; g.gridy = 2;
        form.add(setEnv, g);

        g.gridx = 1; g.gridy = 3;
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton launch = new JButton("Запустить через прокси");
        launch.addActionListener(e -> doLaunch());
        buttons.add(launch);
        JButton toProxy = new JButton("Открыть «Прокси / Перехват»");
        toProxy.addActionListener(e -> {
            if (openProxyTab != null) {
                openProxyTab.run();
            }
        });
        buttons.add(toProxy);
        form.add(buttons, g);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(form, BorderLayout.CENTER);
        wrap.add(new JLabel("<html><i>Прокси поднимется автоматически. Трафик запущенного приложения "
                + "появится во вкладке «Прокси / Перехват», где его можно смотреть и блокировать. "
                + "Для HTTPS виден хост (содержимое зашифровано без MITM-сертификата).</i></html>"),
                BorderLayout.SOUTH);
        return wrap;
    }

    private JComponent buildCenter() {
        output.setEditable(false);
        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JPanel procPanel = new JPanel(new BorderLayout(4, 4));
        procPanel.setBorder(BorderFactory.createTitledBorder("Запущенные процессы"));
        procTable.setFillsViewportHeight(true);
        procPanel.add(new JScrollPane(procTable), BorderLayout.CENTER);
        JPanel procBtns = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        JButton stop = new JButton("Остановить выбранный");
        stop.addActionListener(e -> stopSelected());
        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(e -> refreshProcesses());
        procBtns.add(stop);
        procBtns.add(refresh);
        procPanel.add(procBtns, BorderLayout.SOUTH);

        JPanel outPanel = new JPanel(new BorderLayout());
        outPanel.setBorder(BorderFactory.createTitledBorder("Вывод процесса"));
        outPanel.add(new JScrollPane(output), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, procPanel, outPanel);
        split.setResizeWeight(0.4);
        return split;
    }

    private void applyPreset() {
        int port = settings.getInt("proxy.port", 8888);
        String sel = (String) presets.getSelectedItem();
        if (sel == null) {
            return;
        }
        if (sel.startsWith("Chrome")) {
            argsField.setText("--proxy-server=http://127.0.0.1:" + port
                    + " --proxy-bypass-list=\"<-loopback>\""
                    + " --user-data-dir=/tmp/netmonitor-chrome --no-first-run");
            setEnv.setSelected(true);
        } else if (sel.startsWith("curl")) {
            if (execField.getText().isBlank()) {
                execField.setText("curl");
            }
            argsField.setText("-v https://httpbin.org/get");
            setEnv.setSelected(true);
        } else if (sel.startsWith("Очистить")) {
            argsField.setText("");
        }
        presets.setSelectedIndex(0);
    }

    private void browse() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = fc.getSelectedFile();
            execField.setText(f.getAbsolutePath());
        }
    }

    private void ensureProxyRunning() {
        if (!proxy.isRunning()) {
            int port = settings.getInt("proxy.port", 8888);
            proxy.setLogBodies(settings.getBool("proxy.logBodies", true));
            proxy.setMaxBodyLogBytes(settings.getInt("proxy.maxBodyLogBytes", 8192));
            try {
                proxy.start(port);
                appendOutput("[прокси запущен на 127.0.0.1:" + port + "]");
            } catch (Exception e) {
                appendOutput("[не удалось запустить прокси: " + e.getMessage() + "]");
                AppLogger.get().error("LaunchPanel", "Запуск прокси не удался", e);
            }
        }
    }

    private void doLaunch() {
        String exec = execField.getText().trim();
        if (exec.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Укажите путь к приложению.",
                    "Нужен путь", JOptionPane.WARNING_MESSAGE);
            return;
        }
        ensureProxyRunning();
        int port = settings.getInt("proxy.port", 8888);
        try {
            launcher.launch(exec, argsField.getText(), port, setEnv.isSelected(),
                    line -> SwingUtilities.invokeLater(() -> appendOutput(line)));
            appendOutput("=== Запущено: " + exec + " " + argsField.getText() + " ===");
            refreshProcesses();
            updateStatus();
        } catch (Exception ex) {
            appendOutput("[ОШИБКА запуска: " + ex.getMessage() + "]");
            JOptionPane.showMessageDialog(this,
                    "Не удалось запустить приложение:\n" + ex.getMessage(),
                    "Ошибка запуска", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void stopSelected() {
        int row = procTable.getSelectedRow();
        if (row < 0) {
            return;
        }
        ProcRow r = procModel.get(row);
        if (r == null) {
            return;
        }
        if (r.launched != null) {
            r.launched.forceStop();
            appendOutput("[остановлен процесс pid=" + r.pid + "]");
        } else if (r.handle != null) {
            r.handle.destroyForcibly();
            appendOutput("[остановлен дочерний процесс pid=" + r.pid + "]");
        }
        refreshProcesses();
    }

    private void refreshProcesses() {
        List<ProcRow> rows = new java.util.ArrayList<>();
        for (AppLauncher.Launched l : launcher.getLaunched()) {
            rows.add(new ProcRow(l.pid(), -1, "родитель", l.getCommand(),
                    l.isAlive() ? "работает" : "завершён", l, null));
            for (ProcessHandle child : launcher.descendants(l)) {
                long ppid = child.parent().map(ProcessHandle::pid).orElse(l.pid());
                String cmd = child.info().commandLine().orElse(child.info().command().orElse("?"));
                rows.add(new ProcRow(child.pid(), ppid, "дочерний", cmd,
                        child.isAlive() ? "работает" : "завершён", null, child));
            }
        }
        procModel.setData(rows);
        updateStatus();
    }

    private void appendOutput(String line) {
        output.append(line + "\n");
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void updateStatus() {
        int parents = launcher.getLaunched().size();
        int children = 0;
        for (AppLauncher.Launched l : launcher.getLaunched()) {
            children += launcher.descendants(l).size();
        }
        status.setText((proxy.isRunning()
                ? "Прокси РАБОТАЕТ на 127.0.0.1:" + proxy.getPort()
                : "Прокси остановлен — поднимется при запуске приложения")
                + " | процессов: " + parents + " (+ дочерних: " + children + ", перехват наследуется)");
    }

    public void shutdown() {
        launcher.stopAll();
    }

    /** Строка таблицы процессов: запущенный родитель или его потомок. */
    private record ProcRow(long pid, long ppid, String type, String command, String status,
                           AppLauncher.Launched launched, ProcessHandle handle) {
    }

    // -------------------- модель таблицы процессов --------------------
    private static final class ProcTableModel extends AbstractTableModel {
        private final String[] cols = {"PID", "PPID", "Тип", "Команда", "Статус"};
        private List<ProcRow> data = new java.util.ArrayList<>();

        void setData(List<ProcRow> d) {
            this.data = d;
            fireTableDataChanged();
        }

        ProcRow get(int row) {
            return row >= 0 && row < data.size() ? data.get(row) : null;
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
            ProcRow row = data.get(r);
            return switch (c) {
                case 0 -> row.pid();
                case 1 -> row.ppid() < 0 ? "" : row.ppid();
                case 2 -> row.type();
                case 3 -> row.command();
                case 4 -> row.status();
                default -> "";
            };
        }
    }
}
