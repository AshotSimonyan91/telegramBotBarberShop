package com.salon.desktop;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Вкладка «Лог»: вывод работающего бота в реальном времени.
 */
@Lazy // Swing-компоненты создаются в потоке Swing (см. DesktopApp)
@Component
public class LogPanel extends JPanel {

    private static final int MAX_LINES = 5000;

    private final JTextArea area = new JTextArea();
    private final JCheckBox autoScroll = new JCheckBox("Автопрокрутка", true);

    public LogPanel(BotProcess bot) {
        super(new BorderLayout());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        JButton clear = new JButton("Очистить");
        clear.addActionListener(e -> area.setText(""));
        JButton openLogs = new JButton("Папка логов");
        openLogs.setToolTipText("Полные логи бота: logs/bot.log");
        openLogs.addActionListener(e -> openLogsFolder());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bar.add(autoScroll);
        bar.add(clear);
        bar.add(openLogs);

        JScrollPane scroll = new JScrollPane(area);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(bar, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        bot.onLog(line -> SwingUtilities.invokeLater(() -> append(line)));
    }

    private void append(String line) {
        area.append(line + "\n");
        int excess = area.getLineCount() - MAX_LINES;
        if (excess > 0) {
            try {
                area.replaceRange("", 0, area.getLineEndOffset(excess - 1));
            } catch (BadLocationException ignored) {
                // текст уже изменился — обрежем при следующей строке
            }
        }
        if (autoScroll.isSelected()) {
            area.setCaretPosition(area.getDocument().getLength());
        }
    }

    private void openLogsFolder() {
        try {
            Path logs = Path.of(System.getProperty("LOG_DIR", System.getenv().getOrDefault("LOG_DIR", "logs"))).toAbsolutePath();
            Files.createDirectories(logs);
            Desktop.getDesktop().open(logs.toFile());
        } catch (IOException | UnsupportedOperationException e) {
            Dialogs.error(this, "Не удалось открыть папку логов: " + e.getMessage());
        }
    }
}
