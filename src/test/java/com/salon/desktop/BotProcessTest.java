package com.salon.desktop;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

class BotProcessTest {

    @Test
    void runsJarWhenLaunchedFromJar() {
        assertThat(BotProcess.command("java", "target/salon-bot.jar"))
                .endsWith("-jar", "target/salon-bot.jar")
                .contains("-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8");
    }

    @Test
    void runsMainClassWhenLaunchedFromIde() {
        String classPath = String.join(File.pathSeparator, "target/classes", "lib/a.jar");
        assertThat(BotProcess.command("java", classPath))
                .endsWith("-cp", classPath, BotProcess.BOT_MAIN);
    }

    @Test
    void recognizesForeignBotButNotDesktopApp() {
        assertThat(BotProcess.isBotCommand("/usr/bin/java -Djava.awt.headless=true -jar target/salon-bot.jar")).isTrue();
        assertThat(BotProcess.isBotCommand("java -cp target/classes com.salon.bot.BotApplication")).isTrue();
        assertThat(BotProcess.isBotCommand("java -Dloader.main=com.salon.desktop.DesktopApp -cp target/salon-bot.jar "
                + "org.springframework.boot.loader.launch.PropertiesLauncher")).isFalse();
        assertThat(BotProcess.isBotCommand("/usr/bin/vim salon-bot.jar")).isFalse();
    }
}
