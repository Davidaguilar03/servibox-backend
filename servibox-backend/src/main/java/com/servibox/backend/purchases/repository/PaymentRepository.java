package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByPurchaseId(Long purchaseId);
}
