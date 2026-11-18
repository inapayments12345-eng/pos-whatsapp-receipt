package com.ina.pos.receipt.service;

import com.ina.pos.receipt.config.UpiPaymentProperties;
import com.ina.pos.receipt.dao.UpiTransactionRepository;
import com.ina.pos.receipt.dao.entity.UpiTransaction;
import com.ina.pos.receipt.dto.ApiResponse;
import com.ina.pos.receipt.dto.GeneratePaymentQrRequest;
import com.ina.pos.receipt.dto.SendPaymentLinkRequest;
import com.ina.pos.receipt.dto.UpiTransactionSearchRequest;
import com.ina.pos.receipt.dto.UpiTransactions;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.ina.pos.receipt.aws.service.AmazonS3Service;
import com.ina.pos.receipt.exception.CommonValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Duration;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class UpiPaymentService {

    private final UpiTransactionRepository upiTransactionRepository;
    private final UpiPaymentProperties upiProperties;
    private final EmailPaymentVerificationService emailVerificationService;
    private final WhatsAppService whatsAppService;
    private final Random random = new Random();
    private final Map<String, byte[]> qrImageCache = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private void cacheQrBytes(String orderId, byte[] qrBytes) {
        if (orderId == null || qrBytes == null) return;
        qrImageCache.put(orderId, qrBytes);
        if (redisTemplate != null) {
            try {
                String b64 = Base64.getEncoder().encodeToString(qrBytes);
                redisTemplate.opsForValue().set("qr:" + orderId, b64, Duration.ofMinutes(10));
            } catch (Exception ex) {
                log.warn("Redis QR cache failed for orderId {}: {}", orderId, ex.getMessage());
            }
        }
    }

    private byte[] getCachedQrBytes(String orderId) {
        if (orderId == null) return null;
        byte[] local = qrImageCache.get(orderId);
        if (local != null) {
            return local;
        }
        if (redisTemplate != null) {
            try {
                String b64 = redisTemplate.opsForValue().get("qr:" + orderId);
                if (b64 != null && !b64.isBlank()) {
                    byte[] bytes = Base64.getDecoder().decode(b64);
                    qrImageCache.put(orderId, bytes);
                    return bytes;
                }
            } catch (Exception ex) {
                log.warn("Redis QR fetch failed for orderId {}: {}", orderId, ex.getMessage());
            }
        }
        return null;
    }

    /**
     * Initiate payment:
     * 1. Validate request (payerName, mobileNumber, totalAmount)
     * 2. Generate unique order_id
     * 3. Construct unique UPI URI and web payment link
     * 4. Persist in upi_transactions table with initial PENDING status
     * 5. Send link to customer's WhatsApp (via WhatAPI)
     */
    @Transactional
    public ApiResponse initiatePayment(SendPaymentLinkRequest request) {
        validateRequest(request);

        BigDecimal amount;
        try {
            amount = new BigDecimal(request.getTotalAmount().trim());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CommonValidationException("Amount must be greater than zero", HttpStatus.BAD_REQUEST.value());
            }
        } catch (NumberFormatException ex) {
            throw new CommonValidationException("Invalid amount format: " + request.getTotalAmount(), HttpStatus.BAD_REQUEST.value());
        }

        String orderId = generateOrderId();
        String paymentLink = String.format("%s/upi-payment.html?orderId=%s&amount=%s",
                upiProperties.getBaseUrl(), orderId, amount.toPlainString());

        String upiUri = buildUpiUri(orderId, amount, request.getPayerName());

        log.info("Creating UPI transaction. OrderId: {}, Amount: {}, Payer: {}, Mobile: {}",
                orderId, amount, request.getPayerName(), request.getMobileNumber());

        // Create db record with all relevant fields populated or empty
        UpiTransaction transaction = UpiTransaction.builder()
                .tid(request.getTid())
                .mid(request.getMid())
                .batchNo("000001")
                .deviceId(request.getDeviceId())
                .merchantTransactionId(orderId)
                .amount(amount)
                .paymentMode("UPI")
                .payerVpa("")
                .paymentFor(request.getPayerName())
                .orderId(orderId)
                .orderStatus("PENDING")
                .transactionId("")
                .paymentCurrency("INR")
                .bankReference("")
                .paymentMessage("Payment Initiated. Awaiting confirmation.")
                .paymentTime(Timestamp.valueOf(LocalDateTime.now()))
                .build();

        upiTransactionRepository.save(transaction);
        log.info("Saved UpiTransaction record for orderId: {}", orderId);

        // Pre-generate and cache QR image in Redis and memory for fast serving via /qr/{orderId}.png
        byte[] qrBytes = generateQrImageBytes(upiUri, 400, 400);
        cacheQrBytes(orderId, qrBytes);
        String qrImageUrl = String.format("%s/qr/%s.png", upiProperties.getBaseUrl(), orderId);

        // Send payment link to customer's WhatsApp using WhatAPI
        try {
            whatsAppService.sendPaymentLink(request, orderId, paymentLink, qrImageUrl);
            log.info("Dispatched WhatsApp payment link to mobile: {} with medialink: {}",
                    request.getMobileNumber(), qrImageUrl);
        } catch (Exception ex) {
            log.warn("WhatsApp notification could not be sent: {}", ex.getMessage());
        }

        return ApiResponse.builder()
                .status("success")
                .message("Payment link generated and sent via WhatsApp")
                .mobileNumber(request.getMobileNumber())
                .orderId(orderId)
                .receiptNumber(orderId)
                .paymentLink(paymentLink)
                .qrImageUrl(qrImageUrl)
                .qrString(upiUri)
                .paymentStatus("PENDING")
                .build();
    }

    /**
     * Generate UPI payment QR for POS device display:
     * 1. Validate request (payerName, totalAmount, deviceId, mid, tid)
     * 2. Generate unique order_id
     * 3. Construct unique UPI URI
     * 4. Generate QR code PNG bytes and Base64 string
     * 5. Persist transaction with initial PENDING status
     * 6. Return response containing orderId, qrString, qrCodeBase64, qrImageUrl, and PENDING status
     */
    @Transactional
    public ApiResponse generatePaymentQr(GeneratePaymentQrRequest request) {
        validateQrRequest(request);

        BigDecimal amount;
        try {
            amount = new BigDecimal(request.getTotalAmount().trim());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CommonValidationException("Amount must be greater than zero", HttpStatus.BAD_REQUEST.value());
            }
        } catch (NumberFormatException ex) {
            throw new CommonValidationException("Invalid amount format: " + request.getTotalAmount(), HttpStatus.BAD_REQUEST.value());
        }

        String orderId = generateOrderId();
        String upiUri = buildUpiUri(orderId, amount, request.getPayerName());

        log.info("Generating POS UPI QR. OrderId: {}, Amount: {}, Payer: {}, Device: {}, TID: {}, MID: {}",
                orderId, amount, request.getPayerName(), request.getDeviceId(), request.getTid(), request.getMid());

        byte[] qrBytes = generateQrImageBytes(upiUri, 400, 400);
        cacheQrBytes(orderId, qrBytes);
        String base64Image = "data:image/png;base64," + Base64.getEncoder().encodeToString(qrBytes);
        String qrImageUrl = String.format("%s/qr/%s.png", upiProperties.getBaseUrl(), orderId);

        UpiTransaction transaction = UpiTransaction.builder()
                .tid(request.getTid())
                .mid(request.getMid())
                .batchNo("000001")
                .deviceId(request.getDeviceId())
                .merchantTransactionId(orderId)
                .amount(amount)
                .paymentMode("UPI")
                .payerVpa("")
                .paymentFor(request.getPayerName())
                .orderId(orderId)
                .orderStatus("PENDING")
                .transactionId("")
                .paymentCurrency("INR")
                .bankReference("")
                .paymentMessage("QR Code Generated on POS. Awaiting customer scan and payment.")
                .paymentTime(Timestamp.valueOf(LocalDateTime.now()))
                .build();

        upiTransactionRepository.save(transaction);
        log.info("Saved POS UpiTransaction record for orderId: {}", orderId);

        return ApiResponse.builder()
                .status("success")
                .message("Payment QR generated successfully")
                .orderId(orderId)
                .receiptNumber(orderId)
                .qrString(upiUri)
                .qrCodeBase64(base64Image)
                .qrImageUrl(qrImageUrl)
                .paymentStatus("PENDING")
                .build();
    }

    @Transactional
    public ApiResponse generatePaymentQr(SendPaymentLinkRequest request) {
        if (request == null) {
            throw new CommonValidationException("Request body cannot be null", HttpStatus.BAD_REQUEST.value());
        }
        GeneratePaymentQrRequest qrRequest = GeneratePaymentQrRequest.builder()
                .payerName(request.getPayerName())
                .totalAmount(request.getTotalAmount())
                .deviceId(request.getDeviceId())
                .mid(request.getMid())
                .tid(request.getTid())
                .mobileNumber(request.getMobileNumber())
                .build();
        return generatePaymentQr(qrRequest);
    }

    /**
     * Get real-time payment status: checks local state and verifies via email.
     */
    public ApiResponse getPaymentStatus(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new CommonValidationException("Order ID is required", HttpStatus.BAD_REQUEST.value());
        }

        String cleanOrderId = orderId.trim();
        Optional<UpiTransaction> txOpt = upiTransactionRepository.findByOrderId(cleanOrderId);
        if (txOpt.isEmpty()) {
            txOpt = upiTransactionRepository.findByMerchantTransactionId(cleanOrderId);
        }

        if (txOpt.isEmpty()) {
            throw new CommonValidationException("Order not found: " + cleanOrderId, HttpStatus.NOT_FOUND.value());
        }

        UpiTransaction tx = txOpt.get();

        // If transaction is still PENDING, check mail inbox
        if ("PENDING".equalsIgnoreCase(tx.getOrderStatus())) {
            EmailPaymentVerificationService.VerificationResult result = emailVerificationService.checkPaymentStatus(tx);

            if (result != null && "SUCCESS".equalsIgnoreCase(result.getStatus())) {
                log.info("Payment SUCCESS confirmed via email for order {}", cleanOrderId);
                tx.setOrderStatus("SUCCESS");
                if (result.getBankReference() != null && !result.getBankReference().isEmpty()) {
                    tx.setBankReference(result.getBankReference());
                }
                if (result.getTransactionId() != null && !result.getTransactionId().isEmpty()) {
                    tx.setTransactionId(result.getTransactionId());
                }
                if (result.getPayerVpa() != null && !result.getPayerVpa().isEmpty()) {
                    tx.setPayerVpa(result.getPayerVpa());
                }
                tx.setPaymentMessage(result.getMessage());
                tx.setPaymentTime(Timestamp.valueOf(LocalDateTime.now()));
                upiTransactionRepository.save(tx);

            } else if (result != null && "FAILED".equalsIgnoreCase(result.getStatus())) {
                log.info("Payment FAILED/TIMED OUT for order {}", cleanOrderId);
                tx.setOrderStatus("FAILED");
                tx.setPaymentMessage(result.getMessage());
                upiTransactionRepository.save(tx);
            }
        }

        String upiUri = buildUpiUri(tx.getOrderId(), tx.getAmount(), tx.getPaymentFor());

        return ApiResponse.builder()
                .status(tx.getOrderStatus())
                .paymentStatus(tx.getOrderStatus())
                .orderId(tx.getOrderId())
                .receiptNumber(tx.getOrderId())
                .message(tx.getPaymentMessage())
                .bankReference(tx.getBankReference())
                .transactionId(tx.getTransactionId())
                .qrString(upiUri)
                .build();
    }

    /**
     * Retrieve the latest pending transaction for dynamic payment page rendering.
     * If no pending exists, retrieves the latest initiated transaction.
     */
    public ApiResponse getLatestPendingTransaction() {
        Optional<UpiTransaction> txOpt = upiTransactionRepository.findFirstByOrderStatusOrderByPaymentTimeDesc("PENDING");
        if (txOpt.isEmpty()) {
            txOpt = upiTransactionRepository.findFirstByOrderByPaymentTimeDesc();
        }
        if (txOpt.isEmpty()) {
            throw new CommonValidationException("No transaction found", HttpStatus.NOT_FOUND.value());
        }
        UpiTransaction tx = txOpt.get();
        String upiUri = buildUpiUri(tx.getOrderId(), tx.getAmount(), tx.getPaymentFor());
        return ApiResponse.builder()
                .status("success")
                .paymentStatus(tx.getOrderStatus())
                .orderId(tx.getOrderId())
                .receiptNumber(tx.getOrderId())
                .totalAmount(tx.getAmount().toPlainString())
                .qrString(upiUri)
                .qrImageUrl(String.format("%s/qr/%s.png", upiProperties.getBaseUrl(), tx.getOrderId()))
                .payerName(tx.getPaymentFor())
                .message("Latest transaction fetched")
                .build();
    }

    /**
     * Background task: Runs every 3 seconds to check email for all PENDING payments.
     * Enforces the 3-minute timeout rule in real time.
     */
    @Scheduled(fixedDelay = 3000)
    public void pollPendingTransactions() {
        List<UpiTransaction> pendingList = upiTransactionRepository.findByOrderStatus("PENDING");
        if (pendingList == null || pendingList.isEmpty()) {
            return;
        }

        for (UpiTransaction tx : pendingList) {
            try {
                EmailPaymentVerificationService.VerificationResult result = emailVerificationService.checkPaymentStatus(tx);
                if ("SUCCESS".equalsIgnoreCase(result.getStatus())) {
                    log.info("[Background 3s Job] Order {} verified SUCCESS via email", tx.getOrderId());
                    tx.setOrderStatus("SUCCESS");
                    if (result.getBankReference() != null && !result.getBankReference().isEmpty()) {
                        tx.setBankReference(result.getBankReference());
                    }
                    if (result.getTransactionId() != null && !result.getTransactionId().isEmpty()) {
                        tx.setTransactionId(result.getTransactionId());
                    }
                    if (result.getPayerVpa() != null && !result.getPayerVpa().isEmpty()) {
                        tx.setPayerVpa(result.getPayerVpa());
                    }
                    tx.setPaymentMessage(result.getMessage());
                    tx.setPaymentTime(Timestamp.valueOf(LocalDateTime.now()));
                    upiTransactionRepository.save(tx);

                } else if ("FAILED".equalsIgnoreCase(result.getStatus())) {
                    log.info("[Background 3s Job] Order {} marked FAILED (3-min timeout or decline)", tx.getOrderId());
                    tx.setOrderStatus("FAILED");
                    tx.setPaymentMessage(result.getMessage());
                    upiTransactionRepository.save(tx);
                }
            } catch (Exception ex) {
                log.warn("[Background 3s Job] Error verifying order {}: {}", tx.getOrderId(), ex.getMessage());
            }
        }
    }

    private String buildUpiUri(String orderId, BigDecimal amount, String payerName) {
        String merchantVpa = upiProperties.getMerchantVpa();
        String merchantName = upiProperties.getMerchantName();
        String safeName = URLEncoder.encode(merchantName != null ? merchantName : "Merchant", StandardCharsets.UTF_8).replace("+", "%20");
        String safeNote = URLEncoder.encode("Payment for " + (payerName != null ? payerName : orderId), StandardCharsets.UTF_8).replace("+", "%20");
        String amtStr = amount != null ? amount.toPlainString() : "0.00";

        return String.format("upi://pay?pa=%s&pn=%s&am=%s&cu=INR&tr=%s&tn=%s",
                merchantVpa, safeName, amtStr, orderId, safeNote);
    }

    private void validateRequest(SendPaymentLinkRequest request) {
        if (request == null) {
            throw new CommonValidationException("Request body cannot be null", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getPayerName() == null || request.getPayerName().trim().isEmpty()) {
            throw new CommonValidationException("payer name is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getMobileNumber() == null || request.getMobileNumber().trim().isEmpty()) {
            throw new CommonValidationException("Mobile number is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getTotalAmount() == null || request.getTotalAmount().trim().isEmpty()) {
            throw new CommonValidationException("totalAmount is required", HttpStatus.BAD_REQUEST.value());
        }
    }

    private void validateQrRequest(GeneratePaymentQrRequest request) {
        if (request == null) {
            throw new CommonValidationException("Request body cannot be null", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getPayerName() == null || request.getPayerName().trim().isEmpty()) {
            throw new CommonValidationException("payer name is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getTotalAmount() == null || request.getTotalAmount().trim().isEmpty()) {
            throw new CommonValidationException("totalAmount is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getDeviceId() == null || request.getDeviceId().trim().isEmpty()) {
            throw new CommonValidationException("device id is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getMid() == null || request.getMid().trim().isEmpty()) {
            throw new CommonValidationException("mid is required", HttpStatus.BAD_REQUEST.value());
        }
        if (request.getTid() == null || request.getTid().trim().isEmpty()) {
            throw new CommonValidationException("tid is required", HttpStatus.BAD_REQUEST.value());
        }
    }

    private synchronized String generateOrderId() {
        return "ORD" + System.currentTimeMillis() + (1000 + random.nextInt(9000));
    }

    /**
     * Retrieve QR image PNG bytes for a given orderId (served directly via HTTP).
     * Reconstructs from DB if cache missed.
     */
    public byte[] getQrImageBytesForOrder(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new CommonValidationException("Order ID is required", HttpStatus.BAD_REQUEST.value());
        }

        String cleanOrderId = orderId.trim();
        if (cleanOrderId.toLowerCase().endsWith(".png")) {
            cleanOrderId = cleanOrderId.substring(0, cleanOrderId.length() - 4);
        }

        byte[] cached = getCachedQrBytes(cleanOrderId);
        if (cached != null) {
            return cached;
        }

        Optional<UpiTransaction> txOpt = upiTransactionRepository.findByOrderId(cleanOrderId);
        if (txOpt.isEmpty()) {
            txOpt = upiTransactionRepository.findByMerchantTransactionId(cleanOrderId);
        }

        if (txOpt.isEmpty()) {
            throw new CommonValidationException("Order not found: " + cleanOrderId, HttpStatus.NOT_FOUND.value());
        }

        UpiTransaction tx = txOpt.get();
        String upiUri = buildUpiUri(tx.getOrderId(), tx.getAmount(), tx.getPaymentFor());
        byte[] generated = generateQrImageBytes(upiUri, 400, 400);
        cacheQrBytes(cleanOrderId, generated);
        return generated;
    }

    public byte[] generateQrImageBytes(String text, int width, int height) {
        try {
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            BitMatrix bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, width, height, hints);

            // Generate standard 24-bit RGB image (TYPE_INT_RGB) for 100% compatibility with WhatsApp Cloud API
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    image.setRGB(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "PNG", outputStream);
            return outputStream.toByteArray();
        } catch (Exception ex) {
            log.error("Failed to generate QR code image: {}", ex.getMessage(), ex);
            throw new CommonValidationException("Failed to generate QR code: " + ex.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR.value());
        }
    }

    public UpiTransactions searchUpiTransactions(UpiTransactionSearchRequest request, Pageable pageable) {
        Page<UpiTransaction> result;

        boolean hasSearch = request.getMid() != null
                || request.getDeviceId() != null || request.getTid() != null;

        if (hasSearch) {
            result = upiTransactionRepository.searchTransactions(
                    isBlank(request.getMid()) ? null : request.getMid(),
                    isBlank(request.getBatchNo()) ? null : request.getBatchNo(),
                    isBlank(request.getDeviceId()) ? null : request.getDeviceId(),
                    isBlank(request.getTid()) ? null : request.getTid(),
                    pageable
            );
        } else {
            result = upiTransactionRepository.findAll(pageable);
        }
        List<UpiTransaction> upiTransactionDataList = result.getContent();

        UpiTransactions upiTransactions = UpiTransactions
                .builder()
                .transactionDataList(upiTransactionDataList.reversed())
                .totalCount(result.getTotalElements())
                .build();
        log.info("......Fetched Upi transactions ......");

        return upiTransactions;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
