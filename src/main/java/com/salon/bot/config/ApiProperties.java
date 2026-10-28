package com.salon.bot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Настройки локального REST API (см. application.yml, секция {@code api}).
 * Порт и адрес (только 127.0.0.1) задаются через {@code server.*}.
 *
 * @param enabled     API_ENABLED; не задано или пусто — true
 * @param token       API_TOKEN — обязателен, если API включён
 * @param corsOrigins разрешённые Origin (шаблоны Spring: допускается «*» внутри значения)
 */
@ConfigurationProperties(prefix = "api")
public record ApiProperties(
        Boolean enabled,
        String token,
        List<String> corsOrigins
) {

    public ApiProperties {
        enabled = enabled == null || enabled;
        if (enabled && (token == null || token.isBlank())) {
            throw new IllegalStateException(
                    "API_TOKEN не задан. Укажите его в файле .env (например, результат «openssl rand -hex 32») "
                            + "или отключите API: API_ENABLED=false.");
        }
        token = token == null ? "" : token.trim();
        corsOrigins = corsOrigins == null ? List.of() : List.copyOf(corsOrigins);
    }

    @Override
    public String toString() {
        return "ApiProperties[enabled=" + enabled + ", token=***, corsOrigins=" + corsOrigins + "]";
    }
}
