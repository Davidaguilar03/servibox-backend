package com.servibox.backend.treasury.repository;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;

/**
 * Agregado por Criteria. El tenant lo pone el @Filter de Hibernate, que tambien aplica a
 * las consultas Criteria de la sesion.
 */
class MovementRepositoryCustomImpl implements MovementRepositoryCustom {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public double sumarMonto(Specification<Movement> filtro) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Double> consulta = cb.createQuery(Double.class);
        Root<Movement> raiz = consulta.from(Movement.class);
        consulta.select(cb.coalesce(cb.sum(raiz.<Double>get("amount")), 0.0))
                .where(filtro.toPredicate(raiz, consulta, cb));
        return entityManager.createQuery(consulta).getSingleResult();
    }

    @Override
    public List<FilaReporte> listarParaReporte(Specification<Movement> filtro, int maximo) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> consulta = cb.createTupleQuery();
        Root<Movement> raiz = consulta.from(Movement.class);
        Join<Movement, Account> cuenta = raiz.join("account");
        Join<Movement, Sale> venta = raiz.join("sale", JoinType.LEFT);
        Join<Movement, Purchase> compra = raiz.join("purchase", JoinType.LEFT);
        consulta.multiselect(raiz.get("id"), raiz.get("date"), cuenta.get("name"), raiz.get("type"),
                        raiz.get("category"), raiz.get("concept"), raiz.get("amount"),
                        venta.get("invoiceNumber"), compra.get("invoiceNumber"))
                .where(filtro.toPredicate(raiz, consulta, cb))
                .orderBy(cb.asc(raiz.get("date")), cb.asc(raiz.get("id")));
        return entityManager.createQuery(consulta).setMaxResults(maximo).getResultList().stream()
                .map(fila -> new FilaReporte(fila.get(0, Long.class), fila.get(1, LocalDate.class),
                        fila.get(2, String.class), fila.get(3, MovementType.class),
                        fila.get(4, MovementCategory.class), fila.get(5, String.class),
                        fila.get(6, Double.class), fila.get(7, String.class), fila.get(8, String.class)))
                .toList();
    }
}
