package com.salon.bot.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Единый формат ошибки API: {@code {"error":"код","message":"текст для пользователя","fields":{...}}}.
 * {@code fields} есть только у ошибок валидации.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Map<String, String> fields) {

    public static final String UNAUTHORIZED = "unauthorized";
    public static final String VALIDATION = "validation";
    public static final String NOT_FOUND = "not_found";
    public static final String SLOT_TAKEN = "slot_taken";
    public static final String ALREADY_CANCELLED = "already_cancelled";
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String STORAGE_BUSY = "storage_busy";
    public static final String STORAGE_ERROR = "storage_error";
    public static final String INTERNAL = "internal";

    public ApiError(String error, String message) {
        this(error, message, null);
    }
}
