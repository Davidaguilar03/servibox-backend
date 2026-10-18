# 2026-10-18: Consecutivo de la factura de venta

Rama `feat/23-sale-invoice-numbering`. Objetivo: que una venta sin `invoiceNumber` reciba
el siguiente consecutivo `FV-` del tenant, y exponer cual sera el siguiente.

## Lo que habia

* `SaleRequest.invoiceNumber` con `@NotBlank` (400 "Peticion invalida" si faltaba), y
  `SalesService.validarNumeroFacturaUnico` con "La factura necesita un numero" (400) y la
  busqueda `findByInvoiceNumberIgnoreCaseAndTenantId` (409 si existe, sin distinguir
  mayusculas).
* `uk_venta_tenant_invoice_number UNIQUE (id_tenant, numero_factura_venta)` en la entidad y
  en V1: por tenant, distingue mayusculas. Columna `VARCHAR(50) NOT NULL`.
* Las ventas no se borran: solo se anulan (`POST /api/sales/{id}/annul`). El numero de una
  anulada sigue en la tabla.
* Una venta simultanea con el mismo numero pasaba la validacion y el INSERT daba 500.

## Lo que se hizo

* `invoiceNumber` opcional; si falta o esta en blanco, `siguienteNumeroFactura()`.
* `GET /api/sales/next-invoice-number`, sin reserva.
* La violacion de `uk_venta_tenant_invoice_number` al insertar se traduce a la 409 de
  `DuplicateInvoiceNumberException`. Se comprobo con una prueba temporal de dos hilos (que
  no queda en el repo): la segunda venta recibio esa excepcion.
* Sin cambios de esquema ni en compras.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Consecutivo de la factura de
  venta".

## Hallazgo lateral

* La prueba de dos hilos mostro otra carrera, anterior a esta rama: dos ventas
  simultaneas sin cliente en un tenant que todavia no tiene Consumidor final intentan
  crearlo las dos, y la segunda choca con `uk_tercero_tenant_document` (500). No se toco.

## Verificacion

* Tests: 209 antes, 219 despues. Ninguna prueba existente cambio; nueva
  `sales.SalesInvoiceNumberingTest` (10).
* Prueba real en perfil dev con curl: GET siguiente `FV-0001`; dos ventas de contado sin
  numero, `FV-0001` y `FV-0002` (201); GET dos veces `FV-0003`; anular `FV-0002` (200) y el
  GET sigue en `FV-0003`; la venta siguiente recibe `FV-0003` (201); `FV-0001` explicito 409;
  GET sin token 401.
