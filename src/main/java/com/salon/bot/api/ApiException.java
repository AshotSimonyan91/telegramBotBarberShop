package com.salon.bot.api;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ожидаемая ошибка запроса, которую {@link ApiExceptionHandler} превращает в {@link ApiError}.
 */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fields;

    public ApiException(HttpStatus status, String code, String message, Map<String, String> fields) {
        super(message);
        this.status = status;
        this.code = code;
        this.fields = fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, ApiError.NOT_FOUND, message, null);
    }

    public static ApiException validation(Map<String, String> fields) {
        return new ApiException(HttpStatus.BAD_REQUEST, ApiError.VALIDATION,
                "Проверьте правильность заполнения полей.", fields);
    }

    public static ApiException validation(String field, String message) {
        return validation(Map.of(field, message));
    }

    public static ApiException slotTaken() {
        return new ApiException(HttpStatus.CONFLICT, ApiError.SLOT_TAKEN, "Это время уже занято", null);
    }

    public static ApiException alreadyCancelled() {
        return new ApiException(HttpStatus.CONFLICT, ApiError.ALREADY_CANCELLED, "Запись уже отменена", null);
    }
}
