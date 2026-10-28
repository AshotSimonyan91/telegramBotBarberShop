package com.salon.bot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salon.bot.config.ApiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.time.Duration;
import java.util.List;

/**
 * Фильтры REST API: сначала CORS (чтобы и ответ 401 нёс CORS-заголовки), затем проверка токена.
 * Сервер слушает только 127.0.0.1 ({@code server.address} в application.yml).
 */
@Slf4j
@ConditionalOnApi
@Configuration
public class ApiWebConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> apiCorsFilter(ApiProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOriginPatterns(properties.corsOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT));
        cors.setExposedHeaders(List.of(HttpHeaders.LOCATION, BookingsController.CLIENT_NOTIFIED_HEADER));
        cors.setAllowCredentials(false);
        cors.setMaxAge(Duration.ofHours(1));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration(ApiPaths.BASE + "/**", cors);

        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
        registration.addUrlPatterns(ApiPaths.BASE + "/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<ApiTokenFilter> apiTokenFilter(ApiProperties properties, ObjectMapper objectMapper) {
        if (properties.token().length() < 16) {
            log.warn("API_TOKEN короче 16 символов — используйте длинный случайный токен (openssl rand -hex 32)");
        }
        FilterRegistrationBean<ApiTokenFilter> registration =
                new FilterRegistrationBean<>(new ApiTokenFilter(properties.token(), objectMapper));
        // Все пути, а не только /api: на сервере не должно быть ничего доступного без токена, кроме health.
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
