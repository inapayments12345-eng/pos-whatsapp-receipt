package com.ina.pos.receipt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {
    private String status;
    private String message;
    private String receiptNumber;
    private String mobileNumber;
    private String whatsappMessageId;
    private String pdfUrl;

    // UPI Payment Fields
    private String orderId;
    private String paymentLink;
    private String qrString;
    private String paymentStatus;
    private String bankReference;
    private String transactionId;
    private String qrCodeBase64;
    private String qrImageUrl;
    private String totalAmount;
    private String payerName;

    @JsonProperty("order_id")
    public String getOrderIdSnake() {
        return orderId;
    }

    @JsonProperty("payment_link")
    public String getPaymentLinkSnake() {
        return paymentLink;
    }

    @JsonProperty("qr_string")
    public String getQrStringSnake() {
        return qrString;
    }

    @JsonProperty("qr_code_base64")
    public String getQrCodeBase64Snake() {
        return qrCodeBase64;
    }

    @JsonProperty("qr_image_url")
    public String getQrImageUrlSnake() {
        return qrImageUrl;
    }
}
