# Reportes en vivo de ventas, productos vendidos, compras y cartera (tarea 60), 2026-11-21

Rama feat/60-reports-sales-portfolio creada desde main en e5524f8 (merge PR 59), igual a
origin/main tras fetch. Los dos .idea ajenos quedan fuera. Sin commit, push ni PR. Decision en
[03-DECISIONS](../03-DECISIONS.md); arquitectura en [01-ARCHITECTURE](../01-ARCHITECTURE.md),
Modulo Reporting, "Reportes en vivo"; terminos en [02-CONVENTIONS](../02-CONVENTIONS.md).

## Hallazgos previos

* `ReportExportService` arma un `ReportModel` y los generadores solo dan formato; `/export` elige
  por `ReportType`. Se extendio ese patron, sin uno paralelo.
* `Sale` y `Purchase` tienen `dueDate` (obligatoria a credito), estados PAGADA, PENDIENTE y
  ANULADA y forma de pago CONTADO o CREDITO. No guardan saldo: es el total menos todos sus
  movimientos, con tolerancia de 1 peso. El IVA de una venta es `total - subtotal`; el de una
  compra, `ivaTotal`.
* El filtro de Hibernate aplica a JPQL, no a `findById`: los ids de filtros se validan con los
  `find...ById` por tenant de cada service.
* Autollantas (solo lectura): cartera en bloques "Aun no vence", 0-30, 31-60 y mas de 60 sobre
  `dueDate`; productos vendidos por producto y por categoria con utilidad; reporte diario con
  totales de productos y servicios. Todo en memoria con entidades.

## Implementado

* `reporting/repository/LiveReportRepository`: JPQL dinamico, escalares, subconsulta de lo
  abonado o pagado, `GROUP BY` en productos vendidos.
* `LiveReportService`, `AgingRange`, DTOs `LiveReportResponse`, `SeriesPoint`, `SalesReport`,
  `ProductsSoldReport`, `PurchasesReport` y `PortfolioReport`.
* `ReportingController`: cinco GET nuevos y `/export` con cinco `ReportType` nuevos y sus filtros.
* `ReportModel`: `footer`, `ColumnType.INTEGER` y `PERCENT`, `Total` con tipo, `desde` null para
  "Corte al". PDF horizontal con mas de seis columnas (pie estampado con el tamano rotado). Version
  del producto al final del pie del PDF ("... · Pag. N de M · ServiBox 1.1.0") y en la ultima fila
  del Excel, en todos los reportes, tambien los dos existentes.
* `ProductResponse.vatApplies`.

## Contratos para el frontend

Todos `GET`, con JWT, aislados por serviteca. Error: `{"error","status"}` 400. Fechas ISO.
Respuesta comun:

```
{ "desde": "2026-11-01" | null, "hasta": "2026-11-30" | null, "corte": "2026-11-21" | null,
  "appliedFilters": ["Forma de pago: Crédito"],
  "summary": {...}, "rows": [...], "series": [{"label": "...", "value": 0.0}] }
```

Rango (ventas, productos, compras): sin `desde` ni `hasta`, mes en curso; con uno solo 400. Tope
de filas 20000 (`REPORTING_MAX_EXPORT_ROWS`), mas es 400.

1. `/api/reporting/sales?desde&hasta&clienteId&formaPago=CONTADO|CREDITO&estado=PAGADA|PENDIENTE|ANULADA`
   * summary: `totalSold`, `invoiceCount`, `averageTicket`, `cashTotal`, `creditTotal`,
     `annulledCount` (todo sin anuladas salvo `annulledCount`; montos con IVA).
   * rows (por fecha e id, anuladas incluidas): `saleId`, `invoiceNumber`, `invoiceDate`,
     `customerId`, `customerName`, `paymentType`, `status`, `subtotal`, `iva`, `total`,
     `pendingBalance` (0 en anulada).
   * series: un punto por dia del rango, `label` fecha ISO, total vendido sin anuladas.
2. `/api/reporting/products-sold?desde&hasta&itemType=PRODUCTO|SERVICIO&categoriaId&productoId`
   * summary: `unitsSold`, `revenue` (sin IVA), `cost`, `grossMargin`, `marginPercent`.
   * rows (ingresos de mayor a menor, sin anuladas): `productId`, `code`, `description`,
     `itemType`, `categoryName` (categoria actual), `units`, `revenue`, `cost` (congelado),
     `margin`, `marginPercent` (en por ciento, 2 decimales).
   * series: los 10 primeros, `label` descripcion, `value` ingresos.
3. `/api/reporting/purchases?desde&hasta&proveedorId&categoriaId&estado=PAGADA|PENDIENTE|ANULADA`
   * summary: `totalPurchased`, `invoiceCount`, `deductibleVat`, `pendingTotal` (sin anuladas).
   * rows: `purchaseId`, `invoiceNumber`, `invoiceDate`, `supplierId`, `supplierName`, `status`,
     `subtotal`, `iva`, `total`, `pendingBalance`. `categoriaId` deja las facturas con al menos
     una linea de esa categoria, con sus totales completos.
   * series: total por proveedor, 10 mayores.
