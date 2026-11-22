# Reportes en vivo de inventario, flujo de caja, resultados, IVA y movimientos (tarea 61), 2026-11-21

Rama feat/61-reports-inventory-cashflow creada desde main en a1398fe (merge PR 60), igual a
origin/main tras fetch. Los dos .idea ajenos quedan fuera. Sin commit, push ni PR. Decision en
[03-DECISIONS](../03-DECISIONS.md); arquitectura en [01-ARCHITECTURE](../01-ARCHITECTURE.md),
Modulo Reporting, "Reportes en vivo"; terminos en [02-CONVENTIONS](../02-CONVENTIONS.md). Mismo
patron de la tarea 60: [2026-11-21-reports-sales-portfolio](2026-11-21-reports-sales-portfolio.md).

## Hallazgos previos

* El frontend solo consume `/kpis` (siempre con `desde` y `hasta`, en el panel) y `/export`
  (`ESTADO_RESULTADOS` y `MOVIMIENTOS`). `/movements` e `/iva` no tienen consumidor.
* `/kpis` sin fechas daba los KPIs globales; `/movements` e `/iva` exigian las dos fechas.
* Tesoreria: cuentas con `initialBalance`; movimientos INGRESO o EGRESO con categoria
  `RECAUDO_VENTA` (contado y abonos, enlazados a la venta), `PAGO_COMPRA` (contado y pagos),
  `GASTO_OPERATIVO`, `INGRESO_OCASIONAL` y `TRANSFERENCIA` (una pata por cuenta). Activo = no
  enlazado a una factura ANULADA (`MovementSpecifications.activo()`). Saldo a una fecha:
  `TreasuryService.saldoAl(cuenta, fecha)`.
* Regla de stock del frontend (`src/lib/stock.ts`): cuatro estados `SIN_STOCK` (stock <= 0),
  `CRITICO` (<= rojo), `BAJO` (<= amarillo) y `NORMAL`. Los umbrales son columnas NOT NULL de
  CATEGORIA_PRODUCTOS (`stock_min_amarillo`, `stock_min_rojo`); los valores por defecto del
  frontend (5 y 2) solo cubren un nulo. El prompt pedia NORMAL, BAJO y AGOTADO; David eligio los
  cuatro estados con los nombres del frontend.
* Compras con `categoriaId` (tarea 60): `EXISTS` sobre las lineas y totales de la factura completa.

## Implementado

* `inventory.entity.StockStatus` (la regla del frontend).
* `LiveReportResponse` gano el tipo del punto de la serie (`<S, R, P>`).
* DTOs `InventoryReport`, `CashFlowReport`, `IncomeStatementReport`, `VatReport`, `MovementsReport`.
* `LiveReportRepository`: inventario, flujo por concepto, movimientos por dia, ventas y costo por
  dia, gastos e ingresos por dia, lineas con IVA; Compras por categoria con `GROUP BY` sobre las
  lineas de la categoria.
* `LiveReportService`: `inventario`, `flujoDeCaja`, `estadoDeResultados`, `iva`, `movimientos`;
  `rango` publico (mes en curso por defecto); `referencia` (antes `origen` en la exportacion).
* `TreasuryService.listarParaReporte` con `tipo` (sobrecarga; la firma vieja sigue).
* `ReportExportService`: `inventario`, `flujoDeCaja`, `iva`, `movimientos` con `tipo`; mes por
  defecto en `ESTADO_RESULTADOS` y `MOVIMIENTOS`.
* `ReportingController`: cinco GET nuevos, mes por defecto en `/kpis`, `/movements` e `/iva` (sin
  tope de dias), `ReportType` `INVENTARIO`, `FLUJO_CAJA` e `IVA`, y `tipo` y `cuentaId` en
  `MOVIMIENTOS`.

## Contratos para el frontend

Todos `GET`, con JWT, aislados por serviteca. Error: `{"error","status"}` 400. Fechas ISO. Sin
`desde` ni `hasta`, el mes en curso; con una sola, desde posterior a hasta o mas de 366 dias, 400.
Forma comun (la de la tarea 60):

```
{ "desde", "hasta", "corte", "appliedFilters": [...], "summary": {...}, "rows": [...], "series": [...] }
```

