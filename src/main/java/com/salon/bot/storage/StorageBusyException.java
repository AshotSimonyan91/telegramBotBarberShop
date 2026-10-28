package com.salon.bot.storage;

/**
 * Хранилище временно недоступно (например, Excel-файл открыт в Excel).
 */
public class StorageBusyException extends StorageException {

    public StorageBusyException(String message, Throwable cause) {
        super(message, cause);
    }
}
