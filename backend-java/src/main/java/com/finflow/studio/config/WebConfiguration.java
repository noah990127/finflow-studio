package com.finflow.studio.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

@Configuration
public class WebConfiguration {

    @Bean
    CorsFilter corsFilter() {
        var config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Last-Event-ID", "Content-Disposition", "Content-Range", "Accept-Ranges"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return new CorsFilter(source);
    }

    @Bean
    OncePerRequestFilter securityHeaders() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws java.io.IOException, jakarta.servlet.ServletException {
                response.setHeader("X-Content-Type-Options", "nosniff");
                response.setHeader("Referrer-Policy", "same-origin");
                response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
                var uri = request.getRequestURI();
                var embeddablePreview = uri.endsWith("/rendered-preview")
                        || (uri.startsWith("/api/deliverables/") && uri.endsWith("/content"));
                if (embeddablePreview) {
                    response.setHeader("X-Frame-Options", "SAMEORIGIN");
                    response.setHeader("Content-Security-Policy",
                            "default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'self'");
                } else {
                    response.setHeader("X-Frame-Options", "DENY");
                    response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
                }
                filterChain.doFilter(request, response);
            }
        };
    }
}
