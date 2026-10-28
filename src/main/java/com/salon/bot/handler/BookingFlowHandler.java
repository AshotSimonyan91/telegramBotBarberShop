package com.salon.bot.handler;

import com.salon.bot.bot.AdminNotifier;
import com.salon.bot.bot.Messenger;
import com.salon.bot.keyboard.Callbacks;
import com.salon.bot.keyboard.KeyboardFactory;
import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import com.salon.bot.service.BookingService;
import com.salon.bot.service.SlotTakenException;
import com.salon.bot.state.SessionStorage;
import com.salon.bot.state.UserSession;
import com.salon.bot.state.UserState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Contact;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Пошаговый сценарий записи: услуга → мастер → дата → время → имя → телефон → подтверждение.
 * Нажатия кнопок редактируют текущее сообщение; после ввода текста отправляется новое.
 */
@Slf4j
@Order(50)
@Component
@RequiredArgsConstructor
public class BookingFlowHandler implements UpdateHandler {

    static final int DAYS_AHEAD = 14;
    private static final DateTimeFormatter DATE_DATA = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME_DATA = DateTimeFormatter.ofPattern("HHmm");
    private static final Set<UserState> TEXT_INPUT_STATES = EnumSet.of(UserState.ENTERING_NAME, UserState.ENTERING_PHONE);
    private static final String STALE = "Эта кнопка устарела. Нажмите «Записаться» ещё раз.";

    private final Messenger messenger;
    private final SessionStorage sessions;
    private final KeyboardFactory keyboards;
    private final BookingService bookingService;
    private final AdminNotifier adminNotifier;

    /** Откуда пришло действие: messageId != null — нажата кнопка этого сообщения (его и редактируем). */
    private record Ctx(long chatId, User user, Integer messageId, String callbackId) {
    }

    @Override
    public boolean supports(Update update) {
        if (update.hasCallbackQuery()) {
            String data = update.getCallbackQuery().getData();
            return switch (data) {
                case Callbacks.BOOK, Callbacks.BACK, Callbacks.CONFIRM, Callbacks.ABORT,
                     Callbacks.NAME_FROM_TELEGRAM -> true;
                default -> data.startsWith(Callbacks.SERVICE) || data.startsWith(Callbacks.MASTER)
                        || data.startsWith(Callbacks.DATE) || data.startsWith(Callbacks.TIME);
            };
        }
        if (update.hasMessage() && (update.getMessage().hasText() || update.getMessage().hasContact())) {
            return TEXT_INPUT_STATES.contains(sessions.get(update.getMessage().getChatId()).getState());
        }
        return false;
    }

    @Override
    public void handle(Update update) {
        if (update.hasCallbackQuery()) {
            CallbackQuery q = update.getCallbackQuery();
            UserSession session = sessions.get(q.getFrom().getId());
            synchronized (session) {
                onCallback(new Ctx(q.getFrom().getId(), q.getFrom(), q.getMessage().getMessageId(), q.getId()),
                        session, q.getData());
            }
        } else {
            Message m = update.getMessage();
            UserSession session = sessions.get(m.getChatId());
            synchronized (session) {
                onMessage(new Ctx(m.getChatId(), m.getFrom(), null, null), session, m);
            }
        }
    }

    // ---------------------------------------------------------------- кнопки

    private void onCallback(Ctx ctx, UserSession s, String data) {
        UserState state = s.getState();
        try {
            if (data.equals(Callbacks.BOOK)) {
                s.resetDraft();
                showServices(ctx, s);
            } else if (data.equals(Callbacks.BACK)) {
                back(ctx, s);
            } else if (data.startsWith(Callbacks.SERVICE) && state == UserState.CHOOSING_SERVICE) {
                onService(ctx, s, Integer.parseInt(data.substring(Callbacks.SERVICE.length())));
            } else if (data.startsWith(Callbacks.MASTER) && state == UserState.CHOOSING_MASTER) {
                onMaster(ctx, s, data);
            } else if (data.startsWith(Callbacks.DATE) && state == UserState.CHOOSING_DATE) {
                s.setDate(LocalDate.parse(data.substring(Callbacks.DATE.length()), DATE_DATA));
                showTimes(ctx, s, "");
            } else if (data.startsWith(Callbacks.TIME) && state == UserState.CHOOSING_TIME) {
                onTime(ctx, s, LocalTime.parse(data.substring(Callbacks.TIME.length()), TIME_DATA));
            } else if (data.equals(Callbacks.NAME_FROM_TELEGRAM) && state == UserState.ENTERING_NAME) {
                onName(ctx, s, Texts.telegramName(ctx.user()));
            } else if (data.equals(Callbacks.CONFIRM) && state == UserState.CONFIRMING) {
                onConfirm(ctx, s);
            } else if (data.equals(Callbacks.ABORT) && state == UserState.CONFIRMING) {
                s.resetDraft();
                show(ctx, "Запись не оформлена. Будем рады видеть вас в другой раз! 🙂", keyboards.mainMenu());
            } else {
                messenger.answer(ctx.callbackId(), STALE);
            }
        } catch (NumberFormatException | DateTimeParseException | IndexOutOfBoundsException e) {
            log.warn("Некорректные callback data «{}» от {}", data, ctx.chatId());
            messenger.answer(ctx.callbackId(), STALE);
        }
    }

    private void back(Ctx ctx, UserSession s) {
        switch (s.getState()) {
            case CHOOSING_SERVICE -> {
                s.resetDraft();
                show(ctx, StartHandler.WELCOME, keyboards.mainMenu());
            }
            case CHOOSING_MASTER -> showServices(ctx, s);
            case CHOOSING_DATE -> showMasters(ctx, s);
            case CHOOSING_TIME -> showDates(ctx, s);
            case ENTERING_NAME -> showTimes(ctx, s, "");
            case ENTERING_PHONE -> {
                messenger.send(ctx.chatId(), "Хорошо, вернёмся к имени.", keyboards.removeReplyKeyboard());
                showName(ctx, s);
            }
            case CONFIRMING -> showPhone(ctx, s);
            default -> messenger.answer(ctx.callbackId(), STALE);
        }
    }

    // ---------------------------------------------------------------- шаги

    private void showServices(Ctx ctx, UserSession s) {
        s.setState(UserState.CHOOSING_SERVICE);
        List<Service> services = bookingService.services();
        if (services.isEmpty()) {
            show(ctx, "Список услуг пока пуст. Пожалуйста, свяжитесь с салоном.", keyboards.backOnly());
            return;
        }
        StringBuilder text = new StringBuilder("Шаг 1 из 7 · Услуга\n");
        for (Service service : services) {
            text.append("\n• ").append(service.getName()).append(" — ").append(service.getDurationMinutes())
                    .append(" мин, ").append(Texts.price(service.getPrice()));
        }
        text.append("\n\nВыберите услугу:");
        show(ctx, text.toString(), keyboards.services(services));
    }

    private void onService(Ctx ctx, UserSession s, int index) {
        s.setService(bookingService.services().get(index));
        showMasters(ctx, s);
    }

    private void showMasters(Ctx ctx, UserSession s) {
        s.setState(UserState.CHOOSING_MASTER);
        List<Master> masters = bookingService.activeMasters();
        if (masters.isEmpty()) {
            show(ctx, "Сейчас нет доступных мастеров. Пожалуйста, свяжитесь с салоном.", keyboards.backOnly());
            return;
        }
        show(ctx, "Шаг 2 из 7 · Мастер\n\nУслуга: " + s.getService().getName() + "\n\nВыберите мастера:",
                keyboards.masters(masters));
    }

    private void onMaster(Ctx ctx, UserSession s, String data) {
        if (data.equals(Callbacks.ANY_MASTER)) {
            s.setAnyMaster(true);
            s.setMaster(null);
        } else {
            int index = Integer.parseInt(data.substring(Callbacks.MASTER.length()));
            s.setAnyMaster(false);
            s.setMaster(bookingService.activeMasters().get(index));
        }
        showDates(ctx, s);
    }

