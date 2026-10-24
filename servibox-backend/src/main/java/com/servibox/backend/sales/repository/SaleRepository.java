package com.servibox.backend.sales.repository;

import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    /** Ver la nota de findByIdAndTenantId en CounterpartyRepository. */
    Optional<Sale> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * findByIdAndTenantId con bloqueo pesimista de escritura (SELECT ... FOR UPDATE): la fila
     * queda bloqueada hasta que termina la transaccion. No refresca una entidad que ya estaba
     * en el contexto de persistencia; quien la usa la refresca. Ver Bloqueos en
     * 02-CONVENTIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM Sale s WHERE s.id = :id AND s.tenantId = :tenantId")
    Optional<Sale> findByIdAndTenantIdForUpdate(Long id, Long tenantId);

    Optional<Sale> findByInvoiceNumberIgnoreCaseAndTenantId(String invoiceNumber, Long tenantId);

    /** Solo los numeros, no las ventas, de todas las facturas del tenant (anuladas incluidas). */
    @Query("SELECT s.invoiceNumber FROM Sale s WHERE s.tenantId = :tenantId "
            + "AND s.invoiceNumber LIKE CONCAT(:prefijo, '%')")
    List<String> findInvoiceNumbersByTenantIdAndPrefix(Long tenantId, String prefijo);

    /** Total facturado en todo el historico, sin las facturas en el estado excluido (ANULADA). */
    @Query("SELECT COALESCE(SUM(s.total), 0) FROM Sale s WHERE s.status <> :excluido")
    double sumTotalByStatusNot(SaleStatus excluido);

    @Query("SELECT COALESCE(SUM(s.total), 0) FROM Sale s WHERE s.status <> :excluido "
            + "AND s.invoiceDate BETWEEN :desde AND :hasta")
    double sumTotalByStatusNotAndInvoiceDateBetween(SaleStatus excluido, LocalDate desde, LocalDate hasta);

    List<Sale> findByInvoiceDateBetweenAndStatusNot(LocalDate desde, LocalDate hasta, SaleStatus excluido);
}
