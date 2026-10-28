package com.salon.desktop;

import com.salon.bot.bot.Messenger;
import com.salon.bot.config.BotProperties;
import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;
import com.salon.bot.model.Master;
import com.salon.bot.service.BookingService;
import com.salon.bot.storage.BookingRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Вкладка «Записи»: таблица с фильтрами, добавление и отмена записей.
 */
@Lazy // Swing-компоненты создаются в потоке Swing (см. DesktopApp)
@Component
public class BookingsPanel extends JPanel {

    private static final int AUTO_REFRESH_MS = 30_000;
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Color CANCELLED_COLOR = new Color(150, 150, 150);

    private final BookingRepository repository;
    private final BookingService bookingService;
    private final Messenger messenger;
    private final BotProperties properties;
    private final Clock clock;

    private final BookingTableModel model = new BookingTableModel();
    private final JTable table = new JTable(model);
    private final JComboBox<BookingFilter.Period> period = new JComboBox<>(BookingFilter.Period.values());
    private final JComboBox<String> master = new JComboBox<>(new String[]{BookingFilter.ALL_MASTERS});
    private final JCheckBox showCancelled = new JCheckBox("Отменённые");
    private final JTextField search = new JTextField(14);
    private final JButton cancelButton = new JButton("Отменить запись");
    private final JLabel status = new JLabel(" ");

    private List<Booking> all = List.of();
    private boolean loading;

