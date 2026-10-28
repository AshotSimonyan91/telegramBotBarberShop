package com.salon.desktop;

import com.salon.bot.storage.StorageBusyException;
import com.salon.bot.storage.StorageException;

import javax.swing.JOptionPane;
import java.awt.Component;

/**
 * Сообщения пользователю.
 */
final class Dialogs {

    static final String TITLE = "Салон";

    private Dialogs() {
    }

    static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, TITLE, JOptionPane.ERROR_MESSAGE);
    }

    static void info(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, TITLE, JOptionPane.INFORMATION_MESSAGE);
    }

    static boolean confirm(Component parent, String message) {
        return JOptionPane.showConfirmDialog(parent, message, TITLE,
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION;
    }

    /** Понятный текст для ошибки хранилища. */
    static String storageMessage(Throwable e) {
        if (e instanceof StorageBusyException) {
            return "Excel-файл занят другой программой. Закройте его в Excel и повторите.";
        }
        if (e instanceof StorageException) {
            return "Ошибка чтения или записи данных:\n" + e.getMessage();
        }
        return "Непредвиденная ошибка: " + e.getMessage();
    }
}
