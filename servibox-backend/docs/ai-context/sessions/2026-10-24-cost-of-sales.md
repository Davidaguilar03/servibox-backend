# 2026-10-24: Costo de lo vendido y utilidad bruta con costo real

Rama `feat/37-cost-of-sales`. Decision en [03-DECISIONS.md](../03-DECISIONS.md), "Utilidad
bruta con costo de lo vendido congelado por linea".

## Cambios

* V4 `V4__sale_line_cost.sql`: `detalle_ventas.costo_unitario_linea` NUMERIC(14,2), se
  rellena, pasa a NOT NULL y lleva `ck_detalle_venta_costo` (`>= 0`).
* `SaleDetail.unitCost`; `SalesService.crearFactura` lo toma del `purchaseCost` del producto
  despues de `bloquearProductos` (nulo como 0). Anular, restaurar, compras y ediciones no lo
  tocan. `SaleDetailResponse.unitCost` (campo nuevo).
* `SaleRepository.sumSubtotalByStatusNot...` y `SaleDetailRepository.sumCostByStatusNot...`:
  una consulta agregada cada una, global y por rango.
* Estado de resultados: Ventas sin IVA, Costo de lo vendido, Utilidad bruta, Otros ingresos,
  Gastos operativos, Utilidad neta.
* Siembra: `RECARGO_VENTA = 1.12` sobre el precio sugerido.

## Contrato final de GET /api/reporting/kpis

```
desde, hasta        null en el global
totalSales          suma(Sale.total), con IVA, sin ANULADA
totalPurchases      suma(Purchase.total), con IVA, sin ANULADA
netSales            suma(Sale.subtotal), sin IVA, sin ANULADA            (2 decimales)
costOfSales         suma(SaleDetail.unitCost * quantity), sin ANULADA     (2 decimales)
grossProfit         netSales - costOfSales                                (2 decimales)
operatingExpenses   GASTO_OPERATIVO activos
otherIncome         INGRESO_OCASIONAL activos
netProfit           grossProfit + otherIncome - operatingExpenses         (2 decimales)
```

Mismo criterio de fechas (`invoiceDate` en `[desde, hasta]`), de anuladas y de tenant para
`netSales`, `costOfSales` y `totalSales`.

## Cifras

Escenario controlado (tenant nuevo, IVA 19, compras a credito; igual en H2 y en PostgreSQL 16):

| Paso | netSales | costOfSales | grossProfit | totalSales |
|-|-|-|-|-|
| Compra 10 a 10000 | 0 | 0 | 0 | 0 |
| Venta 2 a 15000 | 30000 | 20000 | 10000 | 35700 |
| Compra 5 a 12000, venta 1 a 15000 | 45000 | 32000 | 13000 | 53550 |
| Primera venta anulada | 15000 | 12000 | 3000 | 17850 |
| Primera venta restaurada | 45000 | 32000 | 13000 | 53550 |

Siembra demo (Clock en 2026-10-24):

| Mes | netSales | costOfSales | grossProfit | margen | netProfit |
|-|-|-|-|-|-|
| Agosto | 20156000 | 13572000 | 6584000 | 32,7 % | -710000 |
| Septiembre | 23105000 | 15636000 | 7469000 | 32,3 % | 557200 |
| Octubre | 14331000 | 9606000 | 4725000 | 33,0 % | -467200 |

Sin recargo (precio sugerido) el margen era 24,6 / 24,2 / 25,0 por ciento y la neta
-2858000 / -1915800 / -1996200.

## Limitaciones

* **Relleno de V4**: las lineas anteriores a V4 toman el costo del producto al migrar, no el
  de la fecha de la venta. Si el costo cambio despues, la utilidad bruta historica de esas
  lineas es aproximada.
* `costo_compra` es NOT NULL y la API exige `purchaseCost`: un producto con costo nulo no
  existe hoy; el codigo guarda 0 si llegara a leer null. La prueba usa costo 0.
* El costo es el ultimo de compra del producto (no hay kardex ni promedio ponderado).
