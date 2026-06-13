package com.emailmanager.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configuration.
 *
 * Phase 1.2 security fix:
 *  - Removed allowCredentials(true) + wildcard origin (incompatible & dangerous)
 *  - Explicit allowed-origins list, configurable via app.cors.allowed-origins
 *  - Default origins cover the mobile app (no browser origin) and local dev
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** Comma-separated list of allowed origins. Override in application.yml */
    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:8080}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type", "Accept")
                .maxAge(3600);
        // Note: allowCredentials NOT set — the mobile app uses Bearer tokens,
        // not cookies, so credentials mode is not needed.
    }
}
