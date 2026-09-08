package com.servibox.backend.purchases.service;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.repository.ProductRepository;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.purchases.entity.Payment;
import com.servibox.backend.purchases.entity.PaymentType;
import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.entity.PurchaseDetail;
import com.servibox.backend.purchases.entity.PurchaseStatus;
import com.servibox.backend.purchases.entity.Supplier;
import com.servibox.backend.purchases.repository.PaymentRepository;
import com.servibox.backend.purchases.repository.PurchaseDetailRepository;
import com.servibox.backend.purchases.repository.PurchaseRepository;
import com.servibox.backend.purchases.repository.SupplierRepository;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.service.TreasuryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Espejo de SalesService con el signo invertido: una compra suma stock y saca dinero.
 */
@Service
@RequiredArgsConstructor
public class PurchasesService {

    /** Misma tolerancia de redondeo en pesos que en Sales. */
    private static final double TOLERANCIA_PESOS = 1.0;

    private final PurchaseRepository purchaseRepository;
    private final PurchaseDetailRepository purchaseDetailRepository;
    private final PaymentRepository paymentRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final TreasuryService treasuryService;

    @Transactional(readOnly = true)
    public List<Purchase> findAllPurchases() {
        return purchaseRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Purchase> findPurchaseById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return purchaseRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<PurchaseDetail> detallesDe(Purchase compra) {
        return new ArrayList<>(purchaseDetailRepository.findByPurchaseId(compra.getId()));
    }

    @Transactional(readOnly = true)
    public List<Payment> findPaymentsByPurchaseId(Long purchaseId) {
        return paymentRepository.findByPurchaseId(purchaseId);
    }

    @Transactional(readOnly = true)
    public List<Supplier> findAllSuppliers() {
        return supplierRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Supplier> findSupplierById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return supplierRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional
    public Supplier saveSupplier(Supplier supplier) {
        return supplierRepository.save(supplier);
    }

    /** Una linea pedida: que producto, cuantas unidades y a que costo unitario sin IVA. */
    public record LineaCompra(Long productId, Integer quantity, Double price) {
    }

    /**
     * Registra una factura de compra: suma el stock y, si es de contado, saca el dinero de
     * la cuenta. Todo en una transaccion. Flujo completo en 01-ARCHITECTURE.md.
     */
    @Transactional
    public Purchase crearFactura(String invoiceNumber,
                                 Supplier proveedor,
                                 LocalDate invoiceDate,
                                 LocalDate dueDate,
                                 PaymentType paymentType,
                                 Account cuenta,
                                 String paymentMethod,
                                 List<LineaCompra> lineas) {

        validarNumeroFacturaUnico(invoiceNumber, null);

        if (lineas == null || lineas.isEmpty()) {
            throw new InvalidPurchaseOperationException("Una factura de compra necesita al menos una linea");
        }
        if (paymentType == PaymentType.CONTADO && cuenta == null) {
            throw new InvalidPurchaseOperationException(
                    "Una compra de contado necesita la cuenta de la que sale el dinero");
        }

        Purchase compra = new Purchase();
        compra.setInvoiceNumber(invoiceNumber);
        compra.setSupplier(proveedor);
        compra.setInvoiceDate(invoiceDate != null ? invoiceDate : LocalDate.now());
        compra.setDueDate(dueDate);
        compra.setPaymentType(paymentType);
        compra.setAccount(cuenta);
        compra.setPaymentMethod(paymentMethod);
        compra.setStatus(PurchaseStatus.PENDIENTE);
        compra.setSubtotal(0.0);
        compra.setIvaTotal(0.0);
        compra.setTotal(0.0);
        Purchase guardada = purchaseRepository.save(compra);

        double subtotal = 0.0;
        double ivaTotal = 0.0;

        // Primera pasada: se calculan los totales y se crean las lineas, sin tocar el
        // stock todavia. Si la cuenta no alcanza, la transaccion se deshace igual, pero
        // asi la validacion de saldo ve el total definitivo antes de mover nada.
        List<PurchaseDetail> detalles = new ArrayList<>();
        for (LineaCompra linea : lineas) {
            Product producto = inventoryService.findProductById(linea.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Producto no encontrado: " + linea.productId()));

            int cantidad = linea.quantity() != null ? linea.quantity() : 0;
            if (cantidad <= 0) {
                throw new InvalidPurchaseOperationException(
                        "La cantidad de " + producto.getDescription() + " debe ser mayor a cero");
            }

            double precio = linea.price() != null ? linea.price() : 0.0;
            double baseLinea = precio * cantidad;
            double ivaLinea = baseLinea * inventoryService.getIvaRateForProduct(producto);

            PurchaseDetail detalle = new PurchaseDetail();
            detalle.setPurchase(guardada);
            detalle.setProduct(producto);
            detalle.setQuantity(cantidad);
            detalle.setPrice(precio);
            detalle.setIvaAmount(ivaLinea);
            detalles.add(detalle);

            subtotal += baseLinea;
            ivaTotal += ivaLinea;
        }

        double total = subtotal + ivaTotal;

        // Saldo suficiente solo aplica al contado. Una compra a credito no saca dinero
        // hoy, asi que no hay nada que validar; es lo que hace Autollantas.
        if (paymentType == PaymentType.CONTADO) {
            treasuryService.exigirSaldoSuficiente(cuenta, total);
        }

        for (PurchaseDetail detalle : detalles) {
            purchaseDetailRepository.save(detalle);
            // Al reves de una venta: comprar SUMA stock.
            aumentarStock(detalle.getProduct(), detalle.getQuantity());
        }

        guardada.setSubtotal(subtotal);
        guardada.setIvaTotal(ivaTotal);
        guardada.setTotal(total);

        if (paymentType == PaymentType.CONTADO) {
            guardada.setStatus(PurchaseStatus.PAGADA);
            treasuryService.registrarEgresoDeCompra(
                    cuenta, "Compra " + invoiceNumber, total, guardada);
        } else {
            guardada.setStatus(PurchaseStatus.PENDIENTE);
        }

        return purchaseRepository.save(guardada);
    }

    /**
     * Pago a una factura de compra a credito. Se llama **pago**, no abono: el abono es lo
     * que recibe el taller de un cliente. Ver 02-CONVENTIONS.md.
     */
    @Transactional
    public Payment registrarPago(Long purchaseId, Long accountId, Double monto) {
        Purchase compra = findPurchaseById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Compra no encontrada: " + purchaseId));

        if (compra.getStatus() != PurchaseStatus.PENDIENTE) {
            throw new InvalidPurchaseOperationException(
                    "Solo se puede pagar una compra PENDIENTE, y esta esta " + compra.getStatus());
        }
        if (monto == null || monto <= 0) {
            throw new InvalidPurchaseOperationException("El monto del pago debe ser mayor a cero");
        }

        double pendiente = saldoPendiente(compra);
        if (monto - pendiente > TOLERANCIA_PESOS) {
            throw new InvalidPurchaseOperationException(
                    "El pago de " + monto + " excede el saldo pendiente de " + pendiente);
        }

        Account cuenta = treasuryService.findAccountById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + accountId));

        Payment pago = new Payment();
        pago.setPurchase(compra);
        pago.setAccount(cuenta);
        pago.setAmount(monto);
        pago.setDate(LocalDate.now());
        Payment guardado = paymentRepository.save(pago);

        // registrarEgresoDeCompra ya exige saldo suficiente.
        treasuryService.registrarEgresoDeCompra(
                cuenta, "Pago compra " + compra.getInvoiceNumber(), monto, compra);

        if (saldoPendiente(compra) <= TOLERANCIA_PESOS) {
            compra.setStatus(PurchaseStatus.PAGADA);
        }
        compra.setAccount(cuenta);
        purchaseRepository.save(compra);

        return guardado;
    }

    /**
     * Anula una compra: quita el stock que habia sumado y deshace su efecto en tesoreria.
     *
     * Revierte **todos** los movimientos ligados a la compra, pagos incluidos. Autollantas
     * solo revierte si es contado y esta pagada, lo que deja los pagos de una compra a
     * credito sin devolver; es el mismo defecto que tenia su anulacion de ventas y se
     * corrige igual. Ver 03-DECISIONS.md.
     */
    @Transactional
    public Purchase anularFactura(Long purchaseId) {
        Purchase compra = findPurchaseById(purchaseId)
                .orElseThrow(() -> new IllegalArgumentException("Compra no encontrada: " + purchaseId));

        if (compra.getStatus() == PurchaseStatus.ANULADA) {
            throw new InvalidPurchaseOperationException(
                    "La compra " + compra.getInvoiceNumber() + " ya esta anulada");
        }

        List<PurchaseDetail> detalles = purchaseDetailRepository.findByPurchaseId(compra.getId());

        // Se comprueba TODO el stock antes de tocar nada: si una sola linea no se puede
        // devolver, la anulacion no debe dejar las otras a medias. La transaccion tambien
        // lo desharia, pero asi el mensaje de error sale antes de escribir.
        for (PurchaseDetail detalle : detalles) {
            Product producto = detalle.getProduct();
            if (producto == null) {
                continue;
            }
            int disponible = producto.getQuantity() != null ? producto.getQuantity() : 0;
            int aQuitar = detalle.getQuantity() != null ? detalle.getQuantity() : 0;
            if (disponible < aQuitar) {
                throw new InvalidPurchaseOperationException(
                        "No se puede anular la compra " + compra.getInvoiceNumber() + ": de "
                        + producto.getDescription() + " quedan " + disponible
                        + " unidades y habria que quitar " + aQuitar
                        + ". Parte de ese stock ya se vendio.");
            }
        }

        treasuryService.revertirMovimientosDeCompra(compra);

        for (PurchaseDetail detalle : detalles) {
            quitarStock(detalle.getProduct(), detalle.getQuantity());
        }

        compra.setStatus(PurchaseStatus.ANULADA);
        return purchaseRepository.save(compra);
    }

    /** Lo que falta por pagarle al proveedor: el total menos los pagos ya hechos. */
    @Transactional(readOnly = true)
    public double saldoPendiente(Purchase compra) {
        double pagado = paymentRepository.findByPurchaseId(compra.getId()).stream()
                .mapToDouble(p -> p.getAmount() != null ? p.getAmount() : 0.0)
                .sum();
        double total = compra.getTotal() != null ? compra.getTotal() : 0.0;
        double pendiente = total - pagado;
        return pendiente < TOLERANCIA_PESOS ? 0.0 : pendiente;
    }

    /**
     * La restriccion uk_compra_tenant_invoice_number tambien lo impide, pero salta como un
     * error crudo. Esto lo convierte en un mensaje legible antes del INSERT.
     */
    private void validarNumeroFacturaUnico(String invoiceNumber, Long idPropio) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new InvalidPurchaseOperationException("La factura de compra necesita un numero");
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        purchaseRepository.findByInvoiceNumberIgnoreCaseAndTenantId(invoiceNumber, tenantId)
                .filter(existente -> !existente.getId().equals(idPropio))
                .ifPresent(existente -> {
                    throw new DuplicatePurchaseInvoiceNumberException(invoiceNumber);
                });
    }

    private void aumentarStock(Product producto, Integer cantidad) {
        if (producto == null || cantidad == null) {
            return;
        }
        int actual = producto.getQuantity() != null ? producto.getQuantity() : 0;
        producto.setQuantity(actual + cantidad);
        productRepository.save(producto);
    }

    /**
     * Se guarda por el repositorio y no por InventoryService.saveProduct, igual que en
     * Sales: saveProduct revalida el codigo y rehace los precios sugeridos, y mover stock
     * no es motivo para recalcularle el precio a un producto.
     */
    private void quitarStock(Product producto, Integer cantidad) {
        if (producto == null || cantidad == null) {
            return;
        }
        int actual = producto.getQuantity() != null ? producto.getQuantity() : 0;
        producto.setQuantity(actual - cantidad);
        productRepository.save(producto);
    }
}
