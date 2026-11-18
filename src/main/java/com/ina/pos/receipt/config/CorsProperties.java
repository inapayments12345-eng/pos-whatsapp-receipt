package com.ina.pos.receipt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Centralized CORS configuration properties for the POS WhatsApp Receipt service.
 * Allows trusted origins such as the TMS web portal, local development environments,
 * and ngrok tunnels without exposing wildcard origins or modifying controllers.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "cors")
public class CorsProperties {

    /**
     * Explicit list of allowed origins.
     * Initialized with the actual project URLs (ngrok base URL, server port)
     * and common TMS web portal frontend development ports.
     */
    private List<String> allowedOrigins = new ArrayList<>(Arrays.asList(
            "https://com.ina.spos.receipt.ngrok.dev",
            "http://localhost:8079",
            "http://localhost:3000",
            "http://localhost:4200",
            "http://localhost:5173",
            "http://localhost:8080",
            "http://127.0.0.1:8079",
            "http://127.0.0.1:3000",
            "http://127.0.0.1:4200",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:8080"
    ));

    /**
     * Allowed origin patterns supporting port/subdomain matching without using wildcard "*".
     * Dynamically echoes the exact requesting origin when credentials are enabled.
     */
    private List<String> allowedOriginPatterns = new ArrayList<>(Arrays.asList(
            "https://*.ngrok.dev",
            "https://*.ngrok-free.app",
            "https://*.ngrok.io",
            "http://localhost:*",
            "http://127.0.0.1:*"
    ));

    /**
     * Supported HTTP methods for TMS portal and API clients.
     */
    private List<String> allowedMethods = new ArrayList<>(Arrays.asList(
            "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
    ));

    /**
     * Supported HTTP headers.
     */
    private List<String> allowedHeaders = new ArrayList<>(Arrays.asList(
            "Authorization",
            "Content-Type",
            "Accept",
            "Origin",
            "X-Requested-With",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers",
            "*"
    ));

    /**
     * Headers exposed to the browser/client.
     */
    private List<String> exposedHeaders = new ArrayList<>(Arrays.asList(
            "Authorization",
            "Content-Disposition"
    ));

    /**
     * Allow credentials (cookies, authorization headers, client certificates).
     */
    private boolean allowCredentials = true;

    /**
     * Maximum age (seconds) for preflight caching.
     */
    private long maxAge = 3600L;
}
