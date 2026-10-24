# 2026-10-24: Los ingresos ocasionales suman a la utilidad neta

Rama `feat/34-other-income-in-kpis`. Objetivo: que los movimientos `INGRESO_OCASIONAL`
activos entren a la utilidad neta en `/api/reporting/kpis` y en el Estado de resultados
exportado, como en Autollantas. La utilidad bruta no cambia.

## Lo que habia

* `operatingExpenses` sale de `TreasuryService.sumarPorCategoria(GASTO_OPERATIVO[, desde,
  hasta])`: `movementRepository.sumarMonto` con `filtroActivos`, es decir
  `MovementSpecifications.activo()` (no enlazado a venta ni compra ANULADA), la categoria y
  `date` entre `desde` y `hasta` inclusive; el tenant lo pone el filtro de Hibernate.
* `KpisResponse.of` calculaba `netProfit = grossProfit - operatingExpenses`; los ingresos
  ocasionales no entraban en ningun KPI.
* La siembra de demostracion registraba "Aporte inicial demo" de 2000000 en cada cuenta
  como ingreso ocasional; con el cambio habrian sido 4000000 de utilidad del mes anterior.
* Saldo inicial: `POST /api/treasury/accounts` acepta `initialBalance`, pero la Caja General
  del registro y las cuentas de la siembra base empiezan en 0 y no hay `PUT` de cuentas.

## Lo que se hizo

* `KpisResponse`: campo `otherIncome` (entre `operatingExpenses` y `netProfit`) y
  `netProfit = grossProfit + otherIncome - operatingExpenses`.
* `ReportingService`: `otherIncome` con `sumarPorCategoria(INGRESO_OCASIONAL, ...)`, global
  y de periodo, el mismo criterio que los gastos.
* `ReportExportService.estadoDeResultados`: fila "Otros ingresos" entre Utilidad bruta y
  Gastos operativos. Los generadores no cambiaron.
* `DevDataInitializer` (solo la siembra de demostracion): saldo inicial Caja General
  1950000 y Bancolombia 2000000 fijado en la cuenta, y un ingreso ocasional "Reintegro de
  proveedor" de 50000 a Caja el primer dia del mes actual. Saldos finales iguales.
* Docs: 03-DECISIONS ("Los ingresos ocasionales suman a la utilidad neta", con el hallazgo
  del saldo inicial y la nota del frontend), 01-ARCHITECTURE (KPIs, Estado de resultados y
  contrato de `/kpis`), 02-CONVENTIONS (cifras de la siembra de demostracion).

## Pruebas

* `ReportingTest` (+5): sin ingresos `otherIncome` 0 y neta igual; dentro del rango sube
  exactamente y los de fuera no cuentan (bordes inclusivos); editar, mover de fecha y
  eliminar; cobro, pago, gasto y transferencia no son `otherIncome`; formula completa y
  aislamiento por tenant por HTTP. Ajustada `grossProfitYNetProfitCombinanVentasComprasYGastos`.
* `ReportExportTest` (+2): PDF y XLSX de octubre sin ingresos ocasionales. Ajustadas las
  tres del Estado de resultados (seis filas, orden, valores de `/kpis`, estilo).
* `DevDemoDataTest`: cifras nuevas de la siembra y saldos iniciales.
* Total: 368 antes, 375 despues.

## Verificacion real

* Perfil dev, rama en 8091 y `main` en 8092 para comparar. Septiembre: ventas 748510,
  compras 357000, otros ingresos 0, gastos 0, neta 391510. Octubre: ventas 267750, compras 0,
  otros ingresos 50000, gastos 450000, neta -132250. Saldos Caja 1946250 y Banco 2230650
  en las dos versiones.
* Ingreso de 123456: `otherIncome` y `netProfit` suben exactamente eso; editado a 100000 y
  eliminado vuelven. PDF y XLSX con las seis filas iguales a `/kpis`. IVA identico a `main`;
  Movimientos de cuentas solo difiere por los datos de la siembra. Tenant nuevo: todo en 0.

## Pendiente

* Fijar el saldo inicial de una cuenta: recomendado un `PUT` de cuentas (ver 03-DECISIONS).
* Frontend: vista previa de Reportes y definiciones del panel, en rama aparte.
