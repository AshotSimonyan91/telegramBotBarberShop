package com.salon.bot.bot;

import com.salon.bot.config.BotProperties;
import com.salon.bot.storage.BookingRepository;
import com.salon.bot.storage.StorageBusyException;
import com.salon.bot.storage.StorageException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.AfterBotRegistration;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

@Slf4j
@Component
@RequiredArgsConstructor
public class SalonBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    static final String STORAGE_ERROR_TEXT =
            "Извините, сейчас не получается сохранить или прочитать данные. "
                    + "Пожалуйста, попробуйте ещё раз через пару минут.";
    static final String GENERIC_ERROR_TEXT =
            "Извините, что-то пошло не так. Пожалуйста, попробуйте ещё раз или нажмите /start.";

    private final BotProperties properties;
    private final UpdateRouter router;
    private final BookingRepository repository;
    private final Messenger messenger;
    private final AdminNotifier adminNotifier;

    @PostConstruct
    void initStorage() {
        repository.init();
    }

    @Override
    public String getBotToken() {
        return properties.token();
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        try {
            router.route(update);
        } catch (StorageException e) {
            log.error("Ошибка хранилища при обработке update {}", update.getUpdateId(), e);
            replyError(update, STORAGE_ERROR_TEXT);
            if (e instanceof StorageBusyException) {
                adminNotifier.storageBusy();
            }
        } catch (Exception e) {
            log.error("Ошибка обработки update {}", update.getUpdateId(), e);
            replyError(update, GENERIC_ERROR_TEXT);
        }
    }

    private void replyError(Update update, String text) {
        Long chatId = null;
        if (update.hasMessage()) {
            chatId = update.getMessage().getChatId();
        } else if (update.hasCallbackQuery()) {
            chatId = update.getCallbackQuery().getFrom().getId();
        }
        if (chatId != null) {
            messenger.send(chatId, text);
        }
    }

    @AfterBotRegistration
    public void afterRegistration() {
        log.info("Бот @{} запущен (long polling)", properties.username());
    }
}
