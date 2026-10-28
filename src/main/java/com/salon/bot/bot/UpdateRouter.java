package com.salon.bot.bot;

import com.salon.bot.handler.UpdateHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.util.List;

/**
 * Передаёт Update первому обработчику (в порядке @Order), который его поддерживает.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateRouter {

    private final List<UpdateHandler> handlers;
    private final Messenger messenger;

    public void route(Update update) {
        try {
            for (UpdateHandler handler : handlers) {
                if (handler.supports(update)) {
                    handler.handle(update);
                    return;
                }
            }
            log.debug("Нет обработчика для update {}", update.getUpdateId());
        } finally {
            if (update.hasCallbackQuery()) {
                messenger.finishCallback(update.getCallbackQuery().getId());
            }
        }
    }
}
