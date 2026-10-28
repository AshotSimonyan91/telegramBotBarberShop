package com.salon.bot.handler;

import com.salon.bot.bot.Messenger;
import com.salon.bot.keyboard.Callbacks;
import com.salon.bot.keyboard.KeyboardFactory;
import com.salon.bot.state.SessionStorage;
import com.salon.bot.state.UserSession;
import com.salon.bot.state.UserState;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * /start (в том числе с deep link: /start salon_1) и кнопка «В меню».
 */
@Order(10)
@Component
@RequiredArgsConstructor
public class StartHandler implements UpdateHandler {

    static final String COMMAND = "/start";
    static final String WELCOME = """
            Здравствуйте! 👋
            Добро пожаловать в наш салон красоты.

            Здесь можно за пару минут записаться к мастеру или посмотреть свои записи.""";

    private final Messenger messenger;
    private final SessionStorage sessions;
    private final KeyboardFactory keyboards;

    @Override
    public boolean supports(Update update) {
        if (update.hasCallbackQuery()) {
            return Callbacks.HOME.equals(update.getCallbackQuery().getData());
        }
        return Commands.is(update, COMMAND);
    }

    @Override
    public void handle(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery query = update.getCallbackQuery();
            UserSession session = sessions.get(query.getFrom().getId());
            session.resetDraft();
            messenger.edit(session.getChatId(), query.getMessage().getMessageId(), WELCOME, keyboards.mainMenu());
            return;
        }

        long chatId = update.getMessage().getChatId();
        UserSession session = sessions.get(chatId);
        if (session.getState() == UserState.ENTERING_PHONE) {
            messenger.send(chatId, "Хорошо, начнём сначала.", keyboards.removeReplyKeyboard());
        }
        session.resetDraft();
        session.setSource(source(Commands.argument(update)));
        messenger.send(chatId, WELCOME, keyboards.mainMenu());
    }

    /** Метка источника из deep link; без параметра — «direct». */
    static String source(String payload) {
        if (payload == null || payload.isBlank()) {
            return UserSession.DEFAULT_SOURCE;
        }
        String cleaned = payload.trim().replaceAll("[^A-Za-z0-9_\\-]", "");
        return cleaned.isEmpty() ? UserSession.DEFAULT_SOURCE : cleaned.substring(0, Math.min(64, cleaned.length()));
    }
}
