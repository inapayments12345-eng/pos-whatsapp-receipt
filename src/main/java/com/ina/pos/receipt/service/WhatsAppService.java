package com.ina.pos.receipt.service;

import com.ina.pos.receipt.dao.entity.ReceiptUrls;
import com.ina.pos.receipt.dto.SendPaymentLinkRequest;
import com.ina.pos.receipt.dto.SendPosReceiptRequest;
import com.ina.pos.receipt.exception.CommonValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class WhatsAppService {

    private final RestTemplate restTemplate;

    @Value("${whatsapp.api.base-url}")
    private String baseUrl;

    @Value("${whatsapp.api.payment-link-url:https://webhook.whatapi.in/webhook/6a745c0ca8f91d16c1ff7b92}")
    private String paymentLinkBaseUrl;

    @Value("${whatsapp.api.payment-link-media-url:https://plain-apac-prod-public.komododecks.com/202606/01/aPAccpxZTQjLCMxhgiEV/image.png}")
    private String paymentLinkMediaUrl;

    public void sendReceipt(String mobile, ReceiptUrls receiptData) {
        validateInputs(mobile, receiptData);
        String message = buildMessage(receiptData);
        String url = buildUrl(mobile, message, receiptData.getUrl());

        try {
            log.info("Sending WhatsApp message to mobile: {}", maskMobile(mobile));
            ResponseEntity<String> response =
                    restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        } catch (CommonValidationException ex) {
            log.error("Error while sending WhatsApp message to {}",
                    maskMobile(mobile), ex);
            throw new CommonValidationException("WhatsApp service error", 500);
        }
    }

    private String buildMessage(ReceiptUrls data) {
        return String.format("Invoice,%s,%s,%s,CARD",
                replaceSpaces(safe(data.getStoreName())),
                safe(data.getReceiptNumber()),
                safe(data.getTotal()));
    }

    private String buildUrl(String mobile, String message, String mediaLink) {
        String formattedMobile = formatMobile(mobile);
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(baseUrl)
                .queryParam("number", formattedMobile);

        Map<String, Object> uriVariables = new HashMap<>();
        uriVariables.put("message", message);
        builder.queryParam("message", "{message}");

        if (mediaLink != null && !mediaLink.isBlank()) {
            uriVariables.put("medialink", mediaLink);
            builder.queryParam("medialink", "{medialink}");
        }

        return builder.buildAndExpand(uriVariables).encode().toUriString();
    }

    private void validateInputs(String mobile, ReceiptUrls data) {
        if (mobile == null || mobile.isBlank()) {
            throw new IllegalArgumentException("Mobile number is required");
        }
        if (data == null) {
            throw new IllegalArgumentException("Receipt data is required");
        }
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String maskMobile(String mobile) {
        if (mobile == null || mobile.length() < 4) return "****";
        return "****" + mobile.substring(mobile.length() - 4);
    }

    private String replaceSpaces(String value) {
        if (value == null) return "";
        return value.replace(" ", "_");
    }

    public void sendPosReceipt(SendPosReceiptRequest request, String pdfUrl) {
        String message = String.format("Invoice,%s,%s,%s,CARD",
                replaceSpaces(safe(request.getStoreName())),
                safe(request.getReferenceNumber()),
                safe(request.getTotalAmount()));

        String url = buildUrl(request.getMobileNumber(), message, pdfUrl);

        try {
            log.info("Sending WhatsApp message to mobile: {}", maskMobile(request.getMobileNumber()));
            restTemplate.exchange(url, HttpMethod.GET, null, String.class);
        } catch (CommonValidationException ex) {
            log.error("Error while sending WhatsApp message to {}",
                    maskMobile(request.getMobileNumber()), ex);
            throw new CommonValidationException("WhatsApp service error", 500);
        }
    }

    public String formatMobile(String mobile) {
        if (mobile == null) return "";
        String cleaned = mobile.replaceAll("[^0-9]", "");
        if (cleaned.length() == 10) {
            return "91" + cleaned;
        }
        return cleaned;
    }

    private String extractButtonSuffix(String paymentUrl) {
        if (paymentUrl == null || paymentUrl.isBlank()) {
            return "";
        }
        // If paymentUrl contains query string (e.g. https://.../upi-payment.html?orderId=...&amount=...),
        // Meta template button variable must only be the suffix, NOT the full URL with protocol/domain.
        if (paymentUrl.contains("?")) {
            return paymentUrl.substring(paymentUrl.indexOf("?") + 1);
        }
        // If it starts with http:// or https://, strip scheme and host path
        if (paymentUrl.startsWith("http://") || paymentUrl.startsWith("https://")) {
            int htmlIdx = paymentUrl.indexOf(".html");
            if (htmlIdx != -1 && paymentUrl.length() > htmlIdx + 5) {
                String sub = paymentUrl.substring(htmlIdx + 5);
                while (sub.startsWith("/")) {
                    sub = sub.substring(1);
                }
                return sub.startsWith("?") ? sub.substring(1) : sub;
            }
            return "";
        }
        return paymentUrl;
    }

    /**
     * Build URL for sendPaymentLink using dedicated webhook:
     * https://webhook.whatapi.in/webhook/6a745c0ca8f91d16c1ff7b92?number=...&message=upipaymentlink&name=...&amount=...&button=...&medialink=...
     */
    private String buildPaymentLinkUrl(String mobile, String name, String amount, String orderId, String paymentUrl, String mediaLink) {
        String formattedMobile = formatMobile(mobile);
        String finalMediaLink = (mediaLink != null && !mediaLink.isBlank() && !mediaLink.contains("localhost"))
                ? mediaLink
                : paymentLinkMediaUrl;

        UriComponentsBuilder builder = UriComponentsBuilder
                .fromHttpUrl(paymentLinkBaseUrl)
                .queryParam("number", formattedMobile)
                .queryParam("message", "upipaymentlink");

        Map<String, Object> uriVariables = new HashMap<>();
        uriVariables.put("name", name != null ? name : "");
        builder.queryParam("name", "{name}");

        uriVariables.put("amount", amount != null ? amount : "");
        builder.queryParam("amount", "{amount}");

        // For dynamic URL button in Meta template: pass unique orderId (e.g. ORD1793...)
        // so Meta appends it to https://.../upi-payment.html/{orderId}
        String buttonValue = (orderId != null && !orderId.isBlank()) ? orderId.trim() : extractButtonSuffix(paymentUrl);
        if (!buttonValue.isBlank()) {
            uriVariables.put("button", buttonValue);
            builder.queryParam("button", "{button}");
        }

        uriVariables.put("medialink", finalMediaLink);
        builder.queryParam("medialink", "{medialink}");

        return builder.buildAndExpand(uriVariables).encode().toUriString();
    }

    /**
     * Send UPI payment link via WhatsApp (using dedicated webhook: 6a745c0ca8f91d16c1ff7b92)
     * Format: https://webhook.whatapi.in/webhook/6a745c0ca8f91d16c1ff7b92?number={number}&message=upipaymentlink&name={name}&amount={amount}&button={button}&medialink={medialink}
     */
    public void sendPaymentLink(SendPaymentLinkRequest request, String orderId, String paymentUrl, String qrImageUrl) {
        String url = buildPaymentLinkUrl(request.getMobileNumber(), request.getPayerName(), request.getTotalAmount(), orderId, paymentUrl, qrImageUrl);

        try {
            log.info("Sending WhatsApp payment link to mobile: {} (url: {})", maskMobile(request.getMobileNumber()), url);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, null, String.class);
            log.info("WhatsApp API response for {}: status={}, body={}",
                    maskMobile(request.getMobileNumber()), response.getStatusCode(), response.getBody());
        } catch (Exception ex) {
            log.error("Error while sending WhatsApp payment link to {}: {}",
                    maskMobile(request.getMobileNumber()), ex.getMessage());
            throw new CommonValidationException("WhatsApp service error: " + ex.getMessage(), 500);
        }
    }

    public void sendPaymentLink(SendPaymentLinkRequest request, String orderId, String paymentUrl) {
        sendPaymentLink(request, orderId, paymentUrl, null);
    }

    public void sendPaymentLink(SendPaymentLinkRequest request, String paymentUrl) {
        sendPaymentLink(request, null, paymentUrl, null);
    }

    public void sendPaymentLink(SendPaymentLinkRequest request) {
        sendPaymentLink(request, null, "");
    }
}
