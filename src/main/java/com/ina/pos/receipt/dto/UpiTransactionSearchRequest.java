package com.ina.pos.receipt.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpiTransactionSearchRequest {
    private String mid;
    private String batchNo;
    private String deviceId;
    private String tid;
    private int pageNumber = 0;
    private int pageSize = 1;
}