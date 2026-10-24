package com.servibox.backend.purchases.repository;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PurchaseRepository extends JpaRepository<Purchase, Long> {

    /** Ver la nota de findByIdAndTenantId en CounterpartyRepository. */
    Optional<Purchase> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * findByIdAndTenantId con bloqueo pesimista de escritura (SELECT ... FOR UPDATE): la fila
     * queda bloqueada hasta que termina la transaccion. No refresca una entidad que ya estaba
     * en el contexto de persistencia; quien la usa la refresca. Ver Bloqueos en
     * 02-CONVENTIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Purchase p WHERE p.id = :id AND p.tenantId = :tenantId")
    Optional<Purchase> findByIdAndTenantIdForUpdate(Long id, Long tenantId);

    /** Anuladas incluidas: conservan su numero. */
    Optional<Purchase> findByInvoiceNumberIgnoreCaseAndSupplierIdAndTenantId(String invoiceNumber, Long supplierId,
                                                                           Long tenantId);

    /** Total comprado en todo el historico, sin las compras en el estado excluido (ANULADA). */
    @Query("SELECT COALESCE(SUM(p.total), 0) FROM Purchase p WHERE p.status <> :excluido")
    double sumTotalByStatusNot(PurchaseStatus excluido);

    @Query("SELECT COALESCE(SUM(p.total), 0) FROM Purchase p WHERE p.status <> :excluido "
            + "AND p.invoiceDate BETWEEN :desde AND :hasta")
    double sumTotalByStatusNotAndInvoiceDateBetween(PurchaseStatus excluido, LocalDate desde, LocalDate hasta);

    List<Purchase> findByInvoiceDateBetweenAndStatusNot(LocalDate desde, LocalDate hasta, PurchaseStatus excluido);
}
