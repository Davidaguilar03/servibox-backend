package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

/**
 * JpaSpecificationExecutor para el listado con filtros opcionales: cada filtro se agrega
 * solo si viene, en vez de un `(:p IS NULL OR ...)` en JPQL, que da problemas de tipado en
 * PostgreSQL (ver Modulo Reporting en 01-ARCHITECTURE.md). Los listados y sumas pasan por
 * ahi para llevar siempre MovementSpecifications.activo().
 */
public interface MovementRepository extends JpaRepository<Movement, Long>, JpaSpecificationExecutor<Movement>,
        MovementRepositoryCustom {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<Movement> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Todos los movimientos de una venta, **activos o no**: el contado y los abonos. Los
     * usan el saldo pendiente, anular y restaurar. Nunca para mostrar ni sumar en tesoreria.
     */
    List<Movement> findBySaleIdAndTenantIdOrderByIdAsc(Long saleId, Long tenantId);

    /** Todos los movimientos de una compra, activos o no. Mismo uso que el de ventas. */
    List<Movement> findByPurchaseIdAndTenantIdOrderByIdAsc(Long purchaseId, Long tenantId);
}
