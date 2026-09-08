package com.servibox.backend.inventory.repository;

import com.servibox.backend.inventory.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    Optional<ProductCategory> findByName(String name);

    /** Ver la nota de findByIdAndTenantId en ProductRepository: findById cruza tenants. */
    Optional<ProductCategory> findByIdAndTenantId(Long id, Long tenantId);
}
