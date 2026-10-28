package com.salon.bot.api.dto;

/**
 * Тело POST /api/v1/bookings. Поля — строки, чтобы ошибки формата возвращать по полям.
 * {@code masterName} null/пусто — «любой свободный мастер».
 */
public record NewBookingRequest(
        String clientName,
        String phone,
        String serviceName,
        String masterName,
        String date,
        String time
) {
}
