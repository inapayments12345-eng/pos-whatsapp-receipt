package com.ina.pos.receipt.dto;

import com.ina.pos.receipt.dao.entity.UpiTransaction;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@Builder
public class UpiTransactions {

    private List<UpiTransaction> transactionDataList;

    private Long totalCount;

}