    private void showDates(Ctx ctx, UserSession s) {
        s.setState(UserState.CHOOSING_DATE);
        List<LocalDate> dates = bookingService.availableDates(candidateMasters(s), DAYS_AHEAD);
        String header = "Шаг 3 из 7 · Дата\n\n" + "Услуга: " + s.getService().getName() + "\n"
                + "Мастер: " + masterTitle(s) + "\n\n";
        if (dates.isEmpty()) {
            show(ctx, header + "В ближайшие " + DAYS_AHEAD + " дней нет рабочих дней. Выберите другого мастера.",
                    keyboards.backOnly());
            return;
        }
        show(ctx, header + "Выберите дату:", keyboards.dates(dates));
    }

    private void showTimes(Ctx ctx, UserSession s, String notice) {
        s.setState(UserState.CHOOSING_TIME);
        if (s.isAnyMaster()) {
            s.setMaster(null);
        }
        int duration = s.getService().getDurationMinutes();
        List<LocalTime> slots = bookingService.freeSlots(candidateMasters(s), s.getDate(), duration);
        if (slots.isEmpty()) {
            show(ctx, notice + "На " + Texts.fullDate(s.getDate()) + " свободного времени нет 😔\n"
                    + "Пожалуйста, выберите другую дату.", keyboards.noSlots());
            return;
        }
        show(ctx, notice + "Шаг 4 из 7 · Время\n\n" + "Услуга: " + s.getService().getName() + " ("
                + duration + " мин)\n" + "Мастер: " + masterTitle(s) + "\n"
                + "Дата: " + Texts.fullDate(s.getDate()) + "\n\nВыберите время:", keyboards.times(slots));
    }

    private void onTime(Ctx ctx, UserSession s, LocalTime time) {
        int duration = s.getService().getDurationMinutes();
        Optional<Master> master = bookingService.firstFreeMaster(candidateMasters(s), s.getDate(), time, duration);
        if (master.isEmpty()) {
            showTimes(ctx, s, "⚠️ Это время уже заняли, выберите другое.\n\n");
            return;
        }
        s.setMaster(master.get());
        s.setTime(time);
        showName(ctx, s);
    }

    private void showName(Ctx ctx, UserSession s) {
        s.setState(UserState.ENTERING_NAME);
        show(ctx, "Шаг 5 из 7 · Имя\n\nКак к вам обращаться?\n"
                        + "Нажмите кнопку с вашим именем или напишите имя сообщением.",
                keyboards.name(Texts.telegramName(ctx.user())));
    }

