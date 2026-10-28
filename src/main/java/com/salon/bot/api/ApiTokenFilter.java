package com.salon.bot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Проверяет заголовок {@code Authorization: Bearer <API_TOKEN>} для всех запросов,
 * кроме {@code GET /api/v1/health} и CORS preflight (OPTIONS).
 * Токены сравниваются за постоянное время: сравниваются SHA-256 хэши через {@link MessageDigest#isEqual}.
 */
@Slf4j
public class ApiTokenFilter extends OncePerRequestFilter {

    static final String HEALTH_PATH = ApiPaths.BASE + "/health";
    private static final String BEARER = "Bearer ";

    private final byte[] expectedHash;
    private final ObjectMapper objectMapper;

    public ApiTokenFilter(String token, ObjectMapper objectMapper) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("API token must not be blank");
        }
        this.expectedHash = sha256(token);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        return HttpMethod.GET.matches(request.getMethod()) && HEALTH_PATH.equals(pathOf(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }
        log.warn("API: отклонён запрос без действительного токена: {} {}", request.getMethod(), pathOf(request));
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), new ApiError(ApiError.UNAUTHORIZED,
                "Нужна авторизация: укажите действительный токен доступа."));
    }

    boolean isAuthorized(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return false;
        }
        String provided = header.substring(BEARER.length()).trim();
        return !provided.isEmpty() && MessageDigest.isEqual(expectedHash, sha256(provided));
    }

    private static String pathOf(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        return context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
