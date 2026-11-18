package com.ina.pos.receipt.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SendPosReceiptRequest {

    @NotBlank(message = "Mobile number is required")
    private String mobileNumber;

    @NotBlank(message = "Receipt number is required")
    private String referenceNumber;

    @NotBlank(message = "storeName is required")
    private String storeName;

    @NotBlank(message = "totalAmount is required")
    private String totalAmount;

}
