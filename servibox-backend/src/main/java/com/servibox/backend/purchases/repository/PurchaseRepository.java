package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /** Ver la nota de findByIdAndTenantId en SupplierRepository. */
    Optional<Purchase> findByIdAndTenantId(Long id, Long tenantId);

    Optional<Purchase> findByInvoiceNumberIgnoreCaseAndTenantId(String invoiceNumber, Long tenantId);

    /** Total comprado en todo el historico, sin las compras en el estado excluido (ANULADA). */
    @Query("SELECT COALESCE(SUM(p.total), 0) FROM Purchase p WHERE p.status <> :excluido")
    double sumTotalByStatusNot(PurchaseStatus excluido);

    @Query("SELECT COALESCE(SUM(p.total), 0) FROM Purchase p WHERE p.status <> :excluido "
            + "AND p.invoiceDate BETWEEN :desde AND :hasta")
    double sumTotalByStatusNotAndInvoiceDateBetween(PurchaseStatus excluido, LocalDate desde, LocalDate hasta);

    List<Purchase> findByInvoiceDateBetweenAndStatusNot(LocalDate desde, LocalDate hasta, PurchaseStatus excluido);
}