1. `/api/reporting/inventory?desde&hasta&categoriaId&estadoStock=NORMAL|BAJO|CRITICO|SIN_STOCK`
   * Solo items PRODUCTO. `corte` es hoy (stock y valores); `desde`/`hasta` son la rotacion.
   * summary: `productCount`, `unitsInStock`, `costValue`, `saleValue` (stock por precio de venta
     sin IVA), `lowStockCount` (BAJO), `criticalStockCount` (CRITICO), `outOfStockCount`
     (SIN_STOCK), `unitsSoldInPeriod`. Calculado sobre las filas filtradas.
   * rows (valor al costo de mayor a menor, luego codigo): `productId`, `code`, `description`,
     `categoryName`, `stock`, `stockStatus`, `unitCost`, `costValue`, `salePrice` (sin IVA),
     `unitsSold` (en el rango, sin anuladas), `lastSaleDate` (ultima venta no anulada de cualquier
     fecha, null si nunca), `daysWithoutSale` (null si nunca).
   * series: `{label: descripcion, value: unidades}`, los 10 mas vendidos del rango (solo > 0).
2. `/api/reporting/cash-flow?desde&hasta&cuentaId`
   * summary: `openingBalance` (cierre del dia anterior a desde), `inflows`, `outflows`, `netFlow`,
     `closingBalance` (cierre de hasta). Siempre inicial + entradas - salidas = final.
   * rows en orden fijo: `concept` (`VENTAS_CONTADO`, `ABONOS_CLIENTES`, `INGRESOS_OCASIONALES`,
     `TRANSFERENCIAS_RECIBIDAS`, `COMPRAS_CONTADO`, `PAGOS_PROVEEDORES`, `GASTOS_OPERATIVOS`,
     `TRANSFERENCIAS_ENVIADAS`), `label` (texto de pantalla), `direction` (`ENTRADA` o `SALIDA`),
     `movementCount`, `amount` (positivo). Sin `cuentaId` no vienen las dos de transferencias.
   * series: neto por dia, `{label: fecha ISO, value}`.
3. `/api/reporting/income-statement?desde&hasta`
   * summary: `revenue` (ventas sin IVA), `costOfSales`, `grossProfit`, `grossMarginPercent`,
     `operatingExpenses`, `otherIncome`, `netProfit`, `netMarginPercent` (margenes en por ciento).
   * rows: `line` (`VENTAS`, `COSTO_VENTAS`, `UTILIDAD_BRUTA`, `OTROS_INGRESOS`,
     `GASTOS_OPERATIVOS`, `UTILIDAD_NETA`), `label`, `amount` (costo y gastos negativos),
     `percentOfRevenue`.
   * series: `{label, revenue, cost, profit}` (no `value`): por mes (`2026-09`) si el rango cruza
     meses, si no por dia (`2026-09-05`). `profit` es la utilidad neta del punto.
4. `/api/reporting/vat?desde&hasta`
   * summary: `ivaGenerado`, `ivaDescontable`, `ivaNeto` (saldo por pagar), `invoicesWithIva`.
   * rows (IVA generado de mayor a menor): `categoryName`, `ratePercent` (19.0), `taxableBase`,
     `ivaGenerado`, `ivaDescontable`, `ivaNeto`.
   * series: `{label: "LLANTAS 19 %", value: ivaNeto}` por fila.
5. `/api/reporting/movements-report?desde&hasta&cuentaId&tipo=INGRESO|EGRESO`
   * summary: `inflows`, `outflows`, `net`, `movementCount`.
   * rows (fecha e id ascendente): `movementId`, `date`, `accountName`, `concept`, `reference`
     (`VENTA <n>`, `COMPRA <n>`, `GASTO OPERATIVO`, `INGRESO OCASIONAL`, `TRANSFERENCIA`), `type`,
     `category`, `amount` (positivo). Las transferencias aparecen en sus dos patas.
   * series: neto por dia.

Exportacion: `/api/reporting/export?type=INVENTARIO|FLUJO_CAJA|IVA&format=PDF|XLSX` con los mismos
filtros (`INVENTARIO`: `categoriaId`, `estadoStock`; `FLUJO_CAJA`: `cuentaId`; `IVA`: ninguno).
`MOVIMIENTOS` acepta ademas `tipo` y `cuentaId` (alias de `accountId`). Nombres
`inventario-rotacion_<desde>_<hasta>`, `flujo-caja_...`, `reporte-iva_...`.

Cambios a rutas existentes:

* `/kpis`, `/movements` e `/iva`: misma respuesta; sin `desde` ni `hasta` usan el mes en curso.
  `/kpis` sin fechas ya **no** son los KPIs globales.
