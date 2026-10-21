# 2026-10-21: Datos de demostracion en el perfil dev

Rama `feat/26-dev-demo-data`. Objetivo: que el perfil dev arranque con datos de
demostracion para probar el frontend y Reportes sin sembrar nada a mano.

## Lo que habia

* `DevDataInitializer` (`@Profile("dev")`, `CommandLineRunner`) solo sembraba si no habia
  tenants: tenant `demo`, usuario `admin`, IVA 0.19, 8 categorias sin umbrales y 2 cuentas
  en cero, por `InventoryService` y `TreasuryService`.
* Ninguna prueba activaba `dev`: todas usan `@ActiveProfiles("test")` salvo
  `ServiboxApplicationTests`, que corre sin perfil. Ninguna veia la siembra.
* Ventas: `SalesService.crearFactura` toma el consecutivo `FV-` si no llega numero y la
  fecha de `invoiceDate` (sin ella, hoy del `Clock` de negocio). Compras:
  `PurchasesService.crearFactura` exige numero (el del proveedor), no hay consecutivo.
* El estado de stock lo calcula el frontend (`src/lib/stock.ts`): critico si stock <= rojo,
  bajo si <= amarillo.

## Lo que se hizo

* `servibox.dev.demo-data` en `application-dev.properties` nuevo (`DEV_DEMO_DATA`, true por
  defecto). `run` hace la siembra base como antes y despues, si la propiedad esta activa,
  `sembrarDemostracion`, que solo llama a los services. Cifras en
  [02-CONVENTIONS.md](../02-CONVENTIONS.md), "Datos de demostracion en dev".
* Idempotente por productos del tenant `demo`. Un fallo deja un `WARN` con el paso y el
  arranque sigue. Una linea `INFO` resume lo creado.
* Sin cambios de esquema, de endpoints ni de la siembra base.
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Datos de demostracion en el perfil dev".

## Verificacion

* Tests: 242 antes, 247 despues. Ninguna prueba existente cambio. Nuevas
  `config.DevDemoDataTest` (4, perfiles `test` y `dev`: productos en cada estado, saldos,
  terceros, ventas en los dos meses, `/kpis` de los dos meses e inicializador ejecutado dos
  veces) y `config.DevDemoDataDisabledTest` (1, con la propiedad en false).
* Prueba real en perfil dev (puerto 8081: el 8080 lo ocupaba otro backend que no era de
  esta sesion): sin sembrar nada a mano, login, productos con stock 50, 10 y 3, saldos
  1946250 y 2230650, ventas FV-0001 a FV-0005 con la FV-0004 ANULADA y la FV-0002
  PENDIENTE con 32360, compra PROV-1001; `/kpis` de septiembre 748510 / 357000 / 391510 y
  de octubre 267750 / 0 / -182250 con gastos 450000; Estado de resultados de octubre en
  PDF y Excel con las mismas cifras. Reiniciado, sembro lo mismo.
