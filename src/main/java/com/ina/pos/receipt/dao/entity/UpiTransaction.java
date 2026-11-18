package com.ina.pos.receipt.dao.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.io.Serializable;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.UUID;

@Getter
@Setter
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@Table(name = "upi_transactions")
public class UpiTransaction implements Serializable {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    private UUID id;

    @Column(name = "tid", nullable = false)
    private String tid;

    @Column(name = "mid", nullable = false)
    private String mid;

    @Column(name = "batch_no", nullable = false)
    private String batchNo;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "merchant_transaction_id", nullable = false)
    private String merchantTransactionId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "payment_mode", nullable = false)
    private String paymentMode;

    @Column(name = "payer_vpa")
    private String payerVpa;

    @Column(name = "payment_for", nullable = false)
    private String paymentFor;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "order_status")
    private String orderStatus;

    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "payment_currency")
    private String paymentCurrency;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "payment_message")
    private String paymentMessage;

    @Column(name = "payment_time")
    private Timestamp paymentTime;

}
