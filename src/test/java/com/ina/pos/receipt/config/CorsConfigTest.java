package com.ina.pos.receipt.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CorsConfigTest {

    private CorsFilter corsFilter;
    private CorsProperties corsProperties;

    @BeforeEach
    void setUp() {
        corsProperties = new CorsProperties();
        CorsConfig corsConfig = new CorsConfig(corsProperties);
        corsFilter = corsConfig.corsFilter();
    }

    @Test
    @DisplayName("Preflight: Trusted TMS portal origin (http://localhost:3000) receives 200 OK with proper CORS headers")
    void testPreflightTrustedOriginLocalhost() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/receipt/send");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        corsFilter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
        assertEquals("http://localhost:3000", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertNotNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        assertTrue(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).contains("POST"));
        assertTrue(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS).contains("OPTIONS"));
        assertNotNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
        assertEquals("3600", response.getHeader(HttpHeaders.ACCESS_CONTROL_MAX_AGE));

        // Ensure no duplicate Access-Control-Allow-Origin headers
        List<String> originHeaders = response.getHeaders(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
        assertEquals(1, originHeaders.size(), "Should have exactly 1 Access-Control-Allow-Origin header");
    }

    @Test
    @DisplayName("Preflight: Ngrok trusted origin receives 200 OK with credentials and matching origin (no wildcard *)")
    void testPreflightTrustedOriginNgrok() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/searchUpiTransactions");
        request.addHeader(HttpHeaders.ORIGIN, "https://com.ina.spos.receipt.ngrok.dev");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        corsFilter.doFilter(request, response, filterChain);

        assertEquals(200, response.getStatus());
        assertEquals("https://com.ina.spos.receipt.ngrok.dev", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertNotEquals("*", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN), "Must never be wildcard * when credentials are true");
    }

    @Test
    @DisplayName("Preflight: Untrusted origin is rejected without Access-Control-Allow-Origin header")
    void testPreflightUntrustedOriginRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/searchUpiTransactions");
        request.addHeader(HttpHeaders.ORIGIN, "https://malicious-website.com");
        request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        corsFilter.doFilter(request, response, filterChain);

        assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN),
                "Untrusted origin must not receive Access-Control-Allow-Origin header");
    }

    @Test
    @DisplayName("Actual Request: POST from TMS portal receives CORS headers and continues filter chain")
    void testActualRequestFromTmsPortal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/searchUpiTransactions");
        request.addHeader(HttpHeaders.ORIGIN, "http://localhost:3000");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        corsFilter.doFilter(request, response, filterChain);

        assertEquals("http://localhost:3000", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertEquals("true", response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        assertNotNull(filterChain.getRequest(), "Actual request must proceed down the filter chain");
    }

    @Test
    @DisplayName("Android POS APK: Request without Origin header passes through untouched with zero CORS headers")
    void testAndroidApkRequestPassesThroughUntouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/receipt/whatsapp/sendPosReceipt");
        // Android APK does NOT send Origin header

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain filterChain = new MockFilterChain();

        corsFilter.doFilter(request, response, filterChain);

        assertNull(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        assertNotNull(filterChain.getRequest(), "POS Android APK request must proceed normally without CORS interference");
    }
}
