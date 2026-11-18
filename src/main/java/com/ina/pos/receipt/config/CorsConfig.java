package com.ina.pos.receipt.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Centralized CORS configuration for the Spring Boot application.
 * Resolves CORS errors for requests originating from the TMS web portal
 * without modifying controllers, services, repositories, or DTOs.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CorsConfig {

    private final CorsProperties corsProperties;

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public CorsFilter corsFilter() {
        log.info("Registering centralized CORS filter for TMS web portal and trusted origins");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowCredentials(corsProperties.isAllowCredentials());

        if (corsProperties.getAllowedOrigins() != null && !corsProperties.getAllowedOrigins().isEmpty()) {
            config.setAllowedOrigins(corsProperties.getAllowedOrigins());
        }

        if (corsProperties.getAllowedOriginPatterns() != null && !corsProperties.getAllowedOriginPatterns().isEmpty()) {
            config.setAllowedOriginPatterns(corsProperties.getAllowedOriginPatterns());
        }

        config.setAllowedMethods(corsProperties.getAllowedMethods());
        config.setAllowedHeaders(corsProperties.getAllowedHeaders());
        config.setExposedHeaders(corsProperties.getExposedHeaders());
        config.setMaxAge(corsProperties.getMaxAge());

        // Apply CORS configuration to all existing API endpoints centrally
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
