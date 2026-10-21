package com.servibox.backend.shared;

import com.servibox.backend.auth.InvalidCredentialsException;
import com.servibox.backend.counterparties.service.DuplicateCounterpartyDocumentException;
import com.servibox.backend.counterparties.service.InvalidCounterpartyException;
import com.servibox.backend.inventory.service.DuplicateCategoryNameException;
import com.servibox.backend.inventory.service.DuplicateFinancialSettingsException;
import com.servibox.backend.inventory.service.DuplicateProductCodeException;
import com.servibox.backend.inventory.service.ProductCodeLockedException;
import com.servibox.backend.purchases.service.DuplicatePurchaseInvoiceNumberException;
import com.servibox.backend.purchases.service.InsufficientBalanceException;
import com.servibox.backend.purchases.service.InvalidPurchaseOperationException;
import com.servibox.backend.sales.service.DuplicateInvoiceNumberException;
import com.servibox.backend.sales.service.InsufficientStockException;
import com.servibox.backend.sales.service.InvalidSaleOperationException;
import com.servibox.backend.treasury.service.DuplicateAccountNameException;
import com.servibox.backend.treasury.service.InvalidTransferException;
import com.servibox.backend.treasury.service.InvalidMovementOperationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidFormatException;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Principalmente la que lanza TenantAwareEntity en @PrePersist cuando no hay tenant
     * activo en el contexto. Es un error de uso, no una falla del servidor.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> manejarEstadoInvalido(IllegalStateException ex) {
        log.warn("Peticion rechazada por estado invalido: {}", ex.getMessage());
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Choque con una restriccion de unicidad, por ejemplo dos productos con el mismo codigo. */
    @ExceptionHandler(DuplicateProductCodeException.class)
    public ResponseEntity<ErrorResponse> manejarDuplicado(DuplicateProductCodeException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Cambiar el codigo de un producto que ya tiene facturas. */
    @ExceptionHandler(ProductCodeLockedException.class)
    public ResponseEntity<ErrorResponse> manejarCodigoBloqueado(ProductCodeLockedException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Renombrar una categoria con el nombre de otra del mismo tenant. */
    @ExceptionHandler(DuplicateCategoryNameException.class)
    public ResponseEntity<ErrorResponse> manejarCategoriaDuplicada(DuplicateCategoryNameException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Dos cuentas del mismo tenant con el mismo nombre. */
    /** Segunda fila de CONFIGURACION_FINANCIERA para el mismo tenant. */
    @ExceptionHandler(DuplicateFinancialSettingsException.class)
    public ResponseEntity<ErrorResponse> manejarConfiguracionDuplicada(DuplicateFinancialSettingsException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(DuplicateAccountNameException.class)
    public ResponseEntity<ErrorResponse> manejarCuentaDuplicada(DuplicateAccountNameException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Dos facturas del mismo tenant con el mismo numero. */
    @ExceptionHandler(DuplicateInvoiceNumberException.class)
    public ResponseEntity<ErrorResponse> manejarFacturaDuplicada(DuplicateInvoiceNumberException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** No alcanza el stock para facturar lo pedido. */
    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<ErrorResponse> manejarStockInsuficiente(InsufficientStockException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Operacion que no cuadra con el estado de la factura (abono o anulacion invalida). */
    @ExceptionHandler(InvalidSaleOperationException.class)
    public ResponseEntity<ErrorResponse> manejarVentaInvalida(InvalidSaleOperationException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Dos facturas de compra del mismo tenant con el mismo numero. */
    @ExceptionHandler(DuplicatePurchaseInvoiceNumberException.class)
    public ResponseEntity<ErrorResponse> manejarCompraDuplicada(DuplicatePurchaseInvoiceNumberException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** La cuenta no tiene con que pagar el egreso pedido. */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ErrorResponse> manejarSaldoInsuficiente(InsufficientBalanceException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Operacion que no cuadra con el estado de la compra (pago o anulacion invalida). */
    @ExceptionHandler(InvalidPurchaseOperationException.class)
    public ResponseEntity<ErrorResponse> manejarCompraInvalida(InvalidPurchaseOperationException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Documento de tercero repetido dentro del tenant. */
    @ExceptionHandler(DuplicateCounterpartyDocumentException.class)
    public ResponseEntity<ErrorResponse> manejarTerceroDuplicado(DuplicateCounterpartyDocumentException ex) {
        return construir(HttpStatus.CONFLICT, ex.getMessage());
    }

    /** Tercero que no sirve para la operacion: rol equivocado o datos obligatorios vacios. */
    @ExceptionHandler(InvalidCounterpartyException.class)
    public ResponseEntity<ErrorResponse> manejarTerceroInvalido(InvalidCounterpartyException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Operacion manual sobre un movimiento que se gestiona desde su operacion. */
    @ExceptionHandler(InvalidMovementOperationException.class)
    public ResponseEntity<ErrorResponse> manejarMovimientoInvalido(InvalidMovementOperationException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Transferencia sin sentido: misma cuenta en los dos extremos, o monto no positivo. */
    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ErrorResponse> manejarTransferenciaInvalida(InvalidTransferException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    /** Referencia a algo que no existe, por ejemplo una categoria o producto por id. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> manejarArgumentoInvalido(IllegalArgumentException ex) {
        return construir(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({InvalidCredentialsException.class, AuthenticationException.class})
    public ResponseEntity<ErrorResponse> manejarCredenciales(Exception ex) {
        return construir(HttpStatus.UNAUTHORIZED, "Credenciales invalidas");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> manejarValidacion(MethodArgumentNotValidException ex) {
        return construir(HttpStatus.BAD_REQUEST, "Peticion invalida");
    }

    /**
     * Cuerpo que no se puede leer: JSON mal formado o un valor que no cabe en el tipo del
     * campo. Es un error del cliente, no del servidor. Si el valor es de un enum, el mensaje
     * nombra el campo y los valores permitidos.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> manejarCuerpoIlegible(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof InvalidFormatException formato
                && formato.getTargetType() != null && formato.getTargetType().isEnum()) {
            String campo = formato.getPath().stream()
                    .map(JacksonException.Reference::getPropertyName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("."));
            String permitidos = Arrays.stream(formato.getTargetType().getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return construir(HttpStatus.BAD_REQUEST, "Valor invalido '" + formato.getValue()
                    + "' en el campo " + campo + ". Valores permitidos: " + permitidos);
        }
        return construir(HttpStatus.BAD_REQUEST,
                "El cuerpo de la peticion no es un JSON valido o un campo tiene un valor del tipo equivocado");
    }

    /**
     * Parametro de query o de ruta con un valor que no cabe en su tipo, por ejemplo
     * ?category=X en GET /api/treasury/movements. Es un error del cliente: 400 con el nombre
     * del parametro y, si es un enum, los valores permitidos.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> manejarParametroInvalido(MethodArgumentTypeMismatchException ex) {
        Class<?> tipo = ex.getRequiredType();
        if (tipo != null && tipo.isEnum()) {
            String permitidos = Arrays.stream(tipo.getEnumConstants())
                    .map(String::valueOf)
                    .collect(Collectors.joining(", "));
            return construir(HttpStatus.BAD_REQUEST, "Valor invalido '" + ex.getValue()
                    + "' en el parametro " + ex.getName() + ". Valores permitidos: " + permitidos);
        }
        return construir(HttpStatus.BAD_REQUEST, "Valor invalido '" + ex.getValue()
                + "' en el parametro " + ex.getName());
    }

    /** Recurso pedido por la ruta que no existe para el tenant activo. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> manejarRecursoInexistente(ResourceNotFoundException ex) {
        return construir(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> manejarNoEncontrado(NoResourceFoundException ex) {
        return construir(HttpStatus.NOT_FOUND, "Recurso no encontrado");
    }

    /**
     * Ultimo recurso. El detalle va al log del servidor, nunca al cliente: un stacktrace
     * en la respuesta le regala al atacante versiones de librerias y rutas internas.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> manejarNoControlada(Exception ex) {
        log.error("Error no controlado", ex);
        return construir(HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    private ResponseEntity<ErrorResponse> construir(HttpStatus status, String mensaje) {
        return ResponseEntity.status(status).body(new ErrorResponse(mensaje, status.value()));
    }
}
