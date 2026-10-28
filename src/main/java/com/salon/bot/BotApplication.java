package com.salon.bot;

import com.salon.bot.config.ApiProperties;
import com.salon.bot.config.BotProperties;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({BotProperties.class, ApiProperties.class})
public class BotApplication {

    public static void main(String[] args) {
        loadDotenv();
        SpringApplication application = new SpringApplication(BotApplication.class);
        if (!isApiEnabled()) {
            // Без REST API веб-сервер не нужен: бот работает как раньше (только long polling).
            application.setWebApplicationType(WebApplicationType.NONE);
        }
        application.run(args);
    }

    /**
     * Переносит значения из .env в System properties, чтобы Spring мог их подставить.
     * Реальные переменные окружения имеют приоритет над .env.
     */
    public static void loadDotenv() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();
        dotenv.entries(Dotenv.Filter.DECLARED_IN_ENV_FILE).forEach(entry -> {
            if (System.getenv(entry.getKey()) == null && !entry.getValue().isBlank()) {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        });
    }

    /** API_ENABLED из окружения или .env; пусто — включено. */
    static boolean isApiEnabled() {
        String value = System.getenv("API_ENABLED");
        if (value == null || value.isBlank()) {
            value = System.getProperty("API_ENABLED");
        }
        return value == null || value.isBlank() || !value.trim().equalsIgnoreCase("false");
    }
}
