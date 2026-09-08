package com.servibox.backend.purchases.controller;

import com.servibox.backend.purchases.dto.PaymentRequest;
import com.servibox.backend.purchases.dto.PaymentResponse;
import com.servibox.backend.purchases.dto.PurchaseRequest;
import com.servibox.backend.purchases.dto.PurchaseResponse;
import com.servibox.backend.purchases.entity.Payment;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.service.PurchasesService;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
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

import java.util.List;

@RestController
@RequestMapping("/api/purchases")
@RequiredArgsConstructor
public class PurchasesController {

    private final PurchasesService purchasesService;
    private final TreasuryService treasuryService;

    @GetMapping
    public List<PurchaseResponse> listarCompras() {
        return purchasesService.findAllPurchases().stream().map(this::responder).toList();
    }

    @GetMapping("/{id}")
    public PurchaseResponse obtenerCompra(@PathVariable Long id) {
        return responder(compraDeRuta(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PurchaseResponse crearCompra(@Valid @RequestBody PurchaseRequest request) {
        Supplier proveedor = null;
        if (request.supplierId() != null) {
            proveedor = purchasesService.findSupplierById(request.supplierId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Proveedor no encontrado: " + request.supplierId()));
        }

        Account cuenta = null;
        if (request.paymentType() == PaymentType.CONTADO && request.accountId() != null) {
            cuenta = treasuryService.findAccountById(request.accountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cuenta no encontrada: " + request.accountId()));
        }

        List<PurchasesService.LineaCompra> lineas = request.details().stream()
                .map(d -> new PurchasesService.LineaCompra(d.productId(), d.quantity(), d.price()))
                .toList();

        return responder(purchasesService.crearFactura(
                request.invoiceNumber(),
                proveedor,
                request.invoiceDate(),
                request.dueDate(),
                request.paymentType(),
                cuenta,
                request.paymentMethod(),
                lineas));
    }

    @PostMapping("/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse registrarPago(@PathVariable Long id,
                                         @Valid @RequestBody PaymentRequest request) {
        compraDeRuta(id);
        Payment pago = purchasesService.registrarPago(id, request.accountId(), request.amount());
        return PaymentResponse.from(pago, purchasesService.saldoPendiente(pago.getPurchase()));
    }

    @PostMapping("/{id}/annul")
    public PurchaseResponse anularCompra(@PathVariable Long id) {
        compraDeRuta(id);
        return responder(purchasesService.anularFactura(id));
    }

    /** Compra pedida por la ruta: 404 si no existe para este tenant. */
    private Purchase compraDeRuta(Long id) {
        return purchasesService.findPurchaseById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Compra no encontrada: " + id));
    }

    private PurchaseResponse responder(Purchase compra) {
        return PurchaseResponse.from(compra,
                purchasesService.detallesDe(compra),
                purchasesService.saldoPendiente(compra));
    }
}
