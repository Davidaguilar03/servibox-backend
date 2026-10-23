package com.servibox.backend.treasury.service;

import com.servibox.backend.purchases.entity.Purchase;
import com.servibox.backend.purchases.service.InsufficientBalanceException;
import com.servibox.backend.sales.entity.Sale;
import com.servibox.backend.shared.ResourceNotFoundException;
import com.servibox.backend.tenant.TenantContext;
import com.servibox.backend.treasury.entity.Account;
import com.servibox.backend.treasury.entity.Movement;
import com.servibox.backend.treasury.entity.MovementCategory;
import com.servibox.backend.treasury.entity.MovementType;
import com.servibox.backend.treasury.repository.AccountRepository;
import com.servibox.backend.treasury.repository.MovementRepository;
import com.servibox.backend.treasury.repository.MovementRepositoryCustom;
import com.servibox.backend.treasury.repository.MovementSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TreasuryService {

    private final AccountRepository accountRepository;
    private final MovementRepository movementRepository;
    /** El "hoy" de negocio, ver BusinessClockConfiguration. */
    private final Clock clock;

    /** Las dos patas de una transferencia: el EGRESO del origen y el INGRESO del destino. */
    public record Transferencia(Movement egreso, Movement ingreso) {
    }

    @Transactional(readOnly = true)
    public List<Account> findAllAccounts() {
        return accountRepository.findAll();
    }

    /**
     * No usa accountRepository.findById: el @Filter de Hibernate no se aplica a
     * EntityManager.find(), asi que ese camino devuelve cuentas de otros tenants.
     * Verificado con TreasuryTenantIsolationTest.
     */
    @Transactional(readOnly = true)
    public Optional<Account> findAccountById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return accountRepository.findByIdAndTenantId(id, tenantId);
    }

    /**
     * Una cuenta nueva arranca con currentBalance igual a su initialBalance: mientras no
     * haya movimientos, el saldo vivo es el saldo de apertura.
     */
    @Transactional
    public Account saveAccount(Account account) {
        validarNombreUnico(account);
        if (account.getInitialBalance() == null) {
            account.setInitialBalance(0.0);
        }
        if (account.getCurrentBalance() == null) {
            account.setCurrentBalance(account.getInitialBalance());
        }
        return accountRepository.save(account);
    }

    /**
     * La base tambien lo impide con uk_cuenta_tenant_name, pero esa restriccion salta
     * como un error crudo de constraint violation que no le sirve a nadie. Esto lo
     * convierte en un mensaje legible antes de llegar al INSERT.
     *
     * Al editar hay que excluir el propio registro: guardar una cuenta sin cambiarle el
     * nombre no es un duplicado.
     */
    private void validarNombreUnico(Account account) {
        if (account.getName() == null || account.getName().isBlank()) {
            return;
        }
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return;
        }
        accountRepository.findByNameAndTenantId(account.getName(), tenantId)
                .filter(existente -> !existente.getId().equals(account.getId()))
                .ifPresent(existente -> {
                    throw new DuplicateAccountNameException(account.getName());
                });
    }

    // ---- Consultas de movimientos ----

    @Transactional(readOnly = true)
    public Optional<Movement> findMovementById(Long id) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return Optional.empty();
        }
        return movementRepository.findByIdAndTenantId(id, tenantId);
    }

    /** Extracto de una cuenta: sus movimientos activos, del mas reciente al mas antiguo. */
    @Transactional(readOnly = true)
    public List<Movement> findMovementsByAccountId(Long accountId) {
        return buscarMovimientos(accountId, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<Movement> buscarMovimientos(Long accountId, MovementCategory categoria,
                                            LocalDate desde, LocalDate hasta) {
        return buscarMovimientos(accountId, categoria, desde, hasta, null, null);
    }

    /**
     * Movimientos **activos** del tenant activo, del mas reciente al mas antiguo. Cada
     * filtro es opcional: null no filtra, y solo se agregan al where los que vienen. El
     * tenant lo pone el @Filter de Hibernate, que tambien aplica a las consultas Criteria.
     * Los de una factura ANULADA no salen nunca, ni filtrando por su saleId o purchaseId.
     */
    @Transactional(readOnly = true)
    public List<Movement> buscarMovimientos(Long accountId, MovementCategory categoria,
                                            LocalDate desde, LocalDate hasta,
                                            Long saleId, Long purchaseId) {
        return movementRepository.findAll(filtroActivos(accountId, categoria, desde, hasta, saleId, purchaseId),
                Sort.by(Sort.Order.desc("date"), Sort.Order.desc("id")));
    }

    /**
     * Movimientos activos del rango para el reporte de movimientos, del mas antiguo al mas
     * reciente, en una sola consulta sin cargar entidades. Mismo filtro que
     * buscarMovimientos, asi que los de una factura ANULADA no salen. A lo sumo `maximo`.
     */
    @Transactional(readOnly = true)
    public List<MovementRepositoryCustom.FilaReporte> listarParaReporte(Long accountId, LocalDate desde,
                                                                        LocalDate hasta, int maximo) {
        return movementRepository.listarParaReporte(
                filtroActivos(accountId, null, desde, hasta, null, null), maximo);
    }

    /**
     * Saldo de una cuenta al cierre de un dia: balance inicial mas los movimientos activos
     * con fecha hasta ese dia, los ingresos sumando y los egresos restando. Es la invariante
     * de saldo_actual aplicada a una fecha: con una fecha posterior al ultimo movimiento da
     * exactamente currentBalance.
     */
    @Transactional(readOnly = true)
    public double saldoAl(Account cuenta, LocalDate fecha) {
        Specification<Movement> hastaElDia = filtroActivos(cuenta.getId(), null, null, fecha, null, null);
        double ingresos = movementRepository.sumarMonto(hastaElDia.and(
                (raiz, consulta, cb) -> cb.equal(raiz.get("type"), MovementType.INGRESO)));
        double egresos = movementRepository.sumarMonto(hastaElDia.and(
                (raiz, consulta, cb) -> cb.equal(raiz.get("type"), MovementType.EGRESO)));
        double inicial = cuenta.getInitialBalance() != null ? cuenta.getInitialBalance() : 0.0;
        return inicial + ingresos - egresos;
    }

    /**
     * El filtro de buscarMovimientos y de las sumas: siempre MovementSpecifications.activo(),
     * mas cada filtro opcional que venga.
     */
    private static Specification<Movement> filtroActivos(Long accountId, MovementCategory categoria,
                                                         LocalDate desde, LocalDate hasta,
                                                         Long saleId, Long purchaseId) {
        Specification<Movement> filtro = MovementSpecifications.activo();
        if (saleId != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.equal(raiz.get("sale").get("id"), saleId));
        }
        if (purchaseId != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.equal(raiz.get("purchase").get("id"), purchaseId));
        }
        if (accountId != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.equal(raiz.get("account").get("id"), accountId));
        }
        if (categoria != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.equal(raiz.get("category"), categoria));
        }
        if (desde != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.greaterThanOrEqualTo(raiz.get("date"), desde));
        }
        if (hasta != null) {
            filtro = filtro.and((raiz, consulta, cb) -> cb.lessThanOrEqualTo(raiz.get("date"), hasta));
        }
        return filtro;
    }

    /**
     * Suma de los montos activos de una categoria en todo el historico del tenant. La suma
     * la hace la base, con el mismo filtro que el listado: la regla de movimiento activo
     * sigue viviendo solo en MovementSpecifications.
     */
    @Transactional(readOnly = true)
    public double sumarPorCategoria(MovementCategory categoria) {
        return movementRepository.sumarMonto(filtroActivos(null, categoria, null, null, null, null));
    }

    /** Suma de los montos activos de una categoria con fecha dentro de [desde, hasta]. */
    @Transactional(readOnly = true)
    public double sumarPorCategoria(MovementCategory categoria, LocalDate desde, LocalDate hasta) {
        return movementRepository.sumarMonto(filtroActivos(null, categoria, desde, hasta, null, null));
    }

    // ---- Movimientos manuales: gastos operativos e ingresos ocasionales ----

    /**
     * Ingreso puntual que no viene de una venta: reintegros, venta de chatarra, un aporte
     * del socio. Suma al saldo como cualquier INGRESO.
     */
    @Transactional
    public Movement registrarIngresoOcasional(Account cuenta, String concepto, Double monto,
                                              LocalDate fecha, String notas) {
        return registrarMovimientoManual(cuenta, MovementCategory.INGRESO_OCASIONAL, concepto, monto, fecha, notas);
    }

    /**
     * Gasto puntual que no viene de una factura de compra: arriendo, servicios, papeleria.
     *
     * **No exige saldo suficiente**, a diferencia de registrarEgresoDeCompra. Autollantas
     * tampoco: saveOperationalExpense resta del saldo sin comprobar nada, asi que una
     * cuenta puede quedar en negativo. Ver 03-DECISIONS.md.
     */
    @Transactional
    public Movement registrarGastoOperativo(Account cuenta, String concepto, Double monto,
                                            LocalDate fecha, String notas) {
        return registrarMovimientoManual(cuenta, MovementCategory.GASTO_OPERATIVO, concepto, monto, fecha, notas);
    }

    /**
     * Crea un movimiento manual. Solo GASTO_OPERATIVO (EGRESO) e INGRESO_OCASIONAL
     * (INGRESO): el tipo sale de la categoria. Cualquier otra se rechaza, porque se
     * gestiona desde su operacion.
     */
    @Transactional
    public Movement registrarMovimientoManual(Account cuenta, MovementCategory categoria, String concepto,
                                              Double monto, LocalDate fecha, String notas) {
        exigirCategoriaManual(categoria);
        validarManual(cuenta, monto);
        validarTextosManual(concepto, notas);

        Movement movimiento = new Movement();
        movimiento.setCategory(categoria);
        movimiento.setType(tipoDeManual(categoria));
        movimiento.setAccount(recargar(cuenta));
        movimiento.setConcept(concepto);
        movimiento.setAmount(monto);
        movimiento.setDate(fecha);
        movimiento.setNotes(notas);
        return aplicarMovimiento(movimiento);
    }

    /**
     * Edita un movimiento manual en su misma fila: revierte el efecto anterior **sobre la
     * cuenta anterior**, actualiza los campos y aplica el efecto nuevo sobre la cuenta
     * nueva. Que la reversion mire la cuenta vieja es lo que hace que cambiar de cuenta
     * salga bien: el dinero vuelve de donde salio. La categoria no cambia.
     */
    @Transactional
    public Movement editarMovimientoManual(Long movimientoId, Account nuevaCuenta, String nuevoConcepto,
                                           Double nuevoMonto, LocalDate nuevaFecha, String nuevasNotas) {
        Movement movimiento = movimientoManual(movimientoId);
        validarManual(nuevaCuenta, nuevoMonto);
        validarTextosManual(nuevoConcepto, nuevasNotas);

        deshacerEfecto(movimiento);

        movimiento.setAccount(recargar(nuevaCuenta));
        movimiento.setConcept(nuevoConcepto);
        movimiento.setAmount(nuevoMonto);
        movimiento.setDate(nuevaFecha);
        movimiento.setNotes(nuevasNotas);
        return aplicarMovimiento(movimiento);
    }

    /**
     * Elimina un movimiento manual: revierte su efecto y **borra la fila**. Es lo que hace
     * "Eliminar" en Autollantas: un gasto o un ingreso ocasional no es un documento fiscal
     * ni tiene estado, no hay nada que conservar.
     */
    @Transactional
    public void eliminarMovimientoManual(Long movimientoId) {
        Movement movimiento = movimientoManual(movimientoId);
        deshacerEfecto(movimiento);
        movementRepository.delete(movimiento);
    }

    /** El movimiento del tenant activo, o 404; y 400 si no es manual. */
    private Movement movimientoManual(Long movimientoId) {
        Movement movimiento = findMovementById(movimientoId)
                .orElseThrow(() -> new ResourceNotFoundException("Movimiento no encontrado: " + movimientoId));
        if (movimiento.getCategory() == null || !movimiento.getCategory().esManual()) {
            throw new InvalidMovementOperationException("El movimiento " + movimientoId + " es de categoria "
                    + movimiento.getCategory() + " y se gestiona desde su operacion");
        }
        return movimiento;
    }

    private void exigirCategoriaManual(MovementCategory categoria) {
        if (categoria == null || !categoria.esManual()) {
            throw new InvalidMovementOperationException("Un movimiento manual es GASTO_OPERATIVO o "
                    + "INGRESO_OCASIONAL; " + categoria + " se gestiona desde su operacion");
        }
    }

    private static void validarManual(Account cuenta, Double monto) {
        if (cuenta == null) {
            throw new IllegalArgumentException("El movimiento necesita una cuenta");
        }
        if (monto == null || monto <= 0) {
            throw new IllegalArgumentException("El monto del movimiento debe ser mayor a cero");
        }
    }

    /** Largo de concepto y notas en MOVIMIENTOS (VARCHAR 255): 400 y no un error de columna. */
    private static void validarTextosManual(String concepto, String notas) {
        if (concepto != null && concepto.length() > 255) {
            throw new IllegalArgumentException("El concepto admite hasta 255 caracteres");
        }
        if (notas != null && notas.length() > 255) {
            throw new IllegalArgumentException("Las notas admiten hasta 255 caracteres");
        }
    }

    private static MovementType tipoDeManual(MovementCategory categoria) {
        return categoria == MovementCategory.GASTO_OPERATIVO ? MovementType.EGRESO : MovementType.INGRESO;
    }

    // ---- Transferencias ----

    /**
     * Debita el origen y acredita el destino por el mismo monto, en una sola transaccion:
     * o se mueven los dos saldos o no se mueve ninguno. El dinero se conserva. El origen
     * tiene que tener saldo para el monto (InsufficientBalanceException, 409), igual que una
     * compra de contado; los gastos operativos siguen sin exigirlo.
     *
     * Son dos movimientos de categoria TRANSFERENCIA: un EGRESO en el origen y un INGRESO
     * en el destino que apunta al EGRESO con relatedMovement. No hay tabla de
     * transferencias. El concepto de las dos patas es el que escribe el usuario, sin
     * espacios sobrantes; si no escribe ninguno, el autogenerado ("Transferencia a X" y
     * "Transferencia desde Y"). Las notas quedan vacias.
     */
    @Transactional
    public Transferencia registrarTransferencia(Account cuentaOrigen, Account cuentaDestino,
                                                String concepto, Double monto, LocalDate fecha) {
        if (monto == null || monto <= 0) {
            throw new InvalidTransferException("El monto de la transferencia debe ser mayor a cero");
        }
        if (cuentaOrigen == null || cuentaDestino == null) {
            throw new InvalidTransferException("La transferencia necesita cuenta origen y cuenta destino");
        }
        if (cuentaOrigen.getId() != null && cuentaOrigen.getId().equals(cuentaDestino.getId())) {
            throw new InvalidTransferException("La cuenta origen y la cuenta destino no pueden ser la misma");
        }

        // Antes de mover nada y en la misma transaccion, como en las compras de contado.
        exigirSaldoSuficiente(cuentaOrigen, monto);

        Account origen = recargar(cuentaOrigen);
        Account destino = recargar(cuentaDestino);

        String delUsuario = concepto != null && !concepto.isBlank() ? concepto.strip() : null;

        Movement egreso = new Movement();
        egreso.setCategory(MovementCategory.TRANSFERENCIA);
        egreso.setType(MovementType.EGRESO);
        egreso.setAccount(origen);
        egreso.setConcept(delUsuario != null ? delUsuario : "Transferencia a " + destino.getName());
        egreso.setAmount(monto);
        egreso.setDate(fecha);
        Movement egresoGuardado = aplicarMovimiento(egreso);

        Movement ingreso = new Movement();
        ingreso.setCategory(MovementCategory.TRANSFERENCIA);
        ingreso.setType(MovementType.INGRESO);
        ingreso.setAccount(destino);
        ingreso.setConcept(delUsuario != null ? delUsuario : "Transferencia desde " + origen.getName());
        ingreso.setAmount(monto);
        ingreso.setDate(fecha);
        ingreso.setRelatedMovement(egresoGuardado);
        Movement ingresoGuardado = aplicarMovimiento(ingreso);

        return new Transferencia(egresoGuardado, ingresoGuardado);
    }

    // ---- Movimientos de ventas y compras ----

    /**
     * Ingreso generado por una venta: el del contado al facturar, o un abono. Categoria
     * RECAUDO_VENTA, enlazado a la venta por id_venta. La fecha es la de negocio (la de la
     * factura o la del abono).
     */
    @Transactional
    public Movement registrarIngresoDeVenta(Account cuenta, String concepto, Double monto, Sale venta,
                                            LocalDate fecha) {
        Movement movimiento = new Movement();
        movimiento.setCategory(MovementCategory.RECAUDO_VENTA);
        movimiento.setType(MovementType.INGRESO);
        movimiento.setAccount(recargar(cuenta));
        movimiento.setConcept(concepto);
        movimiento.setAmount(monto);
        movimiento.setDate(fecha);
        movimiento.setSale(venta);
        return aplicarMovimiento(movimiento);
    }

    /**
     * Egreso generado por una compra: el del contado al facturar, o un pago. Categoria
     * PAGO_COMPRA, enlazado a la compra por id_compra.
     *
     * Valida que la cuenta tenga saldo. En Autollantas esa comprobacion vive en el
     * formulario; aqui vive donde se mueve el dinero, que es el unico sitio por el que
     * pasan todos los egresos de compras.
     */
    @Transactional
    public Movement registrarEgresoDeCompra(Account cuenta, String concepto, Double monto, Purchase compra,
                                            LocalDate fecha) {
        Account gestionada = recargar(cuenta);
        exigirSaldoSuficiente(gestionada, monto);
        Movement movimiento = new Movement();
        movimiento.setCategory(MovementCategory.PAGO_COMPRA);
        movimiento.setType(MovementType.EGRESO);
        movimiento.setAccount(gestionada);
        movimiento.setConcept(concepto);
        movimiento.setAmount(monto);
        movimiento.setDate(fecha);
        movimiento.setPurchase(compra);
        return aplicarMovimiento(movimiento);
    }

    /** Lanza InsufficientBalanceException si la cuenta no puede cubrir el monto. */
    @Transactional(readOnly = true)
    public void exigirSaldoSuficiente(Account cuenta, Double monto) {
        Account gestionada = recargar(cuenta);
        double saldo = saldoDe(gestionada);
        double requerido = monto != null ? monto : 0.0;
        if (saldo < requerido) {
            throw new InsufficientBalanceException(gestionada.getName(), saldo, requerido);
        }
    }

    /**
     * Todos los movimientos de una venta, **activos o no**, en orden de creacion. Para el
     * saldo pendiente, anular y restaurar; nunca para mostrar ni sumar en tesoreria.
     */
    @Transactional(readOnly = true)
    public List<Movement> movimientosDeVenta(Sale venta) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return List.of();
        }
        return movementRepository.findBySaleIdAndTenantIdOrderByIdAsc(venta.getId(), tenantId);
    }

    /** Todos los movimientos de una compra, activos o no. Mismo uso que movimientosDeVenta. */
    @Transactional(readOnly = true)
    public List<Movement> movimientosDeCompra(Purchase compra) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            return List.of();
        }
        return movementRepository.findByPurchaseIdAndTenantIdOrderByIdAsc(compra.getId(), tenantId);
    }

    /**
     * Al anular una venta: deshace el efecto de cada uno de sus movimientos sobre el saldo
     * de su cuenta, sin validar nada. **No borra filas**: con la venta ANULADA quedan
     * inactivos. Ver "Anular y restaurar facturas" en 04-DATA-MODEL.md.
     */
    @Transactional
    public void revertirSaldosDeVenta(Sale venta) {
        movimientosDeVenta(venta).forEach(this::deshacerEfecto);
    }

    /** Espejo de revertirSaldosDeVenta: cada egreso de la compra vuelve a su cuenta. */
    @Transactional
    public void revertirSaldosDeCompra(Purchase compra) {
        movimientosDeCompra(compra).forEach(this::deshacerEfecto);
    }

    /**
     * Al restaurar una venta: vuelve a aplicar cada uno de sus movimientos sobre el saldo de
     * su cuenta. Son ingresos, asi que no hay nada que validar.
     */
    @Transactional
    public void reaplicarSaldosDeVenta(Sale venta) {
        movimientosDeVenta(venta).forEach(this::aplicarEfecto);
    }

    /**
     * Al restaurar una compra: vuelve a aplicar cada egreso con la regla de saldo suficiente
     * de crear y pagar. Primero lo simula entero, acumulando sobre el saldo corriente
     * cuando varios egresos salen de la misma cuenta, y solo si todos alcanzan toca algun
     * saldo: si uno no alcanza, InsufficientBalanceException sin haber escrito nada.
     */
    @Transactional
    public void reaplicarSaldosDeCompra(Purchase compra) {
        List<Movement> egresos = movimientosDeCompra(compra);
        Map<Long, Double> saldoCorriente = new HashMap<>();
        for (Movement egreso : egresos) {
            Account cuenta = recargar(egreso.getAccount());
            double saldo = saldoCorriente.computeIfAbsent(cuenta.getId(), id -> saldoDe(cuenta));
            double monto = egreso.getAmount() != null ? egreso.getAmount() : 0.0;
            if (saldo < monto) {
                throw new InsufficientBalanceException(cuenta.getName(), saldo, monto);
            }
            saldoCorriente.put(cuenta.getId(), saldo - monto);
        }
        egresos.forEach(this::aplicarEfecto);
    }

    // ---- Saldos ----

    /**
     * Unico sitio donde se guarda un Movement nuevo o editado, junto con su efecto sobre el
     * saldo de su cuenta. Las dos cosas van juntas a proposito: un movimiento guardado sin
     * mover el saldo, o un saldo movido sin movimiento que lo explique, es un descuadre
     * silencioso.
     *
     * Valida la coherencia entre tipo, categoria y factura antes de escribir nada.
     */
    private Movement aplicarMovimiento(Movement movimiento) {
        validarCoherencia(movimiento);
        if (movimiento.getDate() == null) {
            movimiento.setDate(LocalDate.now(clock));
        }
        Movement guardado = movementRepository.save(movimiento);
        aplicarEfecto(guardado);
        return guardado;
    }

    /**
     * Aplica el efecto de un movimiento sobre **su** cuenta: un INGRESO suma, un EGRESO
     * resta. Lo usan aplicarMovimiento y restaurar una factura, cuyos movimientos ya
     * existen. Junto con deshacerEfecto, son las unicas escrituras de currentBalance.
     */
    private void aplicarEfecto(Movement movimiento) {
        Account cuenta = recargar(movimiento.getAccount());
        double monto = movimiento.getAmount() != null ? movimiento.getAmount() : 0.0;
        cuenta.setCurrentBalance(movimiento.getType() == MovementType.INGRESO
                ? saldoDe(cuenta) + monto
                : saldoDe(cuenta) - monto);
        accountRepository.save(cuenta);
    }

    /**
     * Revierte el efecto de un movimiento sobre **su** cuenta: un INGRESO se resta, un
     * EGRESO se suma. La usan editar o eliminar un movimiento manual y anular una factura.
     * El motivo de que anular sea una reversion y no un contra-movimiento esta en
     * 03-DECISIONS.md.
     */
    private void deshacerEfecto(Movement movimiento) {
        Account cuenta = movimiento.getAccount();
        if (cuenta == null) {
            return;
        }
        double monto = movimiento.getAmount() != null ? movimiento.getAmount() : 0.0;
        Account gestionada = recargar(cuenta);
        gestionada.setCurrentBalance(movimiento.getType() == MovementType.INGRESO
                ? saldoDe(gestionada) - monto
                : saldoDe(gestionada) + monto);
        accountRepository.save(gestionada);
    }

    /**
     * GASTO_OPERATIVO y PAGO_COMPRA son EGRESO; INGRESO_OCASIONAL y RECAUDO_VENTA son
     * INGRESO; TRANSFERENCIA admite los dos (una pata cada uno). RECAUDO_VENTA lleva venta
     * y no compra, PAGO_COMPRA compra y no venta, y las demas ninguna de las dos: la misma
     * regla que ck_movimiento_factura en la base.
     */
    private static void validarCoherencia(Movement movimiento) {
        MovementType tipo = movimiento.getType();
        MovementCategory categoria = movimiento.getCategory();
        if (tipo == null || categoria == null) {
            throw new IllegalArgumentException("Un movimiento necesita tipo y categoria");
        }
        boolean coherente = switch (categoria) {
            case GASTO_OPERATIVO, PAGO_COMPRA -> tipo == MovementType.EGRESO;
            case INGRESO_OCASIONAL, RECAUDO_VENTA -> tipo == MovementType.INGRESO;
            case TRANSFERENCIA -> true;
        };
        if (!coherente) {
            throw new IllegalArgumentException("Un movimiento de categoria " + categoria + " no puede ser " + tipo);
        }
        boolean conVenta = movimiento.getSale() != null;
        boolean conCompra = movimiento.getPurchase() != null;
        boolean facturaCoherente = switch (categoria) {
            case RECAUDO_VENTA -> conVenta && !conCompra;
            case PAGO_COMPRA -> conCompra && !conVenta;
            default -> !conVenta && !conCompra;
        };
        if (!facturaCoherente) {
            throw new IllegalArgumentException("Un movimiento de categoria " + categoria
                    + " no puede ir enlazado a esa factura (venta: " + conVenta + ", compra: " + conCompra + ")");
        }
    }

    /**
     * El "Total Global" de Accounts.fxml: suma de los saldos vivos de todas las cuentas.
     * El filtro de Hibernate ya limita findAll al tenant activo.
     */
    @Transactional(readOnly = true)
    public double balanceGlobal() {
        return accountRepository.findAll().stream()
                .mapToDouble(this::saldoDe)
                .sum();
    }

    /**
     * Trae la instancia gestionada por la sesion. Si llega una cuenta desprendida con un
     * saldo viejo en memoria, actualizarla escribiria un saldo equivocado.
     */
    private Account recargar(Account cuenta) {
        if (cuenta.getId() == null) {
            return cuenta;
        }
        return findAccountById(cuenta.getId()).orElse(cuenta);
    }

    private double saldoDe(Account cuenta) {
        return cuenta.getCurrentBalance() != null ? cuenta.getCurrentBalance() : 0.0;
    }
}
