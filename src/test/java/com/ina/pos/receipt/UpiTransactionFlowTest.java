package com.ina.pos.receipt;

import com.ina.pos.receipt.config.UpiPaymentProperties;
import com.ina.pos.receipt.dao.UpiTransactionRepository;
import com.ina.pos.receipt.dao.entity.UpiTransaction;
import com.ina.pos.receipt.dto.ApiResponse;
import com.ina.pos.receipt.dto.GeneratePaymentQrRequest;
import com.ina.pos.receipt.dto.SendPaymentLinkRequest;
import com.ina.pos.receipt.exception.CommonValidationException;
import com.ina.pos.receipt.service.EmailPaymentVerificationService;
import com.ina.pos.receipt.service.UpiPaymentService;
import com.ina.pos.receipt.service.WhatsAppService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class UpiTransactionFlowTest {

    @Mock
    private UpiTransactionRepository upiTransactionRepository;

    @Mock
    private EmailPaymentVerificationService emailVerificationService;

    @Mock
    private WhatsAppService whatsAppService;

    private UpiPaymentProperties upiProperties;
    private UpiPaymentService upiPaymentService;

    @BeforeEach
    void setUp() {
        upiProperties = new UpiPaymentProperties();
        upiProperties.setMerchantVpa("merchant@upi");
        upiProperties.setMerchantName("INA Store");
        upiProperties.setBaseUrl("http://localhost:8079");
        upiProperties.setTimeoutSeconds(180);

        upiPaymentService = new UpiPaymentService(
                upiTransactionRepository,
                upiProperties,
                emailVerificationService,
                whatsAppService
        );
    }

    @Test
    @DisplayName("Validation: Missing fields throw 400 Bad Request")
    void testMissingFieldsValidation() {
        // Missing payer name
        SendPaymentLinkRequest req1 = new SendPaymentLinkRequest();
        req1.setMobileNumber("9876543210");
        req1.setTotalAmount("10.00");
        CommonValidationException ex1 = assertThrows(CommonValidationException.class, () ->
                upiPaymentService.initiatePayment(req1));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex1.getError());

        // Missing mobile number
        SendPaymentLinkRequest req2 = new SendPaymentLinkRequest();
        req2.setPayerName("Anand");
        req2.setTotalAmount("10.00");
        CommonValidationException ex2 = assertThrows(CommonValidationException.class, () ->
                upiPaymentService.initiatePayment(req2));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex2.getError());

        // Missing total amount
        SendPaymentLinkRequest req3 = new SendPaymentLinkRequest();
        req3.setPayerName("Anand");
        req3.setMobileNumber("9876543210");
        CommonValidationException ex3 = assertThrows(CommonValidationException.class, () ->
                upiPaymentService.initiatePayment(req3));
        assertEquals(HttpStatus.BAD_REQUEST.value(), ex3.getError());
    }

    @Test
    @DisplayName("Initiate Payment: Creates record in upi_transactions and generates UPI URI and payment link")
    void testInitiatePaymentSuccess() {
        SendPaymentLinkRequest request = new SendPaymentLinkRequest();
        request.setPayerName("Anand Sharma");
        request.setMobileNumber("9876543210");
        request.setTotalAmount("100.50");
        request.setDeviceId("POS-WEB");
        request.setMid("MID001");
        request.setTid("TID001");

        ApiResponse response = upiPaymentService.initiatePayment(request);

        assertNotNull(response);
        assertEquals("success", response.getStatus());
        assertEquals("PENDING", response.getPaymentStatus());
        assertNotNull(response.getOrderId());
        assertTrue(response.getOrderId().startsWith("ORD"));
        assertNotNull(response.getPaymentLink());
        assertTrue(response.getPaymentLink().contains("/upi-payment.html?orderId="));
        assertTrue(response.getQrString().startsWith("upi://pay?pa=merchant@upi"));

        // Verify database persistence
        ArgumentCaptor<UpiTransaction> captor = ArgumentCaptor.forClass(UpiTransaction.class);
        verify(upiTransactionRepository, times(1)).save(captor.capture());

        UpiTransaction saved = captor.getValue();
        assertEquals("PENDING", saved.getOrderStatus());
        assertEquals(new BigDecimal("100.50"), saved.getAmount());
        assertEquals("Anand Sharma", saved.getPaymentFor());
        assertEquals("UPI", saved.getPaymentMode());
        assertEquals("INR", saved.getPaymentCurrency());
        assertEquals("TID001", saved.getTid());
        assertEquals("000001", saved.getBatchNo());
        assertEquals("POS-WEB", saved.getDeviceId());
        assertNotNull(saved.getPaymentTime());

        // Verify WhatsApp notification was triggered
        verify(whatsAppService, times(1)).sendPaymentLink(eq(request), any(), any(), any());
    }

    @Test
    @DisplayName("Status Check: Payment SUCCESS confirmed via email alert")
    void testStatusSuccessFromEmail() {
        String orderId = "ORD_TEST_SUCCESS_01";
        UpiTransaction existing = UpiTransaction.builder()
                .orderId(orderId)
                .amount(new BigDecimal("50.00"))
                .paymentFor("Rahul")
                .orderStatus("PENDING")
                .paymentTime(Timestamp.valueOf(LocalDateTime.now()))
                .build();

        when(upiTransactionRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));
        when(emailVerificationService.checkPaymentStatus(existing)).thenReturn(
                EmailPaymentVerificationService.VerificationResult.success(
                        "UTR4455667788",
                        "TXN112233",
                        "customer@okhdfc",
                        "Payment confirmed via bank email alert"
                )
        );

        ApiResponse response = upiPaymentService.getPaymentStatus(orderId);

        assertEquals("SUCCESS", response.getStatus());
        assertEquals("SUCCESS", response.getPaymentStatus());
        assertEquals("UTR4455667788", response.getBankReference());
        assertEquals("TXN112233", response.getTransactionId());

        // Verify DB update
        verify(upiTransactionRepository, times(1)).save(existing);
        assertEquals("SUCCESS", existing.getOrderStatus());
        assertEquals("UTR4455667788", existing.getBankReference());
        assertEquals("customer@okhdfc", existing.getPayerVpa());
    }

    @Test
    @DisplayName("Status Check: 3-minute timeout with no confirmation marks payment as FAILED")
    void testThreeMinuteTimeoutMarksPaymentFailed() {
        String orderId = "ORD_TEST_TIMEOUT_01";
        UpiTransaction existing = UpiTransaction.builder()
                .orderId(orderId)
                .amount(new BigDecimal("75.00"))
                .paymentFor("Suresh")
                .orderStatus("PENDING")
                .paymentTime(Timestamp.valueOf(LocalDateTime.now().minusSeconds(185))) // > 3 minutes old
                .build();

        when(upiTransactionRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));
        when(emailVerificationService.checkPaymentStatus(existing)).thenReturn(
                EmailPaymentVerificationService.VerificationResult.failed(
                        "Payment timed out after 3 minutes - no confirmation received"
                )
        );

        ApiResponse response = upiPaymentService.getPaymentStatus(orderId);

        assertEquals("FAILED", response.getStatus());
        assertEquals("FAILED", response.getPaymentStatus());
        verify(upiTransactionRepository, times(1)).save(existing);
        assertEquals("FAILED", existing.getOrderStatus());
    }

    @Test
    @DisplayName("Status Check: Non-existent order returns 404 NOT FOUND")
    void testOrderNotFound() {
        when(upiTransactionRepository.findByOrderId("NON_EXISTENT")).thenReturn(Optional.empty());

        CommonValidationException ex = assertThrows(CommonValidationException.class, () ->
                upiPaymentService.getPaymentStatus("NON_EXISTENT"));

        assertEquals(HttpStatus.NOT_FOUND.value(), ex.getError());
    }

    @Test
    @DisplayName("Generate Payment QR: Generates UPI QR payload, base64 image, and persists PENDING transaction")
    void testGeneratePaymentQrSuccess() {
        GeneratePaymentQrRequest request = GeneratePaymentQrRequest.builder()
                .payerName("Vikram")
                .totalAmount("150.00")
                .deviceId("POS-TERM-01")
                .mid("MID_INA_01")
                .tid("TID_INA_01")
                .build();

        ApiResponse response = upiPaymentService.generatePaymentQr(request);

        assertNotNull(response);
        assertEquals("success", response.getStatus());
        assertEquals("PENDING", response.getPaymentStatus());
        assertNotNull(response.getOrderId());
        assertTrue(response.getOrderId().startsWith("ORD"));
        assertTrue(response.getQrString().startsWith("upi://pay?pa=merchant@upi"));
        assertNotNull(response.getQrCodeBase64());
        assertTrue(response.getQrCodeBase64().startsWith("data:image/png;base64,"));
        assertNotNull(response.getQrImageUrl());
        assertTrue(response.getQrImageUrl().endsWith(".png"));

        // Verify database persistence
        ArgumentCaptor<UpiTransaction> captor = ArgumentCaptor.forClass(UpiTransaction.class);
        verify(upiTransactionRepository, atLeastOnce()).save(captor.capture());

        UpiTransaction saved = captor.getValue();
        assertEquals("PENDING", saved.getOrderStatus());
        assertEquals(new BigDecimal("150.00"), saved.getAmount());
        assertEquals("Vikram", saved.getPaymentFor());
        assertEquals("POS-TERM-01", saved.getDeviceId());
        assertEquals("MID_INA_01", saved.getMid());
        assertEquals("TID_INA_01", saved.getTid());
        assertEquals("000001", saved.getBatchNo());
    }

    @Test
    @DisplayName("Get QR Image Bytes: Generates valid PNG byte stream for order")
    void testGetQrImageBytesForOrder() {
        String orderId = "ORD_QR_TEST_01";
        UpiTransaction existing = UpiTransaction.builder()
                .orderId(orderId)
                .amount(new BigDecimal("200.00"))
                .paymentFor("Anita")
                .orderStatus("PENDING")
                .build();

        when(upiTransactionRepository.findByOrderId(orderId)).thenReturn(Optional.of(existing));

        byte[] qrBytes = upiPaymentService.getQrImageBytesForOrder(orderId);
        assertNotNull(qrBytes);
        assertTrue(qrBytes.length > 0);
        // Verify PNG magic bytes 0x89 'P' 'N' 'G'
        assertEquals((byte) 0x89, qrBytes[0]);
        assertEquals((byte) 0x50, qrBytes[1]);
        assertEquals((byte) 0x4E, qrBytes[2]);
        assertEquals((byte) 0x47, qrBytes[3]);
    }
}
