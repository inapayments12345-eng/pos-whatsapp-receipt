package com.ina.pos.receipt.dto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendPaymentLinkRequest {

    @NotBlank(message = "Mobile number is required")
    private String mobileNumber;

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

}