    public BookingsPanel(BookingRepository repository, BookingService bookingService, Messenger messenger,
                         BotProperties properties, Clock clock) {
        super(new BorderLayout());
        this.repository = repository;
        this.bookingService = bookingService;
        this.messenger = messenger;
        this.properties = properties;
        this.clock = clock;

        period.setSelectedItem(BookingFilter.Period.UPCOMING);
        search.putClientProperty("JTextField.placeholderText", "Имя, телефон или №");
        search.putClientProperty("JTextField.showClearButton", true);

        JButton refresh = new JButton("Обновить");
        refresh.addActionListener(e -> reload());
        JButton add = new JButton("Новая запись");
        add.addActionListener(e -> openNewBookingDialog());
        cancelButton.addActionListener(e -> cancelSelected());
        cancelButton.setEnabled(false);
        JButton openFile = new JButton("Открыть файл");
        openFile.setToolTipText("Открыть Excel-файл с записями");
        openFile.addActionListener(e -> openExcelFile());

        JPanel filters = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filters.add(new JLabel("Период:"));
        filters.add(period);
        filters.add(master);
        filters.add(showCancelled);
        filters.add(search);
        filters.add(refresh);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(add);
        actions.add(cancelButton);
        actions.add(openFile);

        JPanel top = new JPanel(new BorderLayout());
        top.add(filters, BorderLayout.NORTH);
        top.add(actions, BorderLayout.SOUTH);

        setupTable();
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        period.addActionListener(e -> applyFilter());
        master.addActionListener(e -> applyFilter());
        showCancelled.addActionListener(e -> applyFilter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                applyFilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                applyFilter();
            }
        });

        new Timer(AUTO_REFRESH_MS, e -> reload()).start();
        SwingUtilities.invokeLater(this::reload);
    }

    private void setupTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setRowHeight(24);
        table.setFillsViewportHeight(true);
        int[] widths = {40, 120, 60, 180, 120, 150, 90, 100, 90, 120};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        DefaultTableCellRenderer renderer = new DefaultTableCellRenderer() {
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                                    boolean focused, int row, int column) {
                java.awt.Component c = super.getTableCellRendererComponent(t, value, selected, focused, row, column);
                setHorizontalAlignment(column == 0 ? RIGHT : LEFT);
                if (!selected) {
                    boolean active = model.get(t.convertRowIndexToModel(row)).isActive();
                    c.setForeground(active ? t.getForeground() : CANCELLED_COLOR);
                }
                return c;
            }
        };
        table.setDefaultRenderer(Object.class, renderer);
        table.setDefaultRenderer(Long.class, renderer);
        table.setDefaultRenderer(String.class, renderer);
        table.getSelectionModel().addListSelectionListener(e ->
                cancelButton.setEnabled(selected().map(Booking::isActive).orElse(false)));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selected().ifPresent(b -> Dialogs.info(BookingsPanel.this, details(b)));
                }
            }
        });
    }

    /** Перечитывает записи и мастеров из хранилища. */
    public void reload() {
        if (loading) {
            return;
        }
        loading = true;
        Background.run(
                () -> new Loaded(repository.getAllBookings(), repository.getMasters()),
                loaded -> {
                    loading = false;
                    all = loaded.bookings();
                    updateMasters(loaded.masters());
                    applyFilter();
                },
                e -> {
                    loading = false;
                    status.setText("⚠ " + Dialogs.storageMessage(e).replace('\n', ' '));
                });
    }

    private record Loaded(List<Booking> bookings, List<Master> masters) {
    }

    private void updateMasters(List<Master> masters) {
        Object current = master.getSelectedItem();
        DefaultComboBoxModel<String> items = new DefaultComboBoxModel<>();
        items.addElement(BookingFilter.ALL_MASTERS);
        masters.forEach(m -> items.addElement(m.getName()));
        if (current != null && items.getIndexOf(current) < 0) {
            items.addElement(current.toString());
        }
        if (!sameItems(items)) {
            master.setModel(items);
            master.setSelectedItem(current == null ? BookingFilter.ALL_MASTERS : current);
        }
    }

    private boolean sameItems(DefaultComboBoxModel<String> items) {
        if (items.getSize() != master.getItemCount()) {
            return false;
        }
        for (int i = 0; i < items.getSize(); i++) {
            if (!Objects.equals(items.getElementAt(i), master.getItemAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void applyFilter() {
        long selectedId = selected().map(Booking::getId).orElse(-1L);
        BookingFilter filter = new BookingFilter((BookingFilter.Period) period.getSelectedItem(),
                (String) master.getSelectedItem(), showCancelled.isSelected(), search.getText());
        List<Booking> rows = filter.apply(all, LocalDateTime.now(clock));
        model.setRows(rows);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getId() == selectedId) {
                int viewRow = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(viewRow, viewRow);
                break;
            }
        }
        long active = rows.stream().filter(Booking::isActive).count();
        status.setText("Показано: " + rows.size() + " (активных " + active + ")   ·   Всего в файле: " + all.size()
                + "   ·   Обновлено в " + LocalTime.now(clock).format(UPDATED));
    }

    private Optional<Booking> selected() {
        int row = table.getSelectedRow();
        return row < 0 ? Optional.empty() : Optional.of(model.get(table.convertRowIndexToModel(row)));
    }

    private void openNewBookingDialog() {
        Background.run(
                () -> new NewBookingDialog.Catalog(bookingService.services(), bookingService.activeMasters()),
                catalog -> {
                    if (catalog.services().isEmpty() || catalog.masters().isEmpty()) {
                        Dialogs.error(this, "В Excel-файле нет услуг или активных мастеров.");
                        return;
                    }
                    NewBookingDialog dialog = new NewBookingDialog(SwingUtilities.getWindowAncestor(this),
                            bookingService, catalog);
                    dialog.setVisible(true);
                    dialog.result().ifPresent(b -> {
                        reload();
                        Dialogs.info(this, "Запись №" + b.getId() + " сохранена.\n\n" + details(b));
                    });
                },
                e -> Dialogs.error(this, Dialogs.storageMessage(e)));
    }

    private void cancelSelected() {
        Optional<Booking> selected = selected();
        if (selected.isEmpty() || !selected.get().isActive()) {
            return;
        }
        Booking b = selected.get();
        boolean notify = b.getTelegramId() != 0;
        String question = "Отменить запись №" + b.getId() + "?\n\n" + details(b)
                + (notify ? "\n\nКлиент получит уведомление в Telegram." : "");
        if (!Dialogs.confirm(this, question)) {
            return;
        }
        Background.run(
                () -> {
                    Optional<Booking> cancelled = repository.cancelBooking(b.getId());
                    boolean delivered = cancelled.isPresent() && notify && messenger.send(b.getTelegramId(),
                            Texts.salonCancellationNotice(b)) != null;
                    return new CancelResult(cancelled.isPresent(), delivered);
                },
                result -> {
                    reload();
                    if (!result.cancelled()) {
                        Dialogs.info(this, "Запись №" + b.getId() + " уже была отменена.");
                    } else if (notify && !result.delivered()) {
                        Dialogs.info(this, "Запись отменена, но уведомление клиенту отправить не удалось.\n"
                                + "Свяжитесь с клиентом по телефону " + b.getPhone() + ".");
                    }
                },
                e -> Dialogs.error(this, Dialogs.storageMessage(e)));
    }

    private record CancelResult(boolean cancelled, boolean delivered) {
    }

    private void openExcelFile() {
        if (!Dialogs.confirm(this, "Файл откроется в Excel только для просмотра.\n\n"
                + "Не сохраняйте его, пока бот работает: Excel может затереть новые записи,\n"
                + "а на Windows бот не сможет записывать, пока файл открыт.\n\nОткрыть?")) {
            return;
        }
        try {
            Desktop.getDesktop().open(properties.excelFile().toFile());
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException e) {
            Dialogs.error(this, "Не удалось открыть файл: " + e.getMessage());
        }
    }

    private static String details(Booking b) {
        return Texts.adminDetails(b) + "\nСтатус: " + b.getStatus().title();
    }
}