* `/purchases?categoriaId`: `subtotal`, `iva` y `total` de la fila son solo los de las lineas de
  esa categoria; `pendingBalance` es lo pendiente repartido en la misma proporcion.
* `export?type=ESTADO_RESULTADOS|MOVIMIENTOS` sin fechas usan el mes en curso.

Mensajes de error nuevos (texto exacto, para `src/api/errorMessages.ts`):

* 400 `Valor invalido '<x>' en el parametro estadoStock. Valores permitidos: NORMAL, BAJO, CRITICO, SIN_STOCK`
* 400 `Valor invalido '<x>' en el parametro tipo. Valores permitidos: INGRESO, EGRESO`
* Cambiado: `Falta el parametro type: ...` y la lista de `type` permitidos suman `INVENTARIO, FLUJO_CAJA, IVA`.

Ya existentes y reutilizados: `Cuenta no encontrada: <id>`, `Categoria no encontrada: <id>`,
`El parametro <p> no aplica al reporte <TYPE>`, `accountId solo aplica al reporte MOVIMIENTOS`,
los de rango y `El reporte tiene mas de <n> movimientos: acota el rango de fechas`.

## Pruebas

* `./mvnw test`: 801 pruebas, 0 fallos, 0 errores (785 base mas 16 nuevas en
  `reporting.LiveReportsSecondBatchTest`).
* Ajustadas:
  * `ReportExportTest.typeYFormatInvalidosOFaltantesSon400`: `INVENTARIO` ya es un `type` valido;
    ahora usa `BALANCE` y la lista nueva de permitidos.
  * `ReportingTest.laFormulaCompletaYElAislamientoPorTenantDeOtherIncome` y
    `kpisMovimientosYReporteIvaNoVenDatosDeOtroTenant`: llamaban `/kpis` sin fechas esperando los
    globales; ahora piden septiembre de 2026 (donde estan sus datos).
  * `ReportingTest.unRangoIncompletoOInvertidoResponde400`: `/iva` sin fechas ya es 200; el caso
    de 400 usa solo `hasta`.
  * Ninguna prueba de la tarea 60 dependia del filtro viejo de Compras por categoria (su factura de
    baterias tenia una sola linea).
* App en dev, puerto 8080, tenant demo, noviembre de 2026 (tras terminar la siembra, que acaba un
  par de segundos despues de "Started"):
  * Inventario: 27 productos, 276 unidades, valor al costo 49285000 (el de la siembra documentada),
    BAJO 2 (LL-007, LU-010), CRITICO 2 (LL-010, BA-004), SIN_STOCK 1 (BA-007), 89 unidades vendidas
    (igual que Productos vendidos). `estadoStock=CRITICO`: 2 filas; `categoriaId` LLANTAS: 10.
  * Flujo de caja sin cuenta: inicial 22072858,96, entradas 16724656,59, salidas 10619200, final
    28178315,55, igual a la suma de los saldos finales documentados de las tres cuentas. Con
    Bancolombia: final 16963933,02, el documentado.
  * Estado de resultados septiembre a noviembre: igual a `/kpis` del mismo rango (ventas sin IVA
    64041969, neta 1380969), serie de 3 meses.
  * IVA del mes: igual a `/iva` sin fechas (generado 3948562,33, neto 1278112,33).
  * Movimientos `tipo=EGRESO`: 8 filas, 10619200. `/kpis` sin fechas: noviembre.
  * Compras `categoriaId` LLANTAS: DO-4788 con 3300000 de subtotal de llantas.
  * `estadoStock=AGOTADO` y `cuentaId=999999`: 400 con los mensajes de arriba.
* Archivos (Inventario PDF y XLSX, Flujo de caja PDF y XLSX de Bancolombia): totales como celdas
  numericas iguales al JSON, PDF de inventario horizontal en 3 paginas, pie con "ServiBox 1.1.0".

## Limitaciones

* `getGlobalKpis` queda sin ruta (solo lo usan las pruebas).
* La tarifa del IVA se deduce de cada linea (IVA sobre base, a un decimal): una linea de base muy
  pequena puede caer en otra tarifa por redondeo.
* En Compras por categoria lo pagado se reparte por proporcion; no hay pagos por linea.
* Las transferencias aparecen en Movimientos en sus dos patas (como la exportacion), pero no en el
  Flujo de caja sin cuenta.
* La siembra dev no tiene servicios ni transferencias en noviembre: esos casos se probaron con las
  pruebas automaticas.