4. `/api/reporting/receivables?clienteId&antiguedad=0-30|31-60|61-90|90+` (corte hoy; ignora
   fechas). El `+` va codificado (`90%2B`; `URLSearchParams` lo hace solo).
   * summary: `totalBalance`, `invoiceCount`, `balance0To30`, `balance31To60`, `balance61To90`,
     `balanceOver90`.
   * rows (por nombre de cliente, fecha e id; solo PENDIENTE con saldo): `invoiceId`,
     `counterpartyId`, `counterpartyName`, `invoiceNumber`, `invoiceDate`, `dueDate` (puede ser
     null), `daysOld`, `agingBucket`, `total`, `paid`, `balance`.
   * series: siempre los cuatro rangos en orden.
5. `/api/reporting/payables?proveedorId&antiguedad`: igual con proveedores y facturas de compra.

Exportacion: `/api/reporting/export?type=VENTAS|PRODUCTOS_VENDIDOS|COMPRAS|CARTERA_COBRAR|CARTERA_PAGAR&format=PDF|XLSX`
mas los mismos filtros. Nombres: `ventas_<desde>_<hasta>.pdf`,
`productos-servicios-vendidos_...`, `compras_...`, `cartera-por-cobrar_<corte>.xlsx`,
`cartera-por-pagar_<corte>`.

`ProductResponse.vatApplies` (boolean) en todas las respuestas de producto.

Mensajes de error nuevos (texto exacto, para `src/api/errorMessages.ts`):

* 400 `Cliente no encontrado: <id>`
* 400 `Proveedor no encontrado: <id>`
* 400 `Valor invalido '<x>' en el parametro antiguedad. Valores permitidos: 0-30, 31-60, 61-90, 90+`
* 400 `Valor invalido '<x>' en el parametro estado. Valores permitidos: PAGADA, PENDIENTE, ANULADA` (en /export)
* 400 `El parametro <p> no aplica al reporte <TYPE>`
* 400 `El reporte tiene mas de <n> facturas: acota el rango de fechas`
* 400 `El reporte tiene mas de <n> facturas pendientes: filtra por cliente` (o `por proveedor`)
* Cambiado: `Falta el parametro type: ESTADO_RESULTADOS, MOVIMIENTOS, VENTAS, PRODUCTOS_VENDIDOS, COMPRAS, CARTERA_COBRAR, CARTERA_PAGAR`

Ya existentes y reutilizados: `Categoria no encontrada: <id>`, `Producto no encontrado: <id>`,
los de rango y los de enum de parametros (`formaPago`, `estado`, `itemType`).

## Pruebas

* `./mvnw test`: 785 pruebas, 0 fallos, 0 errores (763 base mas 22 nuevas: 21 en
  `reporting.LiveReportsTest` y 1 en `inventory.ServiceItemsTest`).
* Ajustada: `ReportExportTest.typeYFormatInvalidosOFaltantesSon400` usaba `type=VENTAS` como
  valor invalido; ahora usa `INVENTARIO` y la lista de valores permitidos nueva.
* App en dev, puerto 8080, tenant demo, noviembre de 2026: ventas del mes `totalSold`
  24730469,33 igual a `totalSales` de `/kpis`; productos vendidos `revenue` 20781907, `cost`
  14055000 y `grossMargin` 6726907 iguales a `netSales`, `costOfSales` y `grossProfit` de
  `/kpis`. `formaPago=CREDITO`: 5 facturas, 11902196,74. `purchases?estado=PENDIENTE`: 1 factura,
  pendiente 1670160. `receivables`: 7 facturas, 18838227,56, todo en 0-30 (la siembra tiene
  creditos a 30 dias de los ultimos dos meses). `payables?proveedorId=11`: 1670160.
  `formaPago=X` y `antiguedad=15`: 400 con los mensajes de arriba.
* Archivos: PDF de ventas (horizontal, 2 paginas, totales 20781907 / 3948562,33 / 24730469,33 /
  11402196,74, igual al JSON), Excel de ventas a credito (Total vendido 11902196,74 y 5 facturas
  como celdas numericas, igual al JSON), PDF y Excel de cartera por cobrar ("Corte al
  21/11/2026", saldo 18838227,56, celdas numericas). Pie "ServiBox 1.1.0".

## Limitaciones

* La categoria de Productos vendidos es la actual del producto, no la de la venta.
* Compras con `categoriaId` suma la factura completa, no solo las lineas de esa categoria.
* Las carteras no muestran aparte lo que aun no vence: cae en 0-30 con 0 dias (su `dueDate` lo dice).
* El rango por defecto (mes en curso) solo aplica a los reportes nuevos; KPIs, Movimientos, IVA
  y sus exportaciones siguen exigiendo las dos fechas.
* La siembra dev no tiene cartera de mas de 30 dias ni servicios: los cuatro rangos y los
  servicios se probaron con las pruebas automaticas, no en dev.
