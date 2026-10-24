# 2026-10-24: Costo del producto al anular y recuperar una compra

Rama `fix/35-cost-revert-on-annulment`. Objetivo: que al anular una compra el costo del
producto, y con el su IVA y su precio sugerido, vuelva al de la ultima compra no anulada.

## Lo que habia

* `PurchasesService.crearFactura` llama a `sincronizarCosto` por cada linea, en contado y
  en credito: `purchaseCost = precio` de la linea (sin IVA) y
  `InventoryService.recalculatePrices`, que rehace `taxAmount` y `suggestedPrice`. No mira
  la fecha de factura: el costo vigente es el de la ultima compra registrada, y dentro de
  una compra el de su ultima linea.
* `anularFactura` quitaba el stock y revertia los saldos; `restaurarCompra` los reponia.
  Ninguno tocaba el costo (heredado de Autollantas, decision del 2026-09-08).
* Pruebas que lo fijaban: `PurchasesCostSyncTest.anularNoRevierteElCostoDelProducto` y
  `PurchasesRestoreTest.restaurarNoCambiaElCostoDelProducto`. Las dos tienen una sola compra
  del producto, asi que sus cifras siguen valiendo con la regla nueva.

## Lo que se hizo

* `PurchaseDetailRepository.findLineasDeLaUltimaCompra`: una consulta por lote con las
  lineas de la compra no anulada de mayor id de cada producto, sin contar la compra dada.
* `PurchasesService.revertirCostos` (al anular, despues de quitar el stock) y
  `reaplicarCostos` (al recuperar, despues de sumarlo), con los productos ya bloqueados.
  Reutilizan `sincronizarCosto`.
* Docs: 03-DECISIONS ("Costo del producto al anular y recuperar una compra", y la entrada
  del 2026-09-08 marcada como reemplazada), 01-ARCHITECTURE (anulacion y restauracion de
  compras). 02-CONVENTIONS sin cambios: el criterio queda en 03-DECISIONS.

## Pruebas

* `PurchasesCostRevertTest` (10): anular la ultima vuelve a la anterior y sin otras se
  queda; recuperar la mas reciente vuelve a su costo y una anterior no lo toca; recuperar
  una anterior con la posterior anulada; costo editado a mano; compra posterior que fijo el
  costo; tolerancia de medio centavo; varios productos en una compra; contado y credito;
  ventas emitidas sin cambios; aislamiento entre tenants.
* `ConcurrentOperationsTest` (+2): anular y recuperar la misma compra a la vez, y anular a
  la vez dos compras del mismo producto; 5 rondas cada una.
* Renombradas `anularSinOtrasComprasDejaElCostoDelProducto` y
  `restaurarLaUnicaCompraDejaElCostoDelProducto`.
* Total: 375 antes, 387 despues.

## Verificacion real

* Perfil dev, puerto 8091, categoria REPUESTOS DEMO (margen 0.30, configuracion en ceros).
  Producto en 10000 (IVA 1900, sugerido 14285.71). Compra A 12000 a credito y B 15000 de
  contado. Anular B: 12000 / 2280 / 17142.86. Anular A: se queda en 12000. Recuperar B:
  15000 / 2850 / 21428.57. Recuperar A: sigue en 15000. Costo a mano 20000 y anular B: se
  queda en 20000 / 3800 / 28571.43. Stock y saldos en cada paso como antes; las dos cuentas
  cuadran con su balance inicial mas los movimientos activos.

## Pendiente

* Frontend: texto de los modales de anular y recuperar compra.
* Sin historico de costos: sin otras compras no anuladas el costo se queda como esta.
