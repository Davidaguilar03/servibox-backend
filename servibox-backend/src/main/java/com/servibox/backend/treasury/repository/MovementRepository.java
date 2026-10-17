package com.servibox.backend.treasury.repository;

import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementSourceType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * JpaSpecificationExecutor para el listado con filtros opcionales: cada filtro se agrega
 * solo si viene, en vez de un `(:p IS NULL OR ...)` en JPQL, que da problemas de tipado en
 * PostgreSQL (ver Modulo Reporting en 01-ARCHITECTURE.md).
 */
public interface MovementRepository extends JpaRepository<Movement, Long>, JpaSpecificationExecutor<Movement> {

    /**
     * Nunca usar el findById heredado sobre una entidad TenantAware: el @Filter de
     * Hibernate no se aplica a EntityManager.find(). Ver 02-CONVENTIONS.md.
     */
    Optional<Movement> findByIdAndTenantId(Long id, Long tenantId);

    List<Movement> findByAccountIdOrderByDateDesc(Long accountId);

    /**
     * Los movimientos que genero una factura: la venta y sus abonos, la compra y sus pagos.
     * El tenantId va explicito ademas del @Filter: sourceId no es una clave foranea, asi
     * que aqui el where es la unica garantia de que no se cruce un id de otro tenant.
     */
    List<Movement> findBySourceTypeAndSourceIdAndTenantId(
            MovementSourceType sourceType, Long sourceId, Long tenantId);

    @Query("SELECT COALESCE(SUM(m.amount), 0) FROM Movement m WHERE m.category = :categoria")
    double sumAmountByCategory(MovementCategory categoria);

    @Query("""
            SELECT COALESCE(SUM(m.amount), 0) FROM Movement m
            WHERE m.category = :categoria AND m.date BETWEEN :desde AND :hasta
            """)
    double sumAmountByCategoryAndDateBetween(MovementCategory categoria, LocalDate desde, LocalDate hasta);
}
