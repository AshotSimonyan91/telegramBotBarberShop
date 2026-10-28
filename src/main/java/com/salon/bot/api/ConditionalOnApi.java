package com.salon.bot.api;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Бин REST API: создаётся только в веб-приложении и при {@code api.enabled} не равно «false» (по умолчанию включено).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Conditional(ApiEnabledCondition.class)
public @interface ConditionalOnApi {
}
