package com.salon.desktop;

import com.salon.bot.handler.Texts;
import com.salon.bot.model.Booking;

import javax.swing.table.AbstractTableModel;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Строки таблицы записей.
 */
class BookingTableModel extends AbstractTableModel {

    private static final String[] COLUMNS = {
            "№", "Дата", "Время", "Клиент", "Телефон", "Услуга", "Мастер", "Статус", "Источник", "Создана"
    };
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private List<Booking> rows = List.of();

    void setRows(List<Booking> rows) {
        this.rows = rows;
        fireTableDataChanged();
    }

    Booking get(int row) {
        return rows.get(row);
    }

    @Override
    public int getRowCount() {
        return rows.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return column == 0 ? Long.class : String.class;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Booking b = rows.get(row);
        return switch (column) {
            case 0 -> b.getId();
            case 1 -> Texts.fullDate(b.getDate());
            case 2 -> Texts.time(b.getTime());
            case 3 -> b.getUsername() == null || b.getUsername().isBlank()
                    ? b.getClientName() : b.getClientName() + " (@" + b.getUsername() + ")";
            case 4 -> b.getPhone();
            case 5 -> b.getServiceName();
            case 6 -> b.getMasterName();
            case 7 -> b.getStatus().title();
            case 8 -> b.getSource();
            case 9 -> b.getCreatedAt() == null ? "" : b.getCreatedAt().format(CREATED);
            default -> "";
        };
    }
}
