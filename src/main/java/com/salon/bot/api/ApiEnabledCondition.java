package com.salon.bot.api;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** {@code api.enabled}: выключено только значением «false»; пусто или не задано — включено. */
class ApiEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("api.enabled");
        return value == null || !value.trim().equalsIgnoreCase("false");
    }
}
