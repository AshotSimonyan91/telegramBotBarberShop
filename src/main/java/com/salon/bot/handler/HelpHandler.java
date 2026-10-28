package com.salon.bot.handler;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.keyboard.KeyboardFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.Update;

@Order(20)
@Component
@RequiredArgsConstructor
public class HelpHandler implements UpdateHandler {

    static final String HELP = """
            ℹ️ Что умеет бот:

            ✂️ Записаться — выбрать услугу, мастера, дату и время.
            📋 Мои записи — посмотреть предстоящие визиты и отменить запись.
            ⏰ За 2 часа до визита бот пришлёт напоминание.

            Команды:
            /start — главное меню
            /help — эта справка""";

    static final String ADMIN_HELP = """


            Для администратора:
            /admin — записи на сегодня и завтра
            /export — получить Excel-файл с записями""";

    private final Messenger messenger;
    private final KeyboardFactory keyboards;
    private final AdminNotifier adminNotifier;

    @Override
    public boolean supports(Update update) {
        return Commands.is(update, "/help");
    }

    @Override
    public void handle(Update update) {
        long chatId = update.getMessage().getChatId();
        String text = adminNotifier.isAdmin(chatId) ? HELP + ADMIN_HELP : HELP;
        messenger.send(chatId, text, keyboards.mainMenu());
    }
}
