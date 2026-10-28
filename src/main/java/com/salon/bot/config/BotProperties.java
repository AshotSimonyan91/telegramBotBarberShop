package com.salon.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;

/**
 * Настройки бота. Значения приходят из .env / переменных окружения (см. application.yml).
 */
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        String token,
        String username,
        String excelPath,
        Long adminChatId,
        String timezone
) {

    private static final String DEFAULT_EXCEL_FILE = "salon_bookings.xlsx";

    public BotProperties {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "BOT_TOKEN не задан. Укажите его в файле .env или в переменной окружения.");
        }
    }

    public ZoneId zoneId() {
        return ZoneId.of(timezone);
    }

    /**
     * Путь к Excel-файлу: EXCEL_PATH или salon_bookings.xlsx на рабочем столе пользователя.
     */
    public Path excelFile() {
        if (excelPath != null && !excelPath.isBlank()) {
            return Path.of(excelPath);
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path desktop = home.resolve("Desktop");
        if (!Files.isDirectory(desktop)) {
            Path oneDriveDesktop = home.resolve("OneDrive").resolve("Desktop");
            if (Files.isDirectory(oneDriveDesktop)) {
                desktop = oneDriveDesktop;
            }
        }
        return desktop.resolve(DEFAULT_EXCEL_FILE);
    }
}
