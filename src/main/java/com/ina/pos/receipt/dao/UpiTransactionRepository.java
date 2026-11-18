package com.ina.pos.receipt.dao;

import com.ina.pos.receipt.dao.entity.UpiTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UpiTransactionRepository extends JpaRepository<UpiTransaction, UUID> {
    Optional<UpiTransaction> findByOrderId(String orderId);
    Optional<UpiTransaction> findByMerchantTransactionId(String merchantTransactionId);
    List<UpiTransaction> findByOrderStatus(String orderStatus);
    Optional<UpiTransaction> findByBankReference(String bankReference);
    Optional<UpiTransaction> findFirstByOrderStatusOrderByPaymentTimeDesc(String orderStatus);
    Optional<UpiTransaction> findFirstByOrderByPaymentTimeDesc();

    @Query("  SELECT u FROM UpiTransaction u\n  WHERE (:mid IS NULL OR u.mid = :mid)\n    AND (:batchNo IS NULL OR u.batchNo = :batchNo)\n    AND (:deviceId IS NULL OR u.deviceId = :deviceId)\n    AND (:tid IS NULL OR u.tid = :tid)\n")
    Page<UpiTransaction> searchTransactions(@Param("mid") String mid, @Param("batchNo") String batchNo, @Param("deviceId") String deviceId, @Param("tid") String tid, Pageable pageable);

}
