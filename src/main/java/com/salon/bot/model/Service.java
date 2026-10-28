package com.salon.bot.model;

import lombok.Builder;
import lombok.Value;

/**
 * Услуга салона (лист «Услуги»).
 */
@Value
@Builder
public class Service {
    String name;
    int durationMinutes;
    int price;
}
