package com.salon.bot.api;

import com.salon.bot.service.SlotTakenException;
import com.salon.bot.storage.StorageBusyException;
import com.salon.bot.storage.StorageException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Переводит исключения в единый формат {@link ApiError}. Тексты — для пользователя, детали — в лог.
 */
@Slf4j
@ConditionalOnApi
@RestControllerAdvice
public class ApiExceptionHandler {

    static final String STORAGE_BUSY_TEXT =
            "Файл с записями сейчас занят (возможно, открыт в Excel). Закройте его и повторите через минуту.";
    static final String STORAGE_ERROR_TEXT =
            "Не получается прочитать или сохранить данные. Попробуйте ещё раз позже.";
    static final String INTERNAL_TEXT = "Что-то пошло не так. Попробуйте ещё раз.";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ApiError(e.getCode(), e.getMessage(), e.getFields()));
    }

    @ExceptionHandler(SlotTakenException.class)
    ResponseEntity<ApiError> slotTaken(SlotTakenException e) {
        return api(ApiException.slotTaken());
    }

    @ExceptionHandler(StorageBusyException.class)
    ResponseEntity<ApiError> storageBusy(StorageBusyException e) {
        log.warn("API: хранилище занято: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(ApiError.STORAGE_BUSY, STORAGE_BUSY_TEXT));
    }

    @ExceptionHandler(StorageException.class)
    ResponseEntity<ApiError> storage(StorageException e) {
        log.error("API: ошибка хранилища", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiError.STORAGE_ERROR, STORAGE_ERROR_TEXT));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException e) {
        return api(ApiException.validation(e.getParameterName(), "Обязательный параметр"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e) {
        return api(ApiException.validation(e.getName(), "Неверное значение"));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ApiError> unreadable(Exception e) {
        log.debug("API: не удалось прочитать тело запроса: {}", e.getMessage());
        return ResponseEntity.badRequest().body(new ApiError(ApiError.VALIDATION,
                "Не удалось прочитать запрос: ожидается JSON."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return api(ApiException.notFound("Такого адреса нет"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError(ApiError.METHOD_NOT_ALLOWED, "Метод не поддерживается"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> internal(Exception e) {
        log.error("API: необработанная ошибка", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ApiError.INTERNAL, INTERNAL_TEXT));
    }
}
