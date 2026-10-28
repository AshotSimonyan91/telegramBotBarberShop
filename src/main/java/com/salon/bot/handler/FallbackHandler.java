package com.salon.bot.handler;

import com.salon.bot.bot.Messenger;
import com.salon.bot.keyboard.KeyboardFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Всё, что не распознали остальные обработчики.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Component
@RequiredArgsConstructor
public class FallbackHandler implements UpdateHandler {

    private final Messenger messenger;
    private final KeyboardFactory keyboards;

    @Override
    public boolean supports(Update update) {
        return true;
    }

    @Override
    public void handle(Update update) {
        if (update.hasCallbackQuery()) {
            messenger.answer(update.getCallbackQuery().getId(), "Кнопка устарела. Нажмите /start");
        } else if (update.hasMessage()) {
            messenger.send(update.getMessage().getChatId(),
                    "Я вас не понял 🙂 Выберите действие ниже или нажмите /help.", keyboards.mainMenu());
        }
    }
}
