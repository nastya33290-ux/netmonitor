package com.netmonitor.ui;

import com.netmonitor.core.CapturedRequest;
import com.netmonitor.core.HttpRequestSender;
import com.netmonitor.core.Settings;

import javax.swing.*;
import java.awt.*;

/**
 * Вкладка "Отправить запрос": ручное формирование и отправка HTTP-запросов
 * (метод, URL, заголовки, тело), опционально через локальный прокси.
 */
public final class RequestSenderPanel extends JPanel {

    private final HttpRequestSender sender = new HttpRequestSender();
    private final Settings settings;

    private final JComboBox<String> methodBox =
            new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
    private final JTextField urlField = new JTextField("https://httpbin.org/get");
    private final JTextArea headersArea = new JTextArea("User-Agent: NetMonitor/1.0\nAccept: */*");
    private final JTextArea bodyArea = new JTextArea();
    private final JTextArea responseArea = new JTextArea();
    private final JCheckBox useProxy = new JCheckBox("Через локальный прокси");
    private final JLabel statusLabel = new JLabel(" ");
    private final JButton sendButton = new JButton("Отправить");

    public RequestSenderPanel(Settings settings) {
        super(new BorderLayout(6, 6));
        this.settings = settings;
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        add(buildTop(), BorderLayout.NORTH);
        add(buildCenter(), BorderLayout.CENTER);
        add(statusLabel, BorderLayout.SOUTH);

        sendButton.addActionListener(e -> doSend());
    }

    private JComponent buildTop() {
        JPanel p = new JPanel(new BorderLayout(6, 6));
        JPanel line = new JPanel(new BorderLayout(6, 6));
        methodBox.setPreferredSize(new Dimension(110, 28));
        line.add(methodBox, BorderLayout.WEST);
        line.add(urlField, BorderLayout.CENTER);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        right.add(useProxy);
        right.add(sendButton);
        line.add(right, BorderLayout.EAST);
        p.add(line, BorderLayout.CENTER);
        return p;
    }

    private JComponent buildCenter() {
        headersArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        responseArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        responseArea.setEditable(false);

        JTabbedPane reqTabs = new JTabbedPane();
        reqTabs.addTab("Заголовки", new JScrollPane(headersArea));
        reqTabs.addTab("Тело", new JScrollPane(bodyArea));

        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.setBorder(BorderFactory.createTitledBorder("Запрос"));
        reqPanel.add(reqTabs, BorderLayout.CENTER);

        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.setBorder(BorderFactory.createTitledBorder("Ответ"));
        respPanel.add(new JScrollPane(responseArea), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reqPanel, respPanel);
        split.setResizeWeight(0.4);
        return split;
    }

    /** Заполнить форму из перехваченного запроса (для пересылки). */
    public void loadFrom(CapturedRequest cap) {
        methodBox.setSelectedItem("CONNECT".equals(cap.getMethod()) ? "GET" : cap.getMethod());
        urlField.setText(cap.getFullUrl());
        if (cap.getRequestHeaders() != null) {
            headersArea.setText(cap.getRequestHeaders());
        }
        if (cap.getRequestBodyPreview() != null) {
            bodyArea.setText(cap.getRequestBodyPreview());
        }
    }

    private void doSend() {
        final String method = (String) methodBox.getSelectedItem();
        final String url = urlField.getText();
        final String headers = headersArea.getText();
        final String body = bodyArea.getText();
        final boolean proxy = useProxy.isSelected();
        final int proxyPort = settings.getInt("proxy.port", 8888);

        sendButton.setEnabled(false);
        statusLabel.setText("Отправка...");
        responseArea.setText("");

        new SwingWorker<HttpRequestSender.Result, Void>() {
            @Override
            protected HttpRequestSender.Result doInBackground() {
                return sender.send(method, url, headers, body, proxy, proxyPort);
            }

            @Override
            protected void done() {
                try {
                    HttpRequestSender.Result r = get();
                    if (r.isError()) {
                        statusLabel.setText("Ошибка: " + r.error() + " (" + r.elapsedMs() + " мс)");
                        responseArea.setText("ОШИБКА: " + r.error());
                    } else {
                        statusLabel.setText("HTTP " + r.statusCode() + " " + r.statusInfo()
                                + " | " + r.elapsedMs() + " мс");
                        StringBuilder sb = new StringBuilder();
                        sb.append("HTTP ").append(r.statusCode()).append(' ')
                                .append(r.statusInfo()).append("\n\n");
                        sb.append("--- Заголовки ответа ---\n").append(r.headers()).append('\n');
                        sb.append("--- Тело ответа ---\n").append(r.body());
                        responseArea.setText(sb.toString());
                        responseArea.setCaretPosition(0);
                    }
                } catch (Exception ex) {
                    statusLabel.setText("Ошибка: " + ex.getMessage());
                } finally {
                    sendButton.setEnabled(true);
                }
            }
        }.execute();
    }
}
