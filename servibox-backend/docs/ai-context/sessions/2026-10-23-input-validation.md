# 2026-10-23: Endurecimiento de validaciones de entrada

Rama `fix/31-input-validation`. Objetivo: cerrar huecos de validacion del backend sin cambiar
reglas de negocio.

## Lo que habia

* Transferencias sin control de saldo (las compras y pagos si lo tenian).
* Ventas y compras aceptaban precio cero o negativo y credito sin `dueDate`; abonos y pagos
  con fecha anterior a la factura.
* Categorias sin rangos (margen 1 o mas, rojo mayor que amarillo, umbrales negativos); el
  POST ignoraba en silencio el nombre repetido y los `taxTypeId` ajenos, el PUT no.
* Productos, movimientos manuales y nombre de categoria: un texto mas largo que su columna
  daba 500.
* POST de terceros con `@Valid` y el "Peticion invalida" generico, sin recorte ni formato de
  correo; el documento 222222222222 se podia dar a cualquier tercero.
* Configuracion financiera y categorias editables por cualquier rol.
* `InsufficientBalanceException` concatenaba doubles ("3250000.0").

## Lo que se hizo

* `shared.Moneda.formatear` y `InsufficientBalanceException` con montos es-CO.
* `TreasuryService`: saldo del origen en `registrarTransferencia`; largo de concepto y notas
  en los movimientos manuales.
* `SalesService` y `PurchasesService`: precio, vencimiento en credito y fecha de abono/pago.
* `InventoryService`: `crearCategoria`, `validarCategoria`, `impuestosDelTenant`,
  `validarProducto` en `saveProduct`. `ProductResponse` redondea precios a 2 decimales.
* `InventoryController`: `exigirAdmin` (rol, luego las anotaciones del DTO con el
  `Validator`) en el PUT de configuracion y el POST y PUT de categorias.
  `AdminRequiredException` con un constructor de mensaje.
* `CounterpartyService.validarDatos` compartido por POST y PUT; documento reservado con
  `SystemCounterpartyException`; `TenantRegistrationService.CORREO` publico.
* Sin migracion. Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Endurecimiento de
  validaciones de entrada".

## Pruebas existentes ajustadas

* Facturas a credito de prueba sin `dueDate`: se les agrego vencimiento (fecha de la factura
  mas 30 dias, o 2099-12-31 en los cuerpos JSON) en CounterpartiesApiTest,
  CounterpartiesUpdateApiTest, CounterpartyTest, InventoryEditingTest, PurchasesCostSyncTest,
  PurchasesFlowTest, PurchasesRestoreTest, PurchasesTenantIsolationTest, ReportExportTest,
  ReportingTest, SalesCollectionAndAnnulmentTest, SalesInvoiceNumberingTest,
  SalesInvoicingTest, SalesRestoreTest, CheckConstraintsTest, InvoiceMovementCategoryTest,
  InvoiceMovementsApiTest y BusinessClockZoneTest.
* CollectionPaymentDateTest: las facturas se emiten 10 dias antes de hoy, para que el abono y
  el pago "de hace 5 dias" sigan siendo validos.
* TreasuryTenantIsolationTest: dos transferencias salen de una cuenta con saldo inicial 10000.
* ReportingTest (gastos e ingresos): la transferencia se registra antes del gasto, para que
  la caja tenga saldo.
* CounterpartiesUpdateApiTest (limites): el correo de 150 caracteres ahora tiene formato.

## Verificacion

* Tests: 321 antes, 351 despues. Nuevas: SalesValidationTest (5), PurchasesValidationTest
  (5), TreasuryValidationTest (5), InventoryValidationTest (8),
  CounterpartiesValidationTest (5) y MonedaTest (2).
* Prueba real en perfil dev (8080): la siembra deja las cifras de siempre (kpis del mes
  anterior 748510 / 357000 / 391510, del actual 267750 / 450000 / -182250, Caja 1946250,
  Bancolombia 2230650); una peticion por regla con su codigo y mensaje; venta de contado, a
  credito con abono, compra, transferencia y registro de una serviteca con su primera venta.
  No hay endpoint para crear un usuario no ADMIN en dev, asi que el 403 se probo solo en
  InventoryValidationTest.
