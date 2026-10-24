package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByCodeIgnoreCase(String code);

    List<Product> findByCategoryId(Long categoryId);

    Optional<Product> findByCodeAndTenantId(String code, Long tenantId);

    /**
     * Busqueda por id que si respeta el tenant. El @Filter de Hibernate NO se aplica a
     * EntityManager.find(), asi que el findById heredado de JpaRepository devuelve el
     * producto aunque sea de otro tenant. Las consultas derivadas como esta si pasan por
     * el filtro, y ademas el tenant va explicito en el where.
     */
    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * findByIdAndTenantId con bloqueo pesimista de escritura (SELECT ... FOR UPDATE): la fila
     * queda bloqueada hasta que termina la transaccion. No refresca una entidad que ya estaba
     * en el contexto de persistencia; quien la usa la refresca. Ver Bloqueos en
     * 02-CONVENTIONS.md.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Product p WHERE p.id = :id AND p.tenantId = :tenantId")
    Optional<Product> findByIdAndTenantIdForUpdate(Long id, Long tenantId);
}
