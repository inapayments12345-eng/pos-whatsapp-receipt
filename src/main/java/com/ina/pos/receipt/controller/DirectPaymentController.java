package com.ina.pos.receipt.controller;

import com.ina.pos.receipt.dto.ApiResponse;
import com.ina.pos.receipt.dto.GeneratePaymentQrRequest;
import com.ina.pos.receipt.dto.SendPaymentLinkRequest;
import com.ina.pos.receipt.dto.UpiTransactionSearchRequest;
import com.ina.pos.receipt.dto.UpiTransactions;
import com.ina.pos.receipt.exception.CommonValidationException;
import com.ina.pos.receipt.service.EmailPaymentVerificationService;
import com.ina.pos.receipt.service.TxnReceiptService;
import com.ina.pos.receipt.service.UpiPaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DirectPaymentController {

    private final TxnReceiptService txnReceiptService;
    private final EmailPaymentVerificationService emailVerificationService;
    private final UpiPaymentService upiPaymentService;

    @PostMapping("/whatsapp/sendPaymentLink")
    public ResponseEntity<ApiResponse> sendPaymentLinkDirect(@Valid @RequestBody SendPaymentLinkRequest request) {
        log.info("[Root Path] Send upi payment link for payer: {}, mobile: {}", request.getPayerName(), request.getMobileNumber());

        try {
            ApiResponse response = txnReceiptService.sendPaymentLink(request);
            return ResponseEntity.ok(response);
        } catch (CommonValidationException cve) {
            throw cve;
        } catch (Exception e) {
            log.error("Failed to send upi payment link: {}", e.getMessage(), e);
            throw new CommonValidationException("Failed to initiate payment: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    /**
     * POST /generatePaymentQR
     * Generates a UPI payment QR for a POS machine.
     * The POS displays the QR code, the customer scans and pays,
     * and the POS polls GET /payment/status?orderId={orderId} to confirm payment.
     */
    @PostMapping({"/generatePaymentQR", "/payment/generatePaymentQR"})
    public ResponseEntity<ApiResponse> generatePaymentQr(@Valid @RequestBody GeneratePaymentQrRequest request) {
        log.info("[Root Path] Generate UPI payment QR for POS. Payer: {}, Amount: {}, Device: {}, TID: {}",
                request.getPayerName(), request.getTotalAmount(), request.getDeviceId(), request.getTid());

        try {
            ApiResponse response = txnReceiptService.generatePaymentQr(request);
            return ResponseEntity.ok(response);
        } catch (CommonValidationException cve) {
            throw cve;
        } catch (Exception e) {
            log.error("Failed to generate UPI payment QR: {}", e.getMessage(), e);
            throw new CommonValidationException("Failed to generate payment QR: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    /**
     * GET /qr/{orderId} or /qr/{orderId}.png
     * Serves dynamic QR code PNG image bytes directly for the POS device screen.
     */
    @GetMapping(value = {"/qr/{orderId}", "/qr/{orderId}.png"}, produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getPaymentQrImageDirect(@PathVariable("orderId") String orderId) {
        byte[] qrBytes = txnReceiptService.getPaymentQrImage(orderId);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(qrBytes);
    }

    /**
     * Serves dynamic upi-payment.html payment page for any subpath requested by WhatsApp button
     * (e.g. /upi-payment.html, /upi-payment.html/{orderId}, /upi-payment.html/paynow)
     */
    @GetMapping(value = {"/upi-payment.html", "/upi-payment.html/**", "/paynow", "/paynow/**"}, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<byte[]> serveUpiPaymentPageDirect(jakarta.servlet.http.HttpServletRequest request) {
        String uri = (request != null && request.getRequestURI() != null) ? request.getRequestURI().toLowerCase() : "";
        if (uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".png") || uri.endsWith(".jpg") || uri.endsWith(".ico") || uri.endsWith(".map")) {
            return ResponseEntity.notFound().build();
        }

        try {
            org.springframework.core.io.ClassPathResource resource =
                    new org.springframework.core.io.ClassPathResource("static/upi-payment.html");
            byte[] htmlBytes = resource.getInputStream().readAllBytes();
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(htmlBytes);
        } catch (Exception ex) {
            log.error("Failed to serve upi-payment.html: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping({"/payment/latest-pending", "/api/v1/receipt/payment/latest-pending"})
    public ResponseEntity<ApiResponse> getLatestPendingTransactionDirect() {
        return ResponseEntity.ok(upiPaymentService.getLatestPendingTransaction());
    }

    @GetMapping({
            "/payment/status",
            "/payment/status/{orderId}",
            "/whatsapp/paymentStatus/{orderId}"
    })
    public ResponseEntity<ApiResponse> getPaymentStatusDirect(
            @PathVariable(value = "orderId", required = false) String pathOrderId,
            @RequestParam(value = "orderId", required = false) String queryOrderId) {

        String orderId = pathOrderId != null ? pathOrderId : queryOrderId;
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new CommonValidationException("Order ID is required", HttpStatus.BAD_REQUEST.value());
        }

        ApiResponse response = txnReceiptService.getPaymentStatus(orderId.trim());
        return ResponseEntity.ok(response);
    }

    /**
     * Diagnostic endpoint to verify live Gmail IMAP connectivity and trigger status.
     * Accessible at: GET /payment/mail-test or GET /api/v1/receipt/payment/mail-test
     */
    @GetMapping({"/payment/mail-test", "/api/v1/receipt/payment/mail-test"})
    public ResponseEntity<Map<String, Object>> testMailConnection() {
        return ResponseEntity.ok(emailVerificationService.testEmailConnection());
    }

    @PostMapping("/searchUpiTransactions")
    public ResponseEntity<UpiTransactions> searchTransactions(@Valid @RequestBody UpiTransactionSearchRequest request) {
        Pageable pageable = PageRequest.of(request.getPageNumber(), request.getPageSize());
        return ResponseEntity.ok(upiPaymentService.searchUpiTransactions(request, pageable));
    }
}
