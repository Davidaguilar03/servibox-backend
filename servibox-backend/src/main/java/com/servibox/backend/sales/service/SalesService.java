package com.servibox.backend.sales.service;

import com.servibox.backend.counterparties.entity.Counterparty;
import com.servibox.backend.counterparties.entity.CounterpartyRole;
import com.servibox.backend.counterparties.service.CounterpartyService;
import com.servibox.backend.inventory.entity.Product;
import com.servibox.backend.inventory.repository.ProductRepository;
import com.servibox.backend.inventory.service.InventoryService;
import com.servibox.backend.sales.entity.PaymentType;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.sales.entity.SaleDetail;
import com.servibox.backend.sales.entity.SaleStatus;
import com.servibox.backend.sales.repository.SaleDetailRepository;
import com.servibox.backend.sales.repository.SaleRepository;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
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
    private final CounterpartyService counterpartyService;
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
                             Counterparty cliente,
                             LocalDate invoiceDate,
                             LocalDate dueDate,
                             PaymentType paymentType,
                             Account cuenta,
                             String paymentMethod,
                             List<LineaFactura> lineas) {

        validarNumeroFacturaUnico(invoiceNumber, null);
        // Toda venta tiene tercero (id_tercero NOT NULL en el diccionario): si no se
        // identifica al cliente, es el Consumidor final del tenant.
        if (cliente == null) {
            cliente = counterpartyService.consumidorFinal();
        } else {
            counterpartyService.exigirRol(cliente, CounterpartyRole.CLIENTE);
        }

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
            // IVA a favor: el que ya se pago al comprar esas unidades.
            double descontableLinea = (producto.getTaxAmount() != null ? producto.getTaxAmount() : 0.0) * cantidad;

            SaleDetail detalle = new SaleDetail();
            detalle.setSale(guardada);
            detalle.setProduct(producto);
            detalle.setQuantity(cantidad);
            detalle.setPrice(precio);
            detalle.setIvaAmount(ivaLinea);
            detalle.setIvaDifference(ivaLinea - descontableLinea);
            saleDetailRepository.save(detalle);

            subtotal += baseLinea;
            ivaGenerado += ivaLinea;
            ivaDescontable += descontableLinea;
        }

        guardada.setSubtotal(subtotal);
        guardada.setIvaPorPagar(ivaGenerado - ivaDescontable);
        guardada.setTotal(subtotal + ivaGenerado);

        if (paymentType == PaymentType.CONTADO) {
            guardada.setStatus(SaleStatus.PAGADA);
            treasuryService.registrarIngresoDeVenta(
                    cuenta, conceptoVenta(guardada), guardada.getTotal(), guardada, guardada.getInvoiceDate());
        } else {
            guardada.setStatus(SaleStatus.PENDIENTE);
        }

        return saleRepository.save(guardada);
    }

    /**
     * Abono a una factura a credito: un movimiento RECAUDO_VENTA enlazado a la venta. Si
     * con este abono la factura queda cubierta, pasa a PAGADA. Se llama abono, no pago ni
     * cobro, ver 02-CONVENTIONS.md.
     */
    @Transactional
    public Movement registrarAbono(Long saleId, Long accountId, Double monto) {
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

        Movement abono = treasuryService.registrarIngresoDeVenta(
                cuenta, conceptoAbono(venta), monto, venta, LocalDate.now());

        if (saldoPendiente(venta) <= TOLERANCIA_PESOS) {
            venta.setStatus(SaleStatus.PAGADA);
            saleRepository.save(venta);
        }

        return abono;
    }

    /**
     * Anula una factura: devuelve el stock y deshace en tesoreria el efecto de cada uno de
     * sus movimientos (el contado y los abonos) sobre el saldo de su cuenta, sin validar
     * nada. **No borra los movimientos**: con la factura ANULADA quedan inactivos y dejan de
     * salir en listados y sumas. Ver "Anular y restaurar facturas" en 04-DATA-MODEL.md.
     *
     * Una factura ya ANULADA no se puede volver a anular: devolveria el stock y el dinero
     * por segunda vez.
     */
    @Transactional
    public Sale anularFactura(Long saleId) {
        Sale venta = findSaleById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("Factura no encontrada: " + saleId));

        if (venta.getStatus() == SaleStatus.ANULADA) {
            throw new InvalidSaleOperationException("La factura " + venta.getInvoiceNumber()
                    + " ya esta anulada");
        }

        treasuryService.revertirSaldosDeVenta(venta);

        for (SaleDetail detalle : saleDetailRepository.findBySaleId(venta.getId())) {
            restaurarStock(detalle.getProduct(), detalle.getQuantity());
        }

        venta.setStatus(SaleStatus.ANULADA);
        return saleRepository.save(venta);
    }

    /**
     * Saca una factura de la papelera: el inverso exacto de anularFactura.
     *
     * * Vuelve a descontar el stock con el mismo descontarStock de crearFactura. Si una
     *   linea no alcanza, InsufficientStockException y la transaccion deshace todo.
     * * Vuelve a aplicar el saldo de cada movimiento de la factura, que nunca se borraron:
     *   son ingresos, no hay nada que validar.
     * * El estado se deriva: PAGADA si el saldo pendiente cae en la tolerancia, si no
     *   PENDIENTE.
     */
    @Transactional
    public Sale restaurarFactura(Long saleId) {
        Sale venta = findSaleById(saleId)
                .orElseThrow(() -> new IllegalArgumentException("Factura no encontrada: " + saleId));

        if (venta.getStatus() != SaleStatus.ANULADA) {
            throw new InvalidSaleOperationException("Solo se puede restaurar una factura ANULADA, y la "
                    + venta.getInvoiceNumber() + " esta " + venta.getStatus());
        }

        for (SaleDetail detalle : saleDetailRepository.findBySaleId(venta.getId())) {
            descontarStock(detalle.getProduct(), detalle.getQuantity());
        }

        treasuryService.reaplicarSaldosDeVenta(venta);

        venta.setStatus(saldoPendiente(venta) <= TOLERANCIA_PESOS ? SaleStatus.PAGADA : SaleStatus.PENDIENTE);
        return saleRepository.save(venta);
    }

    /** Concepto del ingreso de una venta de contado. Unica copia del texto. */
    private static String conceptoVenta(Sale venta) {
        return "Venta " + venta.getInvoiceNumber();
    }

    /** Concepto del ingreso de un abono. Unica copia del texto. */
    private static String conceptoAbono(Sale venta) {
        return "Abono factura " + venta.getInvoiceNumber();
    }

    /**
     * Lo que falta por cobrar de una factura: su total menos sus movimientos RECAUDO_VENTA
     * (el contado y los abonos). Cuenta todos, activos o no: una factura anulada conserva
     * lo que tenia pagado, y al restaurarla el estado sale de aqui.
     */
    @Transactional(readOnly = true)
    public double saldoPendiente(Sale venta) {
        double abonado = treasuryService.movimientosDeVenta(venta).stream()
                .mapToDouble(m -> m.getAmount() != null ? m.getAmount() : 0.0)
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
