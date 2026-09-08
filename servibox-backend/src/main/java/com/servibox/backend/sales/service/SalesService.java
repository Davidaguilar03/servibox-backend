package com.servibox.backend.sales.service;

import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.repository.ProductRepository;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.Collection;
import com.servibox.backend.sales.entity.Customer;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.repository.CollectionRepository;
import com.servibox.backend.sales.repository.CustomerRepository;
import com.servibox.backend.sales.repository.SaleDetailRepository;
import com.servibox.backend.sales.repository.SaleRepository;
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

@Service
@RequiredArgsConstructor
public class SalesService {

    /**
     * Tolerancia de redondeo en pesos. Portada de Autollantas, que trata un pendiente
     * menor a un peso como saldado: los centavos que deja el IVA no pueden dejar una
     * factura eternamente PENDIENTE por 0,4 pesos.
     */
    private static final double TOLERANCIA_PESOS = 1.0;

    private final SaleRepository saleRepository;
    private final SaleDetailRepository saleDetailRepository;
    private final CollectionRepository collectionRepository;
    private final CustomerRepository customerRepository;
    private final ProductRepository productRepository;
    private final InventoryService inventoryService;
    private final TreasuryService treasuryService;

    @Transactional(readOnly = true)
    public List<Sale> findAllSales() {
        return saleRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Sale> findSaleById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return saleRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<SaleDetail> findDetailsBySaleId(Long saleId) {
        return saleDetailRepository.findBySaleId(saleId);
    }

    @Transactional(readOnly = true)
    public List<Collection> findCollectionsBySaleId(Long saleId) {
        return collectionRepository.findBySaleId(saleId);
    }

    @Transactional(readOnly = true)
    public List<Customer> findAllCustomers() {
        return customerRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Customer> findCustomerById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return customerRepository.findByIdAndTenantId(id, tenantId);
    }

    @Transactional
    public Customer saveCustomer(Customer customer) {
        return customerRepository.save(customer);
    }

    /** Una linea pedida: que producto, cuantas unidades y a que precio unitario sin IVA. */
    public record LineaFactura(Long productId, Integer quantity, Double price) {
    }

    /**
     * Emite una factura: descuenta el stock, congela el IVA de cada linea y, si es de
     * contado, registra el ingreso en tesoreria. Todo en una transaccion.
     *
     * Flujo completo en 01-ARCHITECTURE.md, seccion Modulo Sales.
     */
    @Transactional
    public Sale crearFactura(String invoiceNumber,
                             Customer cliente,
                             LocalDate invoiceDate,
                             LocalDate dueDate,
                             PaymentType paymentType,
                             Account cuenta,
                             String paymentMethod,
                             List<LineaFactura> lineas) {

        validarNumeroFacturaUnico(invoiceNumber, null);

        if (lineas == null || lineas.isEmpty()) {
            throw new InvalidSaleOperationException("Una factura necesita al menos una linea");
        }
        if (paymentType == PaymentType.CONTADO && cuenta == null) {
            throw new InvalidSaleOperationException(
                    "Una factura de contado necesita la cuenta donde entra el dinero");
        }

        Sale venta = new Sale();
        venta.setInvoiceNumber(invoiceNumber);
        venta.setCustomer(cliente);
        venta.setInvoiceDate(invoiceDate != null ? invoiceDate : LocalDate.now());
        venta.setDueDate(dueDate);
        venta.setPaymentType(paymentType);
        venta.setAccount(cuenta);
        venta.setPaymentMethod(paymentMethod);
        // Se persiste primero para que las lineas tengan a que colgarse.
        venta.setStatus(SaleStatus.PENDIENTE);
        venta.setSubtotal(0.0);
        venta.setIvaPorPagar(0.0);
        venta.setTotal(0.0);
        Sale guardada = saleRepository.save(venta);

        double subtotal = 0.0;
        double ivaGenerado = 0.0;
        double ivaDescontable = 0.0;

        for (LineaFactura linea : lineas) {
            Product producto = inventoryService.findProductById(linea.productId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Producto no encontrado: " + linea.productId()));

            int cantidad = linea.quantity() != null ? linea.quantity() : 0;
            if (cantidad <= 0) {
                throw new InvalidSaleOperationException(
                        "La cantidad de " + producto.getDescription() + " debe ser mayor a cero");
            }
            descontarStock(producto, cantidad);

            double precio = linea.price() != null ? linea.price() : 0.0;
            double baseLinea = precio * cantidad;
            // Congelado aqui: la tasa que rige es la del momento de facturar.
            double ivaLinea = baseLinea * inventoryService.getIvaRateForProduct(producto);

            SaleDetail detalle = new SaleDetail();
            detalle.setSale(guardada);
            detalle.setProduct(producto);
            detalle.setQuantity(cantidad);
            detalle.setPrice(precio);
            detalle.setIvaAmount(ivaLinea);
            saleDetailRepository.save(detalle);

            subtotal += baseLinea;
            ivaGenerado += ivaLinea;
            // IVA a favor: el que ya se pago al comprar esas unidades.
            ivaDescontable += (producto.getTaxAmount() != null ? producto.getTaxAmount() : 0.0) * cantidad;
        }

        guardada.setSubtotal(subtotal);
        guardada.setIvaPorPagar(ivaGenerado - ivaDescontable);
        guardada.setTotal(subtotal + ivaGenerado);

        if (paymentType == PaymentType.CONTADO) {
            guardada.setStatus(SaleStatus.PAGADA);
            treasuryService.registrarIngresoDeVenta(
                    cuenta, "Venta " + invoiceNumber, guardada.getTotal(), guardada);
        } else {
            guardada.setStatus(SaleStatus.PENDIENTE);
        }

        return saleRepository.save(guardada);
    }

    /**
     * Abono a una factura a credito. Si con este abono la factura queda cubierta, pasa a
     * PAGADA. Se llama abono, no pago ni cobro, ver 02-CONVENTIONS.md.
     */
    @Transactional
    public Collection registrarAbono(Long saleId, Long accountId, Double monto) {
        Sale venta = findSaleById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("Factura no encontrada: " + saleId));

        if (venta.getStatus() != SaleStatus.PENDIENTE) {
            throw new InvalidSaleOperationException(
                    "Solo se puede abonar a una factura PENDIENTE, y esta esta " + venta.getStatus());
        }
        if (monto == null || monto <= 0) {
            throw new InvalidSaleOperationException("El monto del abono debe ser mayor a cero");
        }

        double pendiente = saldoPendiente(venta);
        if (monto - pendiente > TOLERANCIA_PESOS) {
            throw new InvalidSaleOperationException(
                    "El abono de " + monto + " excede el saldo pendiente de " + pendiente);
        }

        Account cuenta = treasuryService.findAccountById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Cuenta no encontrada: " + accountId));

        Collection abono = new Collection();
        abono.setSale(venta);
        abono.setAccount(cuenta);
        abono.setAmount(monto);
        abono.setDate(LocalDate.now());
        Collection guardado = collectionRepository.save(abono);

        treasuryService.registrarIngresoDeVenta(
                cuenta, "Abono factura " + venta.getInvoiceNumber(), monto, venta);

        if (saldoPendiente(venta) <= TOLERANCIA_PESOS) {
            venta.setStatus(SaleStatus.PAGADA);
        }
        // La cuenta por la que entro el ultimo abono queda como la de la factura, igual
        // que en Autollantas.
        venta.setAccount(cuenta);
        saleRepository.save(venta);

        return guardado;
    }

    /**
     * Anula una factura. Portado de SalesService.cancelSale de Autollantas:
     *
     * * PAGADA: devuelve el stock, borra los movimientos de tesoreria de la venta y resta
     *   su importe del saldo de la cuenta.
     * * PENDIENTE sin abonos: solo devuelve el stock, tesoreria no se toca porque nunca
     *   entro dinero.
     *
     * En los dos casos queda ANULADA. Una factura ya ANULADA no se puede volver a anular:
     * devolveria el stock por segunda vez.
     */
    @Transactional
    public Sale anularFactura(Long saleId) {
        Sale venta = findSaleById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("Factura no encontrada: " + saleId));

        if (venta.getStatus() == SaleStatus.ANULADA) {
            throw new InvalidSaleOperationException("La factura " + venta.getInvoiceNumber()
                    + " ya esta anulada");
        }

        // Deshace el efecto en tesoreria antes de tocar nada mas. Si la venta no genero
        // ningun movimiento (credito sin abonos), esto no hace nada.
        treasuryService.revertirMovimientosDeVenta(venta);

        for (SaleDetail detalle : saleDetailRepository.findBySaleId(venta.getId())) {
            restaurarStock(detalle.getProduct(), detalle.getQuantity());
        }

        venta.setStatus(SaleStatus.ANULADA);
        return saleRepository.save(venta);
    }

