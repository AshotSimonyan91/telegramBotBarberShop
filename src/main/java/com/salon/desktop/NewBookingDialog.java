package com.salon.desktop;

import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import com.salon.bot.service.BookingService;
import com.salon.bot.service.SlotTakenException;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Запись клиента администратором (по телефону, на месте). Свободное время считается так же, как в боте.
 */
class NewBookingDialog extends JDialog {

    /** Источник записей, созданных в приложении (колонка «Источник»). */
    static final String SOURCE = BookingService.ADMIN_SOURCE;
    private static final int DAYS_AHEAD = 30;

    record Catalog(List<Service> services, List<Master> masters) {
    }

    private final BookingService bookingService;

    private final JTextField name = new JTextField(22);
    private final JTextField phone = new JTextField(22);
    private final JComboBox<Service> service = new JComboBox<>();
    private final JComboBox<Master> master = new JComboBox<>();
    private final JComboBox<LocalDate> date = new JComboBox<>();
    private final JComboBox<LocalTime> time = new JComboBox<>();
    private final JLabel hint = new JLabel(" ");
    private final JButton save = new JButton("Записать");

    /** Номер запроса слотов: ответы на устаревшие запросы игнорируются. */
    private int slotsRequest;
    /** Список дат перезаполняется — не загружать слоты на каждый промежуточный выбор. */
    private boolean fillingDates;
    private Booking result;

    NewBookingDialog(Window owner, BookingService bookingService, Catalog catalog) {
        super(owner, "Новая запись", ModalityType.APPLICATION_MODAL);
        this.bookingService = bookingService;

        catalog.services().forEach(service::addItem);
        catalog.masters().forEach(master::addItem);
        render(service, s -> s.getName() + " · " + s.getDurationMinutes() + " мин · " + Texts.price(s.getPrice()));
        render(master, Master::getName);
        render(date, Texts::fullDate);
        render(time, Texts::time);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 4, 12));
        row(form, 0, "Клиент", name);
        row(form, 1, "Телефон", phone);
        row(form, 2, "Услуга", service);
        row(form, 3, "Мастер", master);
        row(form, 4, "Дата", date);
        row(form, 5, "Время", time);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = 6;
        c.anchor = GridBagConstraints.WEST;
        form.add(hint, c);

        JButton cancel = new JButton("Отмена");
        cancel.addActionListener(e -> dispose());
        save.addActionListener(e -> save());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);

        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);

        service.addActionListener(e -> loadSlots());
        master.addActionListener(e -> loadDates());
        date.addActionListener(e -> {
            if (!fillingDates) {
                loadSlots();
            }
        });

        pack();
        setLocationRelativeTo(owner);
        loadDates();
    }

    Optional<Booking> result() {
        return Optional.ofNullable(result);
    }

    private static void row(JPanel form, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(4, 4, 4, 8);
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel(label), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
    }

    private static <T> void render(JComboBox<T> combo, Function<T, String> text) {
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            @SuppressWarnings("unchecked")
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                String label = value == null ? "" : text.apply((T) value);
                return super.getListCellRendererComponent(list, label, index, selected, focused);
            }
        });
    }

    /** Рабочие дни выбранного мастера на {@value DAYS_AHEAD} дней вперёд. */
    private void loadDates() {
        Master m = (Master) master.getSelectedItem();
        if (m == null) {
            return;
        }
        LocalDate previous = (LocalDate) date.getSelectedItem();
        fillingDates = true;
        try {
            date.removeAllItems();
            bookingService.availableDates(List.of(m), DAYS_AHEAD).forEach(date::addItem);
            if (previous != null) {
                date.setSelectedItem(previous);
            }
        } finally {
            fillingDates = false;
        }
        loadSlots();
    }

    private void loadSlots() {
        Master m = (Master) master.getSelectedItem();
        Service s = (Service) service.getSelectedItem();
        LocalDate d = (LocalDate) date.getSelectedItem();
        time.removeAllItems();
        save.setEnabled(false);
        if (m == null || s == null || d == null) {
            hint.setText(" ");
            return;
        }
        int request = ++slotsRequest;
        hint.setText("Загрузка свободного времени…");
        Background.run(
                () -> bookingService.freeSlots(m, d, s.getDurationMinutes()),
                slots -> {
                    if (request != slotsRequest) {
                        return;
                    }
                    slots.forEach(time::addItem);
                    hint.setText(slots.isEmpty() ? "Нет свободного времени — выберите другую дату" : " ");
                    save.setEnabled(!slots.isEmpty());
                },
                e -> {
                    if (request == slotsRequest) {
                        hint.setText("⚠ " + Dialogs.storageMessage(e).replace('\n', ' '));
                    }
                });
    }

    private void save() {
        String clientName = name.getText().trim();
        if (!Texts.isValidName(clientName)) {
            Dialogs.error(this, "Введите имя клиента (только буквы, 2–50 символов).");
            name.requestFocus();
            return;
        }
        Optional<String> clientPhone = Texts.normalizePhone(phone.getText());
        if (clientPhone.isEmpty()) {
            Dialogs.error(this, "Введите телефон: 8–15 цифр, можно с «+», например +37491123456.");
            phone.requestFocus();
            return;
        }
        Master m = (Master) master.getSelectedItem();
        Service s = (Service) service.getSelectedItem();
        Booking draft = Booking.builder()
                .clientName(clientName)
                .telegramId(0)
                .username("")
                .phone(clientPhone.get())
                .serviceName(s.getName())
                .date((LocalDate) date.getSelectedItem())
                .time((LocalTime) time.getSelectedItem())
                .source(SOURCE)
                .durationMinutes(s.getDurationMinutes())
                .build();

        save.setEnabled(false);
        Background.run(
                () -> bookingService.book(draft, m),
                booking -> {
                    result = booking;
                    dispose();
                },
                e -> {
                    if (e instanceof SlotTakenException) {
                        Dialogs.error(this, "Это время уже заняли. Выберите другое.");
                        loadSlots();
                    } else {
                        Dialogs.error(this, Dialogs.storageMessage(e));
                        save.setEnabled(true);
                    }
                });
    }
}
