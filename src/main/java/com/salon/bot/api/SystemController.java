package com.salon.bot.api;

import com.salon.bot.api.dto.HealthResponse;
import com.salon.bot.api.dto.StatusResponse;
import com.salon.bot.config.BotProperties;
import com.salon.bot.service.BookingService;
import com.salon.bot.storage.StorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Проверка доступности (без токена) и состояние бота/хранилища. */
@Slf4j
@ConditionalOnApi
@RestController
@RequestMapping(path = ApiPaths.BASE, produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class SystemController {

    private final Clock clock;
    private final BotProperties properties;
    private final BookingService bookingService;
    private final ObjectProvider<TelegramBotsLongPollingApplication> longPolling;

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("ok", clock.getZone().getId(), now());
    }

    @GetMapping("/status")
    public StatusResponse status() {
        boolean storageOk;
        try {
            bookingService.services();
            storageOk = true;
        } catch (StorageException e) {
            log.warn("API: хранилище недоступно при проверке статуса: {}", e.getMessage());
            storageOk = false;
        }
        Path file = properties.excelFile().getFileName();
        return new StatusResponse(
                new StatusResponse.Bot(botRunning(), properties.username()),
                new StatusResponse.Storage(storageOk, file == null ? "" : file.toString()),
                now());
    }

    /** API работает в процессе бота: если long polling недоступен для проверки — процесс жив, значит true. */
    private boolean botRunning() {
        TelegramBotsLongPollingApplication application = longPolling.getIfAvailable();
        if (application == null) {
            return true;
        }
        try {
            return application.isRunning();
        } catch (RuntimeException e) {
            log.debug("Не удалось проверить состояние long polling", e);
            return true;
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MINUTES);
    }
}
