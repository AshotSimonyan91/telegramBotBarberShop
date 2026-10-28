package com.salon.bot.storage;

/**
 * Ошибка хранилища записей.
 */
public class StorageException extends RuntimeException {

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
