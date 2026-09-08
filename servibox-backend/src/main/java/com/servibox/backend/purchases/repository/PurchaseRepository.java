package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /** Ver la nota de findByIdAndTenantId en SupplierRepository. */
    Optional<Purchase> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Purchase> findByInvoiceNumberIgnoreCaseAndTenantId(String invoiceNumber, Long tenantId);
}
