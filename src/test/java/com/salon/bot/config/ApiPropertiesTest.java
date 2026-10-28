package com.salon.bot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salon.bot.api.ApiWebConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ApiProperties и условное создание фильтров API (без полного запуска приложения и бота). */
class ApiPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(ApiProperties.class)
    static class PropsConfig {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(PropsConfig.class, ApiWebConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultsEnabledWhenNotSpecified() {
        ApiProperties p = new ApiProperties(null, "secret", null);
        assertThat(p.enabled()).isTrue();
        assertThat(p.corsOrigins()).isEmpty();
    }

    @Test
    void enabledWithBlankTokenFailsWithClearMessage() {
        for (String token : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> new ApiProperties(true, token, List.of()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("API_TOKEN")
                    .hasMessageContaining("API_ENABLED=false");
        }
    }

    @Test
    void disabledAllowsBlankToken() {
        ApiProperties p = new ApiProperties(false, null, null);
        assertThat(p.enabled()).isFalse();
        assertThat(p.token()).isEmpty();
    }

    @Test
    void tokenIsTrimmedAndNeverPrinted() {
        ApiProperties p = new ApiProperties(true, "  topsecret-value  ", List.of("http://localhost:5173"));
        assertThat(p.token()).isEqualTo("topsecret-value");
        assertThat(p.toString()).doesNotContain("topsecret").contains("***");
    }

    @Test
    void corsOriginsAreCopied() {
        List<String> origins = new java.util.ArrayList<>(List.of("a"));
        ApiProperties p = new ApiProperties(true, "t", origins);
        origins.add("b");
        assertThat(p.corsOrigins()).containsExactly("a");
    }

    @Test
    void contextFailsWithoutTokenWhenApiEnabled() {
        runner.withPropertyValues("api.enabled=true", "api.token=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessages(ctx.getStartupFailure())).contains("API_TOKEN не задан");
        });
        // api.enabled не задан — по умолчанию включено
        runner.withPropertyValues("api.token=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessages(ctx.getStartupFailure())).contains("API_TOKEN не задан");
        });
    }

    @Test
    void contextFailsWithBlankTokenWhenApiEnabledByEmptyValue() {
        // API_ENABLED= (пусто) приходит в Boolean как пустая строка -> null -> true
        runner.withPropertyValues("api.enabled=", "api.token=   ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessages(ctx.getStartupFailure())).contains("API_TOKEN не задан");
        });
    }

    @Test
    void contextStartsWithoutApiBeansWhenDisabled() {
        runner.withPropertyValues("api.enabled=false", "api.token=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean("apiTokenFilter");
            assertThat(ctx).doesNotHaveBean("apiCorsFilter");
        });
        runner.withPropertyValues("api.enabled=FALSE", "api.token=").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean("apiTokenFilter");
        });
    }

    @Test
    void contextCreatesFiltersWhenEnabled() {
        runner.withPropertyValues("api.enabled=true", "api.token=abc").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasBean("apiTokenFilter").hasBean("apiCorsFilter");
        });
    }

    private static String rootMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null; c = c.getCause()) {
            sb.append(c.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
