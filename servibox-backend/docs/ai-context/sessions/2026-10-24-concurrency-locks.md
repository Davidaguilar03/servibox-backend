# 2026-10-24: Bloqueo pesimista de saldos y stock

Rama `fix/33-concurrency-locks`. Objetivo: que peticiones simultaneas no pierdan
actualizaciones del saldo de una cuenta ni del stock de un producto, y que dos compras
simultaneas del mismo proveedor con FAC-300 y fac-300 no entren las dos.

## Lo que habia

* La verificacion de V1 a V3 contra PostgreSQL 16 mostro el Banco en 781000.00 con
  movimientos que sumaban 745300.00, y un producto en 18 unidades en vez de 21.
* `currentBalance` lo escriben solo `TreasuryService.aplicarEfecto` y `deshacerEfecto`;
  `quantity`, `descontarStock` y `restaurarStock` en ventas y `aumentarStock` y
  `quitarStock` en compras. Los recalculos de precio (editar producto, editar categoria,
  cambiar margen, configuracion financiera) reescriben la fila entera del producto, stock
  incluido. Todas leian con `findByIdAndTenantId` sin bloqueo.
* Abonar, pagar, anular y restaurar leian el estado y el saldo pendiente de la factura y
  despues escribian, sin bloquear la factura.
* Las transacciones son las de los metodos publicos de los services; el controlador carga
  antes la factura, la cuenta o el producto (open-in-view), asi que esas entidades ya
  estaban en el contexto de persistencia cuando el service las volvia a leer.

## Lo que se hizo

* `findByIdAndTenantIdForUpdate` (`@Lock(PESSIMISTIC_WRITE)`) en los repositorios de
  cuentas, productos, ventas, compras, movimientos y terceros.
* `TreasuryService.bloquearCuentas` e `InventoryService.bloquearProductos`: orden
  ascendente de id, sin repetir, y refresh. Facturas, movimiento manual y proveedor se
  bloquean y refrescan en su service.
* Orden: documento, cuentas, productos. Bloqueo antes de validar.
* `spring.jpa.properties.jakarta.persistence.lock.timeout=5000` y 409 "El recurso esta siendo
  modificado por otra operacion, intenta de nuevo" en `GlobalExceptionHandler`.
* Docs: 03-DECISIONS ("Bloqueo pesimista de saldos y stock"), 02-CONVENTIONS (Bloqueos),
  01-ARCHITECTURE (tabla de errores y Base de datos).

## Pruebas

* `concurrency.ConcurrentOperationsTest` (10): cobros y pagos a una cuenta, ventas y
  compras del mismo producto, stock insuficiente, transferencias cruzadas y sin saldo,
  gastos manuales, FAC-300 y fac-300, abonos que superan el pendiente, y anular y abonar a
  la vez. Antes del arreglo fallaban las 10; despues pasan, 5 rondas seguidas.
* `concurrency.LockTimeoutTest` (1): un bloqueo sostenido hace que otra peticion responda
  409 tras unos 5 s.
* Las ventas de esas pruebas llevan numero explicito: el consecutivo FV- automatico no se
  reserva y dos ventas simultaneas sin numero chocan con 409, como antes.
* Ninguna prueba existente cambio. Total: 357 antes, 368 despues.

## Verificacion real

* H2 (perfil dev, puerto 8091): rafagas de 20 peticiones simultaneas de ventas, pagos,
  compras, FAC-300/fac-300 y transferencias cruzadas. Saldos y stock cuadran por API;
  ningun 500 ni 409 de bloqueo; una sola FAC-300.
* PostgreSQL 16 (perfil prod, contenedor temporal): las mismas rafagas; cuadre por SQL de
  `saldo_actual` contra balance inicial mas movimientos activos y del stock. Hibernate emite
  `set local lock_timeout = 5000` y `for no key update`. Un bloqueo retenido desde psql da
  409 a los 5 s. Contenedor y volumen eliminados.

## Pendiente

* Numeracion automatica de ventas bajo concurrencia (409 por numero repetido). Sin revisar:
  la primera creacion simultanea del Consumidor final.
* Montos en `BigDecimal`.
