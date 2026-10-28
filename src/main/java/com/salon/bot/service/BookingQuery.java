package com.salon.bot.service;

import com.salon.bot.model.BookingStatus;

import java.time.LocalDate;

/**
 * Фильтр списка записей. Все поля необязательны: {@code null} — без ограничения.
 *
 * @param date       точная дата визита (взаимоисключающа с {@code from}/{@code to})
 * @param from       начало периода, включительно
 * @param to         конец периода, включительно
 * @param status     статус записи
 * @param text       подстрока без учёта регистра в имени клиента, телефоне, услуге или username
 * @param masterName имя мастера без учёта регистра
 */
public record BookingQuery(
        LocalDate date,
        LocalDate from,
        LocalDate to,
        BookingStatus status,
        String text,
        String masterName
) {

    public static BookingQuery all() {
        return new BookingQuery(null, null, null, null, null, null);
    }
}
