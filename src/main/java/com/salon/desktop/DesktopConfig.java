package com.salon.desktop;

import com.salon.bot.bot.Messenger;
import com.salon.bot.config.BotProperties;
import com.salon.bot.service.BookingService;
import com.salon.bot.service.SlotCalculator;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.ExcelBookingRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;

/**
 * Контекст настольного приложения: те же настройки и хранилище, что у бота, но без long polling.
 * TelegramClient нужен только для отправки сообщений клиентам (не конфликтует с работающим ботом).
 */
@Configuration
@EnableConfigurationProperties(BotProperties.class)
@Import({SlotCalculator.class, BookingService.class, Messenger.class,
        BotProcess.class, MainWindow.class, BookingsPanel.class, LogPanel.class})
public class DesktopConfig {

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
        BookingRepository repository = new ExcelBookingRepository(properties.excelFile(), clock);
        repository.init();
        return repository;
    }
}
