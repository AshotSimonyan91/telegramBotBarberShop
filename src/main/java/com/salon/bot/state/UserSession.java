package com.salon.bot.state;

import com.salon.bot.model.Master;
import com.salon.bot.model.Service;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Текущий шаг пользователя и собранные данные черновика записи.
 */
@Data
public class UserSession {

    public static final String DEFAULT_SOURCE = "direct";

    private final long chatId;
    private UserState state = UserState.START;
    private String source = DEFAULT_SOURCE;

    private Service service;
    private Master master;
    /** Клиент выбрал «Любой свободный»: мастер подбирается при выборе времени. */
    private boolean anyMaster;
    private LocalDate date;
    private LocalTime time;
    private String clientName;
    private String phone;

    /** Сбрасывает черновик, сохраняя источник. */
    public void resetDraft() {
        state = UserState.START;
        service = null;
        master = null;
        anyMaster = false;
        date = null;
        time = null;
        clientName = null;
        phone = null;
    }
}
