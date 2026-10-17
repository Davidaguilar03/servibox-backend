# 2026-10-06: Refactor de normalizacion del modelo de datos

Objetivo: llevar el modelo de datos de ServiBox al esquema de
[04-DATA-MODEL.md](../04-DATA-MODEL.md) (14 entidades en tercera forma normal). Se hace
en cinco pasos, cada uno con `./mvnw test` completo en verde.

## Plan

- [x] **1. Terceros.** `Customer` y `Supplier` se unifican en `Counterparty` (`TERCEROS`),
  con rol CLIENTE, PROVEEDOR o AMBOS. Hecho el 2026-10-06, rama `refactor/15-terceros`.
- [x] **2. Movimientos con categoria.** Transferencias, ingresos ocasionales y gastos
  operativos pasan a ser movimientos con `categoria_movimiento`. Hecho el 2026-10-06, rama
  `refactor/16-unify-treasury-movements`.
- [x] **3. Abonos y pagos como movimientos.** Abonos y pagos pasan a ser movimientos con FK
  a la factura (`id_venta`, `id_compra`), y anular y restaurar se redisenan. Hecho el
  2026-10-17, rama `refactor/17-collections-payments-as-movements`.
- [ ] **4. Atributos y nombres.** Alinear atributos y nombres de columna con el diccionario.
- [ ] **5. Flyway.** Migracion V1 escrita desde el diccionario.

## Paso 1: terceros

* Paquete nuevo `counterparties`: `Counterparty`, `CounterpartyRole`, `DocumentType`,
  `CounterpartyRepository`, `CounterpartyService`, `CounterpartyResponse` y dos excepciones
  de dominio.
* `Sale.customer` y `Purchase.supplier` apuntan a `Counterparty` por `id_tercero`. Se
  eliminaron `Customer`, `Supplier`, sus repositories y las tablas `CLIENTES` y
  `PROVEEDORES`.
* La API sigue identificando al tercero por id; la respuesta lo devuelve como objeto
  `customer` / `supplier`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Clientes y proveedores se
  unifican en terceros (Counterparty)".
* Tests: 135 antes, 146 despues. Ninguna clase perdio tests; `CounterpartyTest` es nueva
  (11).

### Anotado para el paso 5 (Flyway)

* La PK de `TERCEROS` ya se llama `id_tercero` por `@AttributeOverride`; las demas tablas
  siguen con `id`.
* El tenant sigue en `tenant_id` en todas las tablas, `TERCEROS` incluida: la condicion del
  `@Filter` de `TenantAwareEntity` lo tiene escrito. El diccionario dice `id_tenant`.

## Paso 2: movimientos con categoria

* `Movement` gana `category` (`MovementCategory`, obligatoria), `notes` y
  `relatedMovement` (`id_movimiento_relacionado`). Se eliminaron `Transfer`,
  `OccasionalIncome`, `OperationalExpense`, sus repositories, sus DTOs y sus tres tablas.
* Una transferencia son dos movimientos TRANSFERENCIA; el INGRESO apunta al EGRESO.
* Gastos e ingresos ocasionales son los unicos movimientos manuales:
  `POST`, `PUT` y `DELETE /api/treasury/movements`. Desaparecen `/occasional-incomes`,
  `/operational-expenses` y el movimiento suelto.
* Ventas y compras crean RECAUDO_VENTA y PAGO_COMPRA, todavia enlazados por
  `(sourceType, sourceId)` con solo SALE y PURCHASE.
* Reporting lee gastos e ingresos ocasionales de los movimientos.
* `04-DATA-MODEL.md` se movio de `docs/` a `docs/ai-context/`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Transferencias, ingresos
  ocasionales y gastos operativos son movimientos con categoria".
* Tests: 146 antes, 158 despues. Ninguna clase perdio tests; `InvoiceMovementCategoryTest`
  es nueva (4).

### Anotado para el paso 4

* Las columnas que ya existian en `MOVIMIENTOS` conservan su nombre (`concepto_movimiento`,
  `monto_movimiento`); el diccionario dice `concepto` y `monto`.

## Paso 3: abonos y pagos como movimientos

* `Movement` cambia `sourceType` / `sourceId` por `sale` (`id_venta`) y `purchase`
  (`id_compra`), claves foraneas reales sin cascada, con el `CHECK`
  `ck_movimiento_factura`. Se eliminaron `MovementSourceType`, `Collection`, `Payment`,
  sus repositories, sus DTOs de respuesta y las tablas `RECAUDOS` y `PAGOS`.
* Un abono es un `RECAUDO_VENTA` con `sale` y un pago un `PAGO_COMPRA` con `purchase`; el
  contado tambien. `/collections` y `/payments` responden con `MovementResponse`, que
  expone `saleId` y `purchaseId`.
* `Sale` y `Purchase` ya no guardan cuenta (`id_cuenta`), ni la exponen en la respuesta.
* Movimiento activo: el de una factura `ANULADA` es inactivo, derivado del estado con
  `MovementSpecifications.activo()`; todos los listados y sumas lo aplican.
  `GET /api/treasury/movements` gana `saleId` y `purchaseId`.
* Anular revierte saldos sin borrar filas; restaurar vuelve a aplicarlos (en compras, con
  saldo suficiente validado entero antes de escribir).
* Reporting: la cuenta de los renglones `VENTA` / `COMPRA` sale del ultimo movimiento de
  la factura.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Abonos y pagos son
  movimientos, y anular deja los movimientos inactivos".
* Limitacion conocida: el historial de movimientos de una factura anulada queda en la base
  pero ningun endpoint lo expone.
* Tests: 159 antes, 169 despues. `SalesRestoreTest` y `PurchasesRestoreTest` pierden el
  test de la defensa contra movimientos vivos (ya no aplica) y ganan uno cada uno, asi que
  quedan igual (9); `InvoiceMovementsApiTest` es nueva (4).
