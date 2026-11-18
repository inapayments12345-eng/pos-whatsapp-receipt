package com.ina.pos.receipt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "upi.payment")
public class UpiPaymentProperties {

    /**
     * Merchant UPI ID / VPA (e.g. yourname@okhdfcbank, merchant@upi)
     */
    private String merchantVpa = "merchant@upi";

    /**
     * Merchant Business Name to show in UPI apps (e.g. INA POS Store)
     */
    private String merchantName = "INA POS Store";

    /**
     * Merchant ID (MID)
     */
    private String merchantId = "MERCHANT001";

    /**
     * Base URL of the application for generating payment links (e.g. http://localhost:8079 or ngrok URL)
     */
    private String baseUrl = "http://localhost:8079";

    /**
     * Payment timeout in seconds (Default 180 seconds / 3 minutes)
     */
    private int timeoutSeconds = 180;
}
