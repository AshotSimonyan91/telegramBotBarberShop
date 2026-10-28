package com.salon.bot.config;

import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.ExcelBookingRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;

@Configuration
@EnableScheduling
public class AppConfig {

    @Bean
    public Clock clock(BotProperties properties) {
        return Clock.system(properties.zoneId());
    }

    @Bean
    public TelegramClient telegramClient(BotProperties properties) {
        return new OkHttpTelegramClient(properties.token());
    }

    @Bean
    public BookingRepository bookingRepository(BotProperties properties, Clock clock) {
        return new ExcelBookingRepository(properties.excelFile(), clock);
    }
}
