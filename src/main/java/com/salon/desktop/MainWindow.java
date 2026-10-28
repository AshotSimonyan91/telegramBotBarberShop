package com.salon.desktop;

import com.salon.bot.config.BotProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.Optional;

/**
 * Главное окно: панель управления ботом и вкладки «Записи» / «Лог».
 */
@Slf4j
@Lazy // Swing-компоненты создаются в потоке Swing (см. DesktopApp)
@Component
public class MainWindow extends JFrame {

    private static final Color GREEN = new Color(46, 160, 67);
    private static final Color ORANGE = new Color(210, 140, 20);
    private static final Color GREY = new Color(130, 130, 130);

    private final BotProcess bot;
    private final ConfigurableApplicationContext context;

    private final JLabel botStatus = new JLabel();
    private final JButton startButton = new JButton("Запустить бота");
    private final JButton stopButton = new JButton("Остановить");

    public MainWindow(BotProcess bot, BookingsPanel bookingsPanel, LogPanel logPanel,
                      BotProperties properties, ConfigurableApplicationContext context) {
        super("Салон — @" + properties.username());
        this.bot = bot;
        this.context = context;

        botStatus.setFont(botStatus.getFont().deriveFont(Font.BOLD, 14f));
        startButton.addActionListener(e -> startBot());
        stopButton.addActionListener(e -> stopBot(null));

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8));
        controls.add(botStatus);
        controls.add(startButton);
        controls.add(stopButton);
        controls.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Записи", bookingsPanel);
        tabs.addTab("Лог бота", logPanel);

        getContentPane().add(controls, BorderLayout.NORTH);
        getContentPane().add(tabs, BorderLayout.CENTER);

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                exit();
            }
        });
        setMinimumSize(new Dimension(900, 500));
        setSize(1200, 720);
        setLocationRelativeTo(null);

        bot.onState(state -> SwingUtilities.invokeLater(() -> showState(state)));
        showState(bot.state());
    }

    private void showState(BotProcess.State state) {
        switch (state) {
            case STOPPED -> status("● Бот остановлен", GREY);
            case STARTING -> status("● Бот запускается…", ORANGE);
            case RUNNING -> status("● Бот работает", GREEN);
            case STOPPING -> status("● Бот останавливается…", ORANGE);
        }
        startButton.setEnabled(state == BotProcess.State.STOPPED);
        stopButton.setEnabled(state == BotProcess.State.STARTING || state == BotProcess.State.RUNNING);
    }

    private void status(String text, Color color) {
        botStatus.setText(text);
        botStatus.setForeground(color);
    }

    private void startBot() {
        startButton.setEnabled(false);
        Background.run(bot::findForeignBot, foreign -> {
            if (foreign.isPresent() && !Dialogs.confirm(this, "Похоже, бот уже запущен вне приложения "
                    + "(процесс " + foreign.get().pid() + ", например через start.sh или IDE).\n\n"
                    + "Два бота с одним токеном мешают друг другу (ошибка 409 Conflict).\n"
                    + "Остановите тот процесс. Всё равно запустить?")) {
                showState(bot.state());
                return;
            }
            try {
                bot.start();
            } catch (IOException e) {
                log.error("Не удалось запустить бота", e);
                Dialogs.error(this, "Не удалось запустить бота: " + e.getMessage()
                        + "\n\nПроверьте, что проект собран: mvn package");
                showState(bot.state());
            }
        }, e -> {
            Dialogs.error(this, "Не удалось проверить процессы: " + e.getMessage());
            showState(bot.state());
        });
    }

    /** Останавливает бота в фоне; {@code then} выполняется в потоке Swing после остановки. */
    private void stopBot(Runnable then) {
        stopButton.setEnabled(false);
        Background.run(() -> {
            bot.stop();
            return true;
        }, ok -> Optional.ofNullable(then).ifPresent(Runnable::run),
                e -> Dialogs.error(this, "Не удалось остановить бота: " + e.getMessage()));
    }

    private void exit() {
        if (bot.state() != BotProcess.State.STOPPED) {
            if (!Dialogs.confirm(this, "Бот работает. Остановить бота и закрыть приложение?\n\n"
                    + "Пока приложение закрыто, клиенты не смогут записаться через Telegram.")) {
                return;
            }
            stopBot(this::close);
            return;
        }
        close();
    }

    private void close() {
        dispose();
        context.close();
        System.exit(0);
    }
}
