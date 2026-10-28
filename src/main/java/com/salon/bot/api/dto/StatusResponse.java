package com.salon.bot.api.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/** Ответ GET /api/v1/status. */
public record StatusResponse(
        Bot bot,
        Storage storage,
        @JsonFormat(pattern = ApiFormats.DATE_TIME) LocalDateTime time
) {

    public record Bot(boolean running, String username) {
    }

    public record Storage(boolean ok, String file) {
    }
}