    private void onName(Ctx ctx, UserSession s, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            showName(ctx, s);
            return;
        }
        s.setClientName(trimmed.length() > 50 ? trimmed.substring(0, 50) : trimmed);
        showPhone(ctx, s);
    }

    private void showPhone(Ctx ctx, UserSession s) {
        s.setState(UserState.ENTERING_PHONE);
        show(ctx, "Шаг 6 из 7 · Телефон\n\nИмя: " + s.getClientName() + "\n\n"
                + "Отправьте номер кнопкой «" + KeyboardFactory.SEND_CONTACT_TEXT + "» внизу экрана "
                + "или напишите его, например: +374 91 123456", keyboards.backOnly());
        messenger.send(ctx.chatId(), "👇 Поделитесь номером телефона", keyboards.requestContact());
    }

    private void showConfirm(Ctx ctx, UserSession s) {
        s.setState(UserState.CONFIRMING);
        Service service = s.getService();
        show(ctx, "Шаг 7 из 7 · Подтверждение\n\nПроверьте, пожалуйста, данные:\n\n"
                + "Услуга: " + service.getName() + " (" + service.getDurationMinutes() + " мин, "
                + Texts.price(service.getPrice()) + ")\n"
                + "Мастер: " + s.getMaster().getName() + "\n"
                + "Дата: " + Texts.fullDate(s.getDate()) + "\n"
                + "Время: " + Texts.time(s.getTime()) + "\n"
                + "Имя: " + s.getClientName() + "\n"
                + "Телефон: " + s.getPhone() + "\n\nВсё верно?", keyboards.confirm());
    }

    private void onConfirm(Ctx ctx, UserSession s) {
        Booking draft = Booking.builder()
                .clientName(s.getClientName())
                .telegramId(ctx.user().getId())
                .username(ctx.user().getUserName())
                .phone(s.getPhone())
                .serviceName(s.getService().getName())
                .date(s.getDate())
                .time(s.getTime())
                .source(s.getSource())
                .durationMinutes(s.getService().getDurationMinutes())
                .build();
        Booking booking;
        try {
            booking = bookingService.book(draft, s.getMaster());
        } catch (SlotTakenException e) {
            log.info("Слот {} {} у мастера {} занят при подтверждении", s.getDate(), s.getTime(), s.getMaster().getName());
            showTimes(ctx, s, "⚠️ К сожалению, это время только что заняли. Выберите, пожалуйста, другое.\n\n");
            return;
        }
        log.info("Новая запись №{}: клиент {}, {} {} у {} (источник {})", booking.getId(), booking.getTelegramId(),
                booking.getDate(), booking.getTime(), booking.getMasterName(), booking.getSource());
        s.resetDraft();
        show(ctx, "✅ Вы записаны!\n\nНомер записи: №" + booking.getId() + "\n" + Texts.clientDetails(booking)
                + "\n\nЖдём вас! За 2 часа до визита пришлём напоминание.", keyboards.afterBooking());
        adminNotifier.newBooking(booking);
    }

    // ---------------------------------------------------------------- ввод текста

    private void onMessage(Ctx ctx, UserSession s, Message m) {
        if (s.getState() == UserState.ENTERING_NAME) {
            if (m.hasText() && Texts.isValidName(m.getText())) {
                onName(ctx, s, m.getText());
            } else {
                messenger.send(ctx.chatId(), "Пожалуйста, напишите имя буквами (от 2 до 50 символов).");
            }
            return;
        }

        // ENTERING_PHONE
        if (m.hasText() && m.getText().trim().equals(KeyboardFactory.BACK_TEXT)) {
            messenger.send(ctx.chatId(), "Хорошо, вернёмся к имени.", keyboards.removeReplyKeyboard());
            showName(ctx, s);
            return;
        }
        Optional<String> phone = m.hasContact() ? contactPhone(m.getContact()) : Texts.normalizePhone(m.getText());
        if (phone.isEmpty()) {
            messenger.send(ctx.chatId(), "Похоже, номер указан неверно. Напишите его в формате +374 91 123456 "
                    + "или нажмите «" + KeyboardFactory.SEND_CONTACT_TEXT + "».", keyboards.requestContact());
            return;
        }
        s.setPhone(phone.get());
        messenger.send(ctx.chatId(), "Спасибо! Номер сохранён 👍", keyboards.removeReplyKeyboard());
        showConfirm(ctx, s);
    }

    private static Optional<String> contactPhone(Contact contact) {
        return Texts.normalizePhone(contact.getPhoneNumber())
                .map(p -> p.startsWith("+") ? p : "+" + p);
    }

    // ---------------------------------------------------------------- helpers

    private List<Master> candidateMasters(UserSession s) {
        return s.isAnyMaster() || s.getMaster() == null ? bookingService.activeMasters() : List.of(s.getMaster());
    }

    private static String masterTitle(UserSession s) {
        return s.isAnyMaster() || s.getMaster() == null ? "любой свободный" : s.getMaster().getName();
    }

    private void show(Ctx ctx, String text, InlineKeyboardMarkup markup) {
        messenger.edit(ctx.chatId(), ctx.messageId(), text, markup);
    }
}
