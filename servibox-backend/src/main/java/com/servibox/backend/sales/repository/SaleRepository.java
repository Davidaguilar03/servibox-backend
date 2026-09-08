package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.Sale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    /** Ver la nota de findByIdAndTenantId en CustomerRepository. */
    Optional<Sale> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Sale> findByInvoiceNumberIgnoreCaseAndTenantId(String invoiceNumber, Long tenantId);
}
