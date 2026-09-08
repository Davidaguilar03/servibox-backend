package com.servibox.backend.inventory.controller;

import com.servibox.backend.inventory.dto.CategoryRequest;
import com.servibox.backend.inventory.dto.CategoryResponse;
import com.servibox.backend.inventory.dto.ProductRequest;
import com.servibox.backend.inventory.dto.ProductResponse;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.entity.ProductCategory;
import com.servibox.backend.inventory.entity.TaxType;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.shared.ResourceNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/categories")
    public List<CategoryResponse> listarCategorias() {
        return inventoryService.findAllCategories().stream().map(CategoryResponse::from).toList();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse crearCategoria(@Valid @RequestBody CategoryRequest request) {
        ProductCategory category = new ProductCategory();
        category.setName(request.name());
        category.setColor(request.color());
        category.setYellowStockMin(request.yellowStockMin());
        category.setRedStockMin(request.redStockMin());
        category.setTargetMargin(request.targetMargin());
        category.setTaxTypes(resolverImpuestos(request.taxTypeIds()));
        return CategoryResponse.from(inventoryService.saveCategory(category));
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

    private List<TaxType> resolverImpuestos(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<TaxType> disponibles = inventoryService.findAllTaxTypes();
        return disponibles.stream().filter(t -> ids.contains(t.getId())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
