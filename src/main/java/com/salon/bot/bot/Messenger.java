package com.salon.bot.bot;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.ByteArrayInputStream;

/**
 * Обёртка над TelegramClient: ошибки Telegram API логируются и не роняют обработку.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Messenger {

    private final TelegramClient client;

    /** Callback, на который уже ответили в текущем потоке обработки. */
    private final ThreadLocal<String> answeredCallback = new ThreadLocal<>();

    /** Отправляет сообщение и возвращает его ID (или null при ошибке). */
    public Integer send(long chatId, String text, ReplyKeyboard markup) {
        try {
            Message message = client.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .replyMarkup(markup)
                    .build());
            return message == null ? null : message.getMessageId();
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить сообщение в чат {}", chatId, e);
            return null;
        }
    }

    public Integer send(long chatId, String text) {
        return send(chatId, text, null);
    }

    /**
     * Редактирует сообщение. Если отредактировать нельзя — отправляет новое.
     *
     * @return ID показанного сообщения
     */
    public Integer edit(long chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        if (messageId == null) {
            return send(chatId, text, markup);
        }
        try {
            client.execute(EditMessageText.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .text(text)
                    .replyMarkup(markup)
                    .build());
            return messageId;
        } catch (TelegramApiRequestException e) {
            if (e.getApiResponse() != null && e.getApiResponse().contains("message is not modified")) {
                return messageId;
            }
            log.warn("Не удалось отредактировать сообщение {} в чате {}: {}", messageId, chatId, e.getMessage());
            return send(chatId, text, markup);
        } catch (TelegramApiException e) {
            log.warn("Не удалось отредактировать сообщение {} в чате {}: {}", messageId, chatId, e.getMessage());
            return send(chatId, text, markup);
        }
    }

    /** Отвечает на callback (один раз за обработку); text — всплывающая подсказка или null. */
    public void answer(String callbackId, String text) {
        if (callbackId.equals(answeredCallback.get())) {
            return;
        }
        answeredCallback.set(callbackId);
        try {
            client.execute(AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.debug("Не удалось ответить на callback {}: {}", callbackId, e.getMessage());
        }
    }

    void finishCallback(String callbackId) {
        answer(callbackId, null);
        answeredCallback.remove();
    }

    public boolean sendDocument(long chatId, String fileName, byte[] content, String caption) {
        try {
            client.execute(SendDocument.builder()
                    .chatId(chatId)
                    .document(new InputFile(new ByteArrayInputStream(content), fileName))
                    .caption(caption)
                    .build());
            return true;
        } catch (TelegramApiException e) {
            log.error("Не удалось отправить документ в чат {}", chatId, e);
            return false;
        }
    }
}
