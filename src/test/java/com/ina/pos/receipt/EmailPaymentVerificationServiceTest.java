package com.ina.pos.receipt;

import com.ina.pos.receipt.config.MailReaderProperties;
import com.ina.pos.receipt.config.UpiPaymentProperties;
import com.ina.pos.receipt.dao.UpiTransactionRepository;
import com.ina.pos.receipt.dao.entity.UpiTransaction;
import com.ina.pos.receipt.service.EmailPaymentVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
public class EmailPaymentVerificationServiceTest {

    @Mock
    private UpiTransactionRepository upiTransactionRepository;

    private MailReaderProperties mailProperties;
    private UpiPaymentProperties upiProperties;
    private EmailPaymentVerificationService service;

    @BeforeEach
    void setUp() {
        mailProperties = new MailReaderProperties();
        mailProperties.setHost("smtp.gmail.com"); // Deliberately set to SMTP to test auto-resolution
        mailProperties.setPort(587);
        mailProperties.setUsername("test@gmail.com");
        mailProperties.setPassword("bxua ywae vtdl dddv"); // Deliberately with spaces

        upiProperties = new UpiPaymentProperties();
        upiProperties.setTimeoutSeconds(180); // 3 minutes timeout

        service = new EmailPaymentVerificationService(mailProperties, upiProperties, upiTransactionRepository);
    }

    @Test
    @DisplayName("Timeout Rule: Transaction older than 3 minutes (180s) is marked FAILED")
    void testTransactionExpiresAfterThreeMinutes() {
        UpiTransaction tx = UpiTransaction.builder()
                .orderId("ORD_TIMEOUT_TEST")
                .amount(new BigDecimal("10.00"))
                .paymentTime(Timestamp.valueOf(LocalDateTime.now().minusSeconds(185))) // 3 mins 5 secs ago
                .build();

        EmailPaymentVerificationService.VerificationResult result = service.checkPaymentStatus(tx);

        assertNotNull(result);
        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getMessage().contains("timed out after 3 minutes"));
    }

    @Test
    @DisplayName("Timeout Rule: Transaction younger than 3 minutes remains PENDING")
    void testTransactionPendingWithinThreeMinutes() {
        // Without active connection or dummy creds, returns PENDING if within 3 minutes
        mailProperties.setUsername("");
        mailProperties.setPassword("");

        UpiTransaction tx = UpiTransaction.builder()
                .orderId("ORD_PENDING_TEST")
                .amount(new BigDecimal("10.00"))
                .paymentTime(Timestamp.valueOf(LocalDateTime.now().minusSeconds(60))) // 1 minute ago (well under 3 mins)
                .build();

        EmailPaymentVerificationService.VerificationResult result = service.checkPaymentStatus(tx);

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        assertTrue(result.getMessage().contains("Waiting for payment"));
    }
}
