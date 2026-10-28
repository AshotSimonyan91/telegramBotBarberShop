package com.salon.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BotApplicationTest {

    @AfterEach
    void cleanup() {
        System.clearProperty("API_ENABLED");
    }

    @Test
    void apiEnabledByDefaultAndWhenBlank() {
        assumeTrue(System.getenv("API_ENABLED") == null);
        assertThat(BotApplication.isApiEnabled()).isTrue();
        System.setProperty("API_ENABLED", "");
        assertThat(BotApplication.isApiEnabled()).isTrue();
        System.setProperty("API_ENABLED", "   ");
        assertThat(BotApplication.isApiEnabled()).isTrue();
        System.setProperty("API_ENABLED", "true");
        assertThat(BotApplication.isApiEnabled()).isTrue();
    }

    @Test
    void apiDisabledOnlyByFalseInAnyCase() {
        assumeTrue(System.getenv("API_ENABLED") == null);
        for (String v : new String[]{"false", "FALSE", " False "}) {
            System.setProperty("API_ENABLED", v);
            assertThat(BotApplication.isApiEnabled()).as(v).isFalse();
        }
        System.setProperty("API_ENABLED", "0");
        assertThat(BotApplication.isApiEnabled()).as("0 не отключает").isTrue();
    }

    /** Тесты не должны подхватывать .env (боевой токен): loadDotenv вызывается только из main и DesktopApp. */
    @Test
    void testJvmDidNotLoadDotenv() {
        assertThat(System.getProperty("BOT_TOKEN")).isNull();
        assertThat(System.getProperty("API_TOKEN")).isNull();
        assertThat(System.getProperty("EXCEL_PATH")).isNull();
    }
}
