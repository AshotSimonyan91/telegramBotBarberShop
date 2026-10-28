package com.salon.desktop;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Бот как дочерний процесс: запуск, остановка, вывод лога построчно.
 * Отдельный процесс — тот же jar, что и {@code start.sh}, поэтому поведение бота не меняется.
 */
@Slf4j
@Component
public class BotProcess {

    public enum State { STOPPED, STARTING, RUNNING, STOPPING }

    static final String BOT_MAIN = "com.salon.bot.BotApplication";
    static final String DEFAULT_JAR = "target/salon-bot.jar";
    /** Строка из лога бота, после которой он принимает сообщения (см. SalonBot.afterRegistration). */
    static final String STARTED_MARKER = "запущен (long polling)";
    private static final long STOP_TIMEOUT_SECONDS = 15;

    private final List<Consumer<String>> logListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<State>> stateListeners = new CopyOnWriteArrayList<>();

    private volatile Process process;
    private volatile State state = State.STOPPED;

    public State state() {
        return state;
    }

    public void onLog(Consumer<String> listener) {
        logListeners.add(listener);
    }

    public void onState(Consumer<State> listener) {
        stateListeners.add(listener);
    }

    public synchronized void start() throws IOException {
        if (process != null && process.isAlive()) {
            return;
        }
        List<String> command = command();
        log.info("Запуск бота: {}", String.join(" ", command));
        Process started = new ProcessBuilder(command).redirectErrorStream(true).start();
        process = started;
        setState(State.STARTING);

        Thread reader = new Thread(() -> readOutput(started), "bot-output");
        reader.setDaemon(true);
        reader.start();
    }

    /** Штатная остановка (Spring закрывает контекст); если бот не завершился — принудительно. */
    public synchronized void stop() {
        Process current = process;
        if (current == null || !current.isAlive()) {
            return;
        }
        setState(State.STOPPING);
        current.destroy();
        try {
            if (!current.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Бот не остановился за {} с, завершаю принудительно", STOP_TIMEOUT_SECONDS);
                current.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    @PreDestroy
    void shutdown() {
        stop();
    }

    /**
     * Бот, запущенный не из этого приложения (start.sh, IDE). Два экземпляра с одним токеном
     * получают от Telegram ошибку 409 Conflict.
     */
    public Optional<ProcessHandle> findForeignBot() {
        long self = ProcessHandle.current().pid();
        Process own = process;
        return ProcessHandle.allProcesses()
                .filter(p -> p.pid() != self && (own == null || p.pid() != own.pid()))
                .filter(p -> p.info().commandLine().map(BotProcess::isBotCommand).orElse(false))
                .findFirst();
    }

    static boolean isBotCommand(String commandLine) {
        return commandLine.contains("java")
                && (commandLine.contains("salon-bot.jar") || commandLine.contains(BOT_MAIN))
                && !commandLine.contains("com.salon.desktop");
    }

    private void readOutput(Process p) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (state == State.STARTING && line.contains(STARTED_MARKER)) {
                    setState(State.RUNNING);
                }
                String l = line;
                logListeners.forEach(listener -> listener.accept(l));
            }
        } catch (IOException e) {
            log.debug("Чтение вывода бота прервано: {}", e.getMessage());
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        String message = "— Бот остановлен (код выхода " + code + ") —";
        logListeners.forEach(listener -> listener.accept(message));
        synchronized (this) {
            if (process == p) {
                process = null;
            }
        }
        setState(State.STOPPED);
    }

    private void setState(State newState) {
        state = newState;
        stateListeners.forEach(listener -> listener.accept(newState));
    }

    /**
     * Команда запуска: из собранного jar — {@code java -jar salon-bot.jar},
     * из IDE (classpath из каталогов) — {@code java -cp <classpath> BotApplication}.
     */
    static List<String> command() {
        return command(javaExecutable(), System.getProperty("java.class.path", ""));
    }

    static List<String> command(String java, String classPath) {
        List<String> command = new ArrayList<>(List.of(java,
                "-Djava.awt.headless=true", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        String[] entries = classPath.split(File.pathSeparator);
        if (entries.length == 1 && entries[0].endsWith(".jar")) {
            command.addAll(List.of("-jar", entries[0]));
        } else if (classPath.isBlank()) {
            command.addAll(List.of("-jar", DEFAULT_JAR));
        } else {
            command.addAll(List.of("-cp", classPath, BOT_MAIN));
        }
        return command;
    }

    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        // Windows: javaw — без лишнего окна консоли, вывод всё равно идёт в pipe
        Path javaw = bin.resolve("javaw.exe");
        return Files.exists(javaw) ? javaw.toString() : bin.resolve("java").toString();
    }
}
