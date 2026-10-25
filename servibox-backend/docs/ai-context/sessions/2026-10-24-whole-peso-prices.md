# 2026-10-24: Precio sugerido e IVA del costo en pesos enteros

Rama `fix/38-whole-peso-prices`. Decision en [03-DECISIONS.md](../03-DECISIONS.md),
"Precio sugerido e IVA del costo en pesos enteros".

## Cambios

* `shared.Moneda.aPesosEnteros(BigDecimal)`: HALF_UP al peso, unica funcion de redondeo.
* `InventoryService.aplicarPrecios`: la formula en `BigDecimal` y un solo redondeo final de
  `taxAmount` y `suggestedPrice`. Es el unico punto que los calcula.
* V5 `V5__round_product_prices.sql`: `ROUND(..., 0)` de `precio_sugerido` e `iva_producto`.
* Siembra: precio de venta = sugerido * 1,12 redondeado al peso; 5 ventas nuevas del 20 al
  24 del mes actual (`LL-006:4` a credito a Taxis Altiplano, `BA-006:1` en efectivo,
  `LL-005:3` y `LU-001:8` a credito a Transportes Rio Chicamocha, `LL-008:1` con tarjeta
  debito, `LL-009:1` y `LL-001:3` por transferencia a Servitaller Hunza).

## Cifras finales de la siembra (Clock en 2026-10-24)

| Mes | netSales | costOfSales | grossProfit | margen bruto | otherIncome | operatingExpenses | netProfit |
|-|-|-|-|-|-|-|-|
| Agosto | 20160164 | 13572000 | 6588164 | 32,7 % | 0 | 7294000 | -705836 |
| Septiembre | 23099898 | 15636000 | 7463898 | 32,3 % | 310000 | 7221800 | 552098 |
| Octubre | 20781907 | 14055000 | 6726907 | 32,4 % | 0 | 5192200 | 1534707 |

Octubre antes del cambio (precios ya al peso, sin las 5 ventas): netSales 14333620, costo
9606000, bruta 4727620, gastos 5192200, neta -464580. Margen neto de octubre despues: 7,4 %.

45 ventas, 9 compras, 22 gastos. Saldos finales: Caja General 4406852,45, Bancolombia
16963933,02, Nequi 6807530,08; cada uno igual a su saldo inicial mas sus movimientos activos.

## Limitaciones: donde aun quedan decimales de peso

* IVA por linea de venta (`iva_generado_linea`, `diferencia_iva_linea`) y de compra
  (`iva_linea_compra`): precio por cantidad por tasa, sin redondear.
* Totales de factura (`total_venta`, `iva_por_pagar_venta`, `total_compra`,
  `iva_total_compra`) y por eso `totalSales` y `totalPurchases` de kpis.
* Abonos y pagos por el saldo pendiente completo, y con ellos los saldos de las cuentas.
* El costo del producto (`costo_compra`) acepta centavos si la compra o la edicion los
  traen; precio sugerido e IVA del costo salen enteros igual.
