package com.salon.desktop;

import com.formdev.flatlaf.FlatLightLaf;
import com.salon.bot.BotApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/**
 * Настольное приложение: запуск/остановка бота, просмотр, добавление и отмена записей.
 * Запуск: {@code ./desktop.sh} / {@code desktop.bat}.
 */
@Slf4j
public class DesktopApp {

    public static void main(String[] args) {
        System.setProperty("LOG_NAME", "desktop");
        System.setProperty("apple.awt.application.name", "Салон");
        BotApplication.loadDotenv();
        FlatLightLaf.setup();

        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(DesktopConfig.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(Banner.Mode.OFF)
                    .headless(false)
                    .logStartupInfo(false)
                    .run(args);
        } catch (Exception e) {
            log.error("Не удалось запустить приложение", e);
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            JOptionPane.showMessageDialog(null,
                    "Не удалось запустить приложение:\n" + root.getMessage(),
                    "Салон", JOptionPane.ERROR_MESSAGE);
            System.exit(1);
            return;
        }

        SwingUtilities.invokeLater(() -> context.getBean(MainWindow.class).setVisible(true));
    }
}
