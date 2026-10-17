package com.servibox.backend.treasury.repository;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.treasury.entity.Movement;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import org.springframework.data.jpa.domain.Specification;

/** Predicados de MOVIMIENTOS reutilizables entre consultas. */
public final class MovementSpecifications {

    private MovementSpecifications() {
    }

    /**
     * Movimiento activo: no esta enlazado a una venta ni a una compra ANULADA. Es el unico
     * sitio donde vive esa regla; todo listado o suma de movimientos pasa por aqui. Con
     * esto se cumple siempre que saldo_actual = balance_inicial + movimientos activos.
     */
    public static Specification<Movement> activo() {
        return (raiz, consulta, cb) -> {
            Join<Movement, Sale> venta = raiz.join("sale", JoinType.LEFT);
            Join<Movement, Purchase> compra = raiz.join("purchase", JoinType.LEFT);
            return cb.and(
                    cb.or(cb.isNull(venta.get("id")), cb.notEqual(venta.get("status"), SaleStatus.ANULADA)),
                    cb.or(cb.isNull(compra.get("id")), cb.notEqual(compra.get("status"), PurchaseStatus.ANULADA)));
        };
    }
}
