# 2026-10-24: Datos de demostracion realistas en el perfil dev

Rama `feat/36-realistic-demo-data`. Objetivo: que el tenant `demo` del perfil dev parezca un
almacen real de llantas, lubricantes y baterias de Tunja, con tres meses de operacion, sin
cambiar endpoints ni migraciones.

## Lo que habia

* `DevDataInitializer` sembraba por los services: siembra base (tenant, admin, IVA, las 8
  categorias de Autollantas en 0 y Caja General + Bancolombia) y, con `DEV_DEMO_DATA`, la
  categoria `REPUESTOS DEMO`, `DEMO-001` a `DEMO-003`, 3 terceros, 5 ventas (`FV-0001` a
  `FV-0005`), la compra `PROV-1001` y 4 movimientos en el mes anterior y el actual.
* Pruebas con cifras fijas: `DevDemoDataTest` (stocks, saldos, KPIs de septiembre y octubre,
  numeros de factura) y `TenantRegistrationTest.elTenantNuevoYElDemoNoSeVenEntreSi` (codigos
  `DEMO-00x`, categorias sin LUBRICANTES, 2 cuentas). `DevDemoDataDisabledTest` esperaba 8
  categorias. `SalesInvoiceNumberingTest` usa `FV-000x` en tenants propios, no la siembra.
* El Clock de negocio se puede fijar por contexto con un `@Bean @Primary` en una
  `@TestConfiguration` (como `BusinessClockZoneTest`); el runner usa ese Clock.

## Lo que se hizo

* Siembra base: las 3 categorias del registro (LLANTAS, LUBRICANTES, BATERIAS) en vez de 8.
* Siembra de demostracion nueva (detalle y cifras en 02-CONVENTIONS, decision en
  03-DECISIONS): umbrales y margen por categoria con `actualizarCategoria`, 27 productos,
  cuenta Nequi (DIGITAL), saldos iniciales fijados, 8 clientes y 5 proveedores con NIT de
  digito de verificacion valido, 40 ventas, 9 compras, 22 gastos, 1 ingreso ocasional, 2
  transferencias, abonos y pagos parciales y totales, 2 ventas y 1 compra anuladas.
* Helpers: `fecha(mesesAtras, dia)` (nunca despues de hoy), `vender` (precio sugerido
  redondeado al millar; sin cuenta es credito a 30 dias), `comprar` (costo vigente o
  `@precio`), `abonar` y `pagar` (sin monto, el saldo pendiente).

## Pruebas

* `DevDemoDataTest` reescrita, Clock fijo en 2026-10-24 (6): categorias con margen entre
  0.18 y 0.35 y productos en cada estado sin stock negativo; por cuenta, saldo actual =
  inicial + movimientos activos y saldo de cada dia de los tres meses >= 0; tres meses sin
  fechas futuras, 4 medios de pago segun el tipo de cuenta, Consumidor final, abonos
  parciales y totales, compra anulada; KPIs de cada mes = suma de facturas activas y
  movimientos, bruta positiva y neta positiva en el mes actual o el anterior; KPIs con las
  cifras documentadas; dos ejecuciones mas no duplican.
* `DevDemoDataFirstDayTest` nueva (1): Clock en 2026-03-01 (febrero de 28 dias), siembra
  completa, nada despues de hoy, ventas hoy y las mismas cifras por mes.
* `DevDemoDataDisabledTest`: 3 categorias base en 0 en vez de 8.
* `TenantRegistrationTest.elTenantNuevoYElDemoNoSeVenEntreSi`: los dos tenants tienen ahora
  los mismos nombres de categoria, asi que el aislamiento se comprueba por margen (0 en el
  nuevo, configurado en el demo), codigos `LL/LU/BA` y las 3 cuentas del demo.
* Total: 387 antes, 390 despues (-4 + 6 + 1).

## Verificacion real

Perfil dev en 8080, 2026-10-24, con curl:

* Productos: LLANTAS 10, LUBRICANTES 10, BATERIAS 7. En cero `BA-007`; criticos `LL-010`
  (2) y `BA-004` (1); bajos `LL-007` (6) y `LU-010` (9). Sin stock negativo. Valor del
  inventario a costo 53734000.
* KPIs agosto: ventas 21429520, compras 14518000, bruta 6911520, gastos 7294000, otros 0,
  neta -382480. Septiembre: 24552080, 10595760, 13956320, 7221800, 310000, neta 7044520.
  Octubre: 15234380, 7097160, 8137220, 5192200, 0, neta 2945020.
* Cuentas: Bancolombia 15000000 - 4737740 = 10262260 (46 movimientos); Caja General
  2500000 + 511750 = 3011750 (20); Nequi 600000 + 5414000 = 6014000 (8). Saldo actual igual
  en las tres; balance global 19288010.
* 40 ventas (33 PAGADA, 5 PENDIENTE, 2 ANULADA; 29 contado, 11 credito), 9 compras (6
  PAGADA, 2 PENDIENTE, 1 ANULADA; 6 contado, 3 credito), 22 gastos por 19708000, 9 clientes
  (con Consumidor final) y 5 proveedores.

## Pendiente

* Agosto queda con utilidad neta negativa (-382480) por el surtido inicial de llantas; es
  deliberado, septiembre y octubre quedan en positivo.
