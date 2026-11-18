package com.ina.pos.receipt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeneratePaymentQrRequest {

    @NotBlank(message = "payer name is required")
    private String payerName;

    @NotBlank(message = "totalAmount is required")
    private String totalAmount;

    @NotBlank(message = "device id is required")
    private String deviceId;

    @NotBlank(message = "mid is required")
    private String mid;

    @NotBlank(message = "tid is required")
    private String tid;

    private String mobileNumber;
}
