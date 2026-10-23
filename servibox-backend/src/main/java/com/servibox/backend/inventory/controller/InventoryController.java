package com.servibox.backend.inventory.controller;

import com.servibox.backend.auth.entity.Role;
import com.servibox.backend.inventory.dto.CategoryRequest;
import com.servibox.backend.inventory.dto.CategoryResponse;
import com.servibox.backend.inventory.dto.FinancialSettingsRequest;
import com.servibox.backend.inventory.dto.FinancialSettingsResponse;
import com.servibox.backend.inventory.dto.ProductRequest;
import com.servibox.backend.inventory.dto.ProductResponse;
import com.servibox.backend.inventory.dto.ProductUpdateRequest;
import com.servibox.backend.inventory.dto.TaxTypeResponse;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.tenant.AdminRequiredException;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private static final String ROL_ADMIN = "ROLE_" + Role.ADMIN.name();
    private static final String MENSAJE_CATEGORIAS = "Solo un administrador puede crear o editar categorias";

    private final InventoryService inventoryService;
    private final Validator validator;

    @GetMapping("/categories")
    public List<CategoryResponse> listarCategorias() {
        return inventoryService.findAllCategories().stream().map(CategoryResponse::from).toList();
    }

    /** Solo ADMIN, ver exigirAdmin. */
    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse crearCategoria(@RequestBody CategoryRequest request, Authentication authentication) {
        exigirAdmin(authentication, request, MENSAJE_CATEGORIAS);
        return CategoryResponse.from(inventoryService.crearCategoria(request.name(), request.color(),
                request.yellowStockMin(), request.redStockMin(), request.targetMargin(), request.taxTypeIds()));
    }

    /** Solo ADMIN, ver exigirAdmin. */
    @PutMapping("/categories/{id}")
    public CategoryResponse editarCategoria(@PathVariable Long id, @RequestBody CategoryRequest request,
                                            Authentication authentication) {
        exigirAdmin(authentication, request, MENSAJE_CATEGORIAS);
        inventoryService.findCategoryById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Categoria no encontrada: " + id));
        return CategoryResponse.from(inventoryService.actualizarCategoria(id, request.name(), request.color(),
                request.yellowStockMin(), request.redStockMin(), request.targetMargin(), request.taxTypeIds()));
    }

    @GetMapping("/tax-types")
    public List<TaxTypeResponse> listarImpuestos() {
        return inventoryService.listarImpuestos().stream().map(TaxTypeResponse::from).toList();
    }

    /** Sin configuracion guardada devuelve la de findFinancialSettings: todo en cero. */
    @GetMapping("/financial-settings")
    public FinancialSettingsResponse obtenerConfiguracionFinanciera() {
        return FinancialSettingsResponse.from(inventoryService.findFinancialSettings());
    }

    /** Solo ADMIN, ver exigirAdmin. */
    @PutMapping("/financial-settings")
    public FinancialSettingsResponse guardarConfiguracionFinanciera(
            @RequestBody FinancialSettingsRequest request, Authentication authentication) {
        exigirAdmin(authentication, request, "Solo un administrador puede editar la configuracion financiera");
        return FinancialSettingsResponse.from(inventoryService.guardarConfiguracionFinanciera(
                request.expensesRate(), request.dianRate(), request.icaRate(), request.cardCommissionRate()));
    }

    @GetMapping("/products")
    public List<ProductResponse> listarProductos() {
        return inventoryService.findAllProducts().stream().map(ProductResponse::from).toList();
    }

    @GetMapping("/products/{id}")
    public ProductResponse obtenerProducto(@PathVariable Long id) {
        return inventoryService.findProductById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + id));
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductResponse crearProducto(@Valid @RequestBody ProductRequest request) {
        ProductCategory category = inventoryService.findCategoryById(request.categoryId())
                .orElseThrow(() -> new IllegalArgumentException("Categoria no encontrada: " + request.categoryId()));

        Product product = new Product();
        product.setCode(request.code());
        product.setDescription(request.description());
        product.setPurchaseCost(request.purchaseCost());
        product.setQuantity(request.quantity());
        product.setCategory(category);

        return ProductResponse.from(inventoryService.saveProduct(product));
    }

    @PutMapping("/products/{id}")
    public ProductResponse editarProducto(@PathVariable Long id, @Valid @RequestBody ProductUpdateRequest request) {
        inventoryService.findProductById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado: " + id));
        ProductCategory category = inventoryService.findCategoryById(request.categoryId())
                .orElseThrow(() -> new IllegalArgumentException("Categoria no encontrada: " + request.categoryId()));
        return ProductResponse.from(inventoryService.actualizarProducto(id, request.code(), request.description(),
                request.purchaseCost(), category));
    }

    /**
     * La misma regla por rol que TenantController: la authority ROLE_ADMIN que pone
     * JwtAuthenticationFilter, o AdminRequiredException (403). Va antes de validar el cuerpo,
     * por eso estos endpoints no llevan @Valid: las anotaciones del DTO se comprueban aqui
     * despues, con el mismo 400 "Peticion invalida" de siempre. Ver 03-DECISIONS.md.
     */
    private void exigirAdmin(Authentication authentication, Object request, String mensaje) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> ROL_ADMIN.equals(a.getAuthority()));
        if (!admin) {
            throw new AdminRequiredException(mensaje);
        }
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalArgumentException("Peticion invalida");
        }
    }
}
