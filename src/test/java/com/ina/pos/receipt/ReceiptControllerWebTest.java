package com.ina.pos.receipt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ina.pos.receipt.config.GlobalExceptionHandler;
import com.ina.pos.receipt.controller.DirectPaymentController;
import com.ina.pos.receipt.controller.ReceiptController;
import com.ina.pos.receipt.dto.ApiResponse;
import com.ina.pos.receipt.dto.SendPaymentLinkRequest;
import com.ina.pos.receipt.service.TxnReceiptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
public class ReceiptControllerWebTest {

    private MockMvc mockMvc;

    @Mock
    private TxnReceiptService txnReceiptService;

    @Mock
    private com.ina.pos.receipt.service.EmailPaymentVerificationService emailVerificationService;

    @Mock
    private com.ina.pos.receipt.service.UpiPaymentService upiPaymentService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReceiptController receiptController = new ReceiptController(txnReceiptService);
        DirectPaymentController directController = new DirectPaymentController(txnReceiptService, emailVerificationService, upiPaymentService);

        mockMvc = MockMvcBuilders.standaloneSetup(receiptController, directController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("POST /whatsapp/sendPaymentLink - Success")
    void testSendPaymentLinkEndpointSuccess() throws Exception {
        SendPaymentLinkRequest request = new SendPaymentLinkRequest();
        request.setPayerName("Anand");
        request.setMobileNumber("9876543210");
        request.setTotalAmount("10.00");
        request.setDeviceId("POS-01");
        request.setMid("MID12345");
        request.setTid("TID12345");

        ApiResponse mockResponse = ApiResponse.builder()
                .status("success")
                .message("Payment link generated and sent via WhatsApp")
                .orderId("ORD1700000000")
                .paymentLink("http://localhost:8079/upi-payment.html?orderId=ORD1700000000&amount=10.00")
                .qrString("upi://pay?pa=merchant@upi&pn=Store&am=10.00&cu=INR&tr=ORD1700000000")
                .paymentStatus("PENDING")
                .build();

        when(txnReceiptService.sendPaymentLink(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/whatsapp/sendPaymentLink")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.orderId").value("ORD1700000000"))
                .andExpect(jsonPath("$.order_id").value("ORD1700000000"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    @DisplayName("GET /api/v1/receipt/payment/status?orderId=... - Success")
    void testGetPaymentStatusEndpoint() throws Exception {
        ApiResponse mockResponse = ApiResponse.builder()
                .status("SUCCESS")
                .paymentStatus("SUCCESS")
                .orderId("ORD12345")
                .bankReference("UTR55667788")
                .build();

        when(txnReceiptService.getPaymentStatus(eq("ORD12345"))).thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/receipt/payment/status")
                        .param("orderId", "ORD12345"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.bankReference").value("UTR55667788"));
    }

    @Test
    @DisplayName("POST /generatePaymentQR - Success")
    void testGeneratePaymentQrEndpointSuccess() throws Exception {
        com.ina.pos.receipt.dto.GeneratePaymentQrRequest request = com.ina.pos.receipt.dto.GeneratePaymentQrRequest.builder()
                .payerName("Suresh")
                .totalAmount("50.00")
                .deviceId("POS-001")
                .mid("MID777")
                .tid("TID888")
                .build();

        ApiResponse mockResponse = ApiResponse.builder()
                .status("success")
                .message("Payment QR generated successfully")
                .orderId("ORD1700000001")
                .qrString("upi://pay?pa=merchant@upi&pn=Store&am=50.00&cu=INR&tr=ORD1700000001")
                .qrCodeBase64("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA...")
                .qrImageUrl("http://localhost:8079/qr/ORD1700000001.png")
                .paymentStatus("PENDING")
                .build();

        when(txnReceiptService.generatePaymentQr(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/generatePaymentQR")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.orderId").value("ORD1700000001"))
                .andExpect(jsonPath("$.qrString").value("upi://pay?pa=merchant@upi&pn=Store&am=50.00&cu=INR&tr=ORD1700000001"))
                .andExpect(jsonPath("$.qrCodeBase64").value("data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAA..."))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
    }

    @Test
    @DisplayName("GET /qr/{orderId} - Success")
    void testGetQrImageEndpoint() throws Exception {
        byte[] dummyBytes = new byte[]{1, 2, 3};
        when(txnReceiptService.getPaymentQrImage("ORD123")).thenReturn(dummyBytes);

        mockMvc.perform(get("/qr/ORD123"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentType(MediaType.IMAGE_PNG));
    }
}
