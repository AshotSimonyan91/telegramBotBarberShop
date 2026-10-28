package com.salon.desktop;

import lombok.extern.slf4j.Slf4j;

import javax.swing.SwingWorker;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

/**
 * Работа с файлом и сетью вне потока Swing, чтобы окно не зависало.
 */
@Slf4j
final class Background {

    private Background() {
    }

    static <T> void run(Callable<T> task, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        new SwingWorker<T, Void>() {
            @Override
            protected T doInBackground() throws Exception {
                return task.call();
            }

            @Override
            protected void done() {
                try {
                    onSuccess.accept(get());
                } catch (ExecutionException e) {
                    log.error("Ошибка фоновой операции", e.getCause());
                    onError.accept(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }
}
