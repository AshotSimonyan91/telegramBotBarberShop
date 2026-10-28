package com.salon.bot.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ApiTokenFilter без Spring-контекста. */
class ApiTokenFilterTest {

    private final ApiTokenFilter filter = new ApiTokenFilter("s3cret-token", new ObjectMapper());

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    private MockHttpServletRequest request(String method, String uri, String auth) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRequestURI(uri);
        if (auth != null) {
            r.addHeader("Authorization", auth);
        }
        return r;
    }

    @Test
    void blankTokenRejectedAtConstruction() {
        assertThatThrownBy(() -> new ApiTokenFilter("", new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiTokenFilter("  ", new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ApiTokenFilter(null, new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isAuthorizedVariants() {
        assertThat(filter.isAuthorized("Bearer s3cret-token")).isTrue();
        assertThat(filter.isAuthorized("bearer s3cret-token")).isTrue();
        assertThat(filter.isAuthorized("BEARER   s3cret-token  ")).isTrue();
        assertThat(filter.isAuthorized("Bearer s3cret-toke")).isFalse();
        assertThat(filter.isAuthorized("Bearer S3CRET-TOKEN")).as("токен чувствителен к регистру").isFalse();
        assertThat(filter.isAuthorized("Bearer ")).isFalse();
        assertThat(filter.isAuthorized("Bearer")).isFalse();
        assertThat(filter.isAuthorized("s3cret-token")).isFalse();
        assertThat(filter.isAuthorized(null)).isFalse();
        assertThat(filter.isAuthorized("")).isFalse();
        assertThat(filter.isAuthorized("Bearer s3cret-token extra")).isFalse();
    }

    @Test
    void unauthorizedResponseFormat() throws Exception {
        MockHttpServletResponse r = run(request("GET", "/api/v1/bookings", null));
        assertThat(r.getStatus()).isEqualTo(401);
        assertThat(r.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(r.getContentType()).startsWith("application/json");
        assertThat(r.getContentAsString()).contains("\"error\":\"unauthorized\"").doesNotContain("s3cret");
    }

    @Test
    void optionsAndHealthGetSkipFilter() {
        assertThat(filter.shouldNotFilter(request("OPTIONS", "/api/v1/bookings", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("GET", "/api/v1/health", null))).isTrue();
        assertThat(filter.shouldNotFilter(request("POST", "/api/v1/health", null))).isFalse();
        assertThat(filter.shouldNotFilter(request("GET", "/api/v1/health/", null))).isFalse();
        assertThat(filter.shouldNotFilter(request("GET", "/api/v1/healthz", null))).isFalse();
        assertThat(filter.shouldNotFilter(request("GET", "/api/v1/bookings", null))).isFalse();
    }

    @Test
    void contextPathIsStrippedForHealth() {
        MockHttpServletRequest r = request("GET", "/app/api/v1/health", null);
        r.setContextPath("/app");
        assertThat(filter.shouldNotFilter(r)).isTrue();
    }
}
