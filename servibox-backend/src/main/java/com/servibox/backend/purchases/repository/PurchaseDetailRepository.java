package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.PurchaseDetail;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PurchaseDetailRepository extends JpaRepository<PurchaseDetail, Long> {

    List<PurchaseDetail> findByPurchaseId(Long purchaseId);
}
