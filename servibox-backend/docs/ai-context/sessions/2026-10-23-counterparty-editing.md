# 2026-10-23: Edicion de terceros

Rama `feat/30-counterparty-editing`. Objetivo: `PUT /api/counterparties/{id}` para editar
nombre, tipo y numero de documento, correo y celular de un cliente o proveedor.

## Lo que habia

* `Counterparty` (TERCEROS): rol VARCHAR(20), nombre 150, tipo de documento 20 (CC, NIT,
  CE, RUT, PASAPORTE), numero 25, todos NOT NULL; correo 150 y celular 25 nulables.
  `uk_tercero_tenant_document (id_tenant, numero_documento)`, sensible a mayusculas.
* El POST valida con `@Valid` y da el "Peticion invalida" generico; no recorta espacios ni
  valida el formato del correo.
* Consumidor final: el tercero con documento `222222222222` (`DOCUMENTO_CONSUMIDOR_FINAL`).
  No hay otro tercero de sistema.
* VENTAS y COMPRAS guardan solo `id_tercero`; no copian datos del tercero.
* El documento es clave de negocio en `obtenerOCrear` (promocion a AMBOS desde el POST).

## Lo que se hizo

* `CounterpartyUpdateRequest` sin anotaciones, `CounterpartyService.actualizar`,
  `SystemCounterpartyException` (409) en `GlobalExceptionHandler` y el `PUT` en
  `CounterpartiesController`.
* Sin migracion; POST, GET y promocion sin cambios.
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Edicion de terceros".

## Verificacion

* Tests: 311 antes, 321 despues. Nueva `counterparties.CounterpartiesUpdateApiTest` (10).
  Ninguna prueba existente cambio.
* Prueba real en perfil dev (puerto 8080) con curl: edicion de Laura Gomez reflejada en el
  listado y en la venta FV-0001; 409 por documento de otro tercero y por el Consumidor
  final; 400 por nombre vacio y correo de 200; 404 por id inexistente; proveedor promovido
  a AMBOS por POST sigue AMBOS tras un PUT con `role`; 401 sin token.
