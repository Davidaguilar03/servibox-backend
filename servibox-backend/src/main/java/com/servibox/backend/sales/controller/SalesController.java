package com.servibox.backend.sales.controller;

import com.servibox.backend.sales.dto.CollectionRequest;
import com.servibox.backend.sales.dto.CollectionResponse;
import com.servibox.backend.sales.dto.SaleRequest;
import com.servibox.backend.sales.dto.SaleResponse;
import com.servibox.backend.sales.entity.Collection;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.service.SalesService;
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
@RequestMapping("/api/sales")
@RequiredArgsConstructor
public class SalesController {

    private final SalesService salesService;
    private final TreasuryService treasuryService;

    @GetMapping
    public List<SaleResponse> listarFacturas() {
        return salesService.findAllSales().stream().map(this::responder).toList();
    }

    @GetMapping("/{id}")
    public SaleResponse obtenerFactura(@PathVariable Long id) {
        return responder(facturaDeRuta(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SaleResponse crearFactura(@Valid @RequestBody SaleRequest request) {
        Customer cliente = null;
        if (request.customerId() != null) {
            cliente = salesService.findCustomerById(request.customerId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cliente no encontrado: " + request.customerId()));
        }

        Account cuenta = null;
        if (request.paymentType() == PaymentType.CONTADO && request.accountId() != null) {
            cuenta = treasuryService.findAccountById(request.accountId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cuenta no encontrada: " + request.accountId()));
        }

        List<SalesService.LineaFactura> lineas = request.details().stream()
                .map(d -> new SalesService.LineaFactura(d.productId(), d.quantity(), d.price()))
                .toList();

        Sale venta = salesService.crearFactura(
                request.invoiceNumber(),
                cliente,
                request.invoiceDate(),
                request.dueDate(),
                request.paymentType(),
                cuenta,
                request.paymentMethod(),
                lineas);

        return responder(venta);
    }

    @PostMapping("/{id}/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public CollectionResponse registrarAbono(@PathVariable Long id,
                                             @Valid @RequestBody CollectionRequest request) {
        facturaDeRuta(id);
        Collection abono = salesService.registrarAbono(id, request.accountId(), request.amount());
        return CollectionResponse.from(abono, salesService.saldoPendiente(abono.getSale()));
    }

    @PostMapping("/{id}/annul")
    public SaleResponse anularFactura(@PathVariable Long id) {
        facturaDeRuta(id);
        return responder(salesService.anularFactura(id));
    }

    /**
     * Factura pedida por la ruta: si no existe para este tenant, 404. El mismo 404 cubre
     * "no existe" y "es de otro tenant", ver ResourceNotFoundException.
     */
    private Sale facturaDeRuta(Long id) {
        return salesService.findSaleById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Factura no encontrada: " + id));
    }

    private SaleResponse responder(Sale venta) {
        return SaleResponse.from(venta,
                salesService.detallesDe(venta),
                salesService.saldoPendiente(venta));
    }
}