    /** Lo que falta por cobrar de una factura: su total menos los abonos ya recibidos. */
    @Transactional(readOnly = true)
    public double saldoPendiente(Sale venta) {
        double abonado = collectionRepository.findBySaleId(venta.getId()).stream()
                .mapToDouble(c -> c.getAmount() != null ? c.getAmount() : 0.0)
                .sum();
        double total = venta.getTotal() != null ? venta.getTotal() : 0.0;
        double pendiente = total - abonado;
        return pendiente < TOLERANCIA_PESOS ? 0.0 : pendiente;
    }

    /**
     * La restriccion uk_venta_tenant_invoice_number tambien lo impide, pero salta como un
     * error crudo de constraint violation. Esto lo convierte en un mensaje legible antes
     * de llegar al INSERT. Mismo patron que el codigo de producto y el nombre de cuenta.
     */
    private void validarNumeroFacturaUnico(String invoiceNumber, Long idPropio) {
        if (invoiceNumber == null || invoiceNumber.isBlank()) {
            throw new InvalidSaleOperationException("La factura necesita un numero");
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        saleRepository.findByInvoiceNumberIgnoreCaseAndTenantId(invoiceNumber, tenantId)
                .filter(existente -> !existente.getId().equals(idPropio))
                .ifPresent(existente -> {
                    throw new DuplicateInvoiceNumberException(invoiceNumber);
                });
    }

    private void descontarStock(Product producto, int cantidad) {
        int disponible = producto.getQuantity() != null ? producto.getQuantity() : 0;
        if (disponible < cantidad) {
            throw new InsufficientStockException(producto.getDescription(), disponible, cantidad);
        }
        producto.setQuantity(disponible - cantidad);
        productRepository.save(producto);
    }

    /**
     * Se guarda por el repositorio y no por InventoryService.saveProduct a proposito:
     * saveProduct revalida el codigo y rehace los precios sugeridos, y devolver stock no
     * es motivo para recalcularle el precio a un producto.
     */
    private void restaurarStock(Product producto, Integer cantidad) {
        if (producto == null || cantidad == null) {
            return;
        }
        int actual = producto.getQuantity() != null ? producto.getQuantity() : 0;
        producto.setQuantity(actual + cantidad);
        productRepository.save(producto);
    }

    /** Las lineas de una factura, para armar la respuesta sin exponer la entidad. */
    @Transactional(readOnly = true)
    public List<SaleDetail> detallesDe(Sale venta) {
        return new ArrayList<>(saleDetailRepository.findBySaleId(venta.getId()));
    }
}
