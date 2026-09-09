package com.finflow.studio.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;

@Component
public class AuthenticationFilter extends OncePerRequestFilter {
    private final SessionTokenService tokens;
    private final FixedAccountService accounts;
    private final boolean enabled;

    public AuthenticationFilter(SessionTokenService tokens, FixedAccountService accounts,
                                @Value("${finflow.auth.enabled:true}") boolean enabled) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var uri = request.getRequestURI();
        return !enabled || !uri.startsWith("/api/") || "OPTIONS".equals(request.getMethod()) || uri.startsWith("/actuator/")
                || uri.startsWith("/internal/") || uri.equals("/api/auth/login")
                || uri.equals("/api/auth/logout") || isOfficeCallback(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var token = request.getCookies() == null ? null : Arrays.stream(request.getCookies())
                .filter(cookie -> AuthController.COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
        String actor;
        try {
            actor = token == null ? null : tokens.verify(token);
        } catch (Exception exception) {
            unauthorized(response);
            return;
        }
        if (actor == null || !accounts.exists(actor)) {
            unauthorized(response);
            return;
        }
        try (var ignored = ActorContext.bind(actor)) {
            chain.doFilter(request, response);
        }
    }

    private boolean isOfficeCallback(String uri) {
        return uri.matches("/api/office/(files|deliverables)/[^/]+/callback");
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"登录状态已失效，请重新登录\",\"timestamp\":\""
                + Instant.now() + "\"}");
    }
}
