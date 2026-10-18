# 2026-10-18: Endpoints de terceros

Rama `feat/21-counterparties-endpoints`. Objetivo: alta y listado de terceros (clientes y
proveedores) por la API, que era el primer pendiente de
[2026-10-06-refactor-normalizacion.md](2026-10-06-refactor-normalizacion.md).

## Lo que habia

* `CounterpartyService.crear`: valida y rechaza **cualquier** documento repetido en el
  tenant con 409, sin mirar el rol.
* `CounterpartyService.obtenerOCrear`: el camino de las facturas. Si el documento no
  existe lo crea; con el rol contrario lo pasa a AMBOS; con el mismo rol o AMBOS lo
  devuelve sin error. Correo y celular solo completan vacios; nombre y tipo de documento no
  cambian.
* `consumidorFinal()`: CLIENTE, NIT `222222222222`, creado la primera vez que una venta
  llega sin `customerId`. Hasta entonces no existe.
* `findAll()` usaba el `findAll` heredado (solo el filtro de Hibernate); no habia listado
  por rol ni orden.

## Lo que se hizo

* `CounterpartiesController` con `GET` y `POST /api/counterparties`, y
  `CounterpartyRequest`. La respuesta es el `CounterpartyResponse` existente.
* `CounterpartyService.listar(rol)` con dos consultas derivadas que llevan el `tenantId`
  explicito. Si el listado incluye clientes asegura antes el Consumidor final.
* El POST rechaza con 409 el mismo rol o AMBOS y delega el resto en `obtenerOCrear`
  (201 si es nuevo, 200 si promovio). La 409 nombra el rol actual: constructor nuevo en
  `DuplicateCounterpartyDocumentException`.
* Sin cambios de esquema: no hizo falta V2.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Endpoints de terceros: alta y
  listado". Arquitectura: [01-ARCHITECTURE.md](../01-ARCHITECTURE.md), Modulo
  Counterparties.

## Verificacion

* Tests: 196 antes, 207 despues. Ninguna clase perdio tests; nueva
  `counterparties.CounterpartiesApiTest` (11).
* Prueba real en perfil dev con curl: login 200; alta de cliente y de proveedor 201; listado
  por CLIENTE (con el Consumidor final), por PROVEEDOR y sin filtro 200; documento repetido
  409; `role=X` 400; el cliente dado de alta como PROVEEDOR 200 y AMBOS; sin token 401;
  producto 201, ingreso ocasional 201, venta de contado con el cliente 201 y compra de
  contado con el proveedor 201.
