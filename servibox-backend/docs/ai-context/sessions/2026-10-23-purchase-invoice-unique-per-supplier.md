# 2026-10-23: Numero de factura de compra unico por proveedor

Rama `fix/32-purchase-invoice-unique-per-supplier`. Objetivo: que el numero de factura de
compra sea unico por tenant, proveedor y numero (migracion V3), y probar las migraciones
contra PostgreSQL 16 real.

## Lo que habia

* `uk_compra_tenant_invoice_number UNIQUE (id_tenant, numero_factura_compra)` en V1; V2 no
  toca COMPRAS. La restriccion distingue mayusculas.
* `PurchasesService.validarNumeroFacturaUnico` comparaba por tenant sin distinguir
  mayusculas (`findByInvoiceNumberIgnoreCaseAndTenantId`), anuladas incluidas (una anulada
  conserva su fila y su numero). No traducia la violacion al insertar: dos compras
  simultaneas daban 500. Ventas si la traduce (`uk_venta_tenant_invoice_number`).
* `SchemaConformanceTest` compara restricciones unicas, pero su parseo del diccionario solo
  conocia la unica simple y la (id_tenant, columna).
* Ni la siembra dev ni la del registro dependen de la unicidad por tenant.

## Lo que se hizo

* `V3__purchase_invoice_unique_per_supplier.sql`: `DROP CONSTRAINT` de la anterior y
  `ADD CONSTRAINT uk_compra_tenant_supplier_invoice_number UNIQUE (id_tenant, id_tercero,
  numero_factura_compra)`.
* `Purchase` con la restriccion nueva; `PurchaseRepository` con
  `findByInvoiceNumberIgnoreCaseAndSupplierIdAndTenantId` (se retiro el metodo por tenant);
  `DuplicatePurchaseInvoiceNumberException` nombra al proveedor; `crearFactura` traduce la
  violacion de la restriccion al insertar a esa misma excepcion (409).
* 04-DATA-MODEL (la UK nueva) y el parseo de `SchemaConformanceTest` ("con id_tenant e
  id_tercero").
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Numero de factura de compra unico por
  proveedor (V3)".

## Pruebas existentes ajustadas

* `PurchasesFlowTest.dosComprasConElMismoNumeroEnElMismoTenantFalla`: el mensaje ahora
  nombra al proveedor.
* `FlywayMigrationTest.v2AplicaSobreV1ConDatosYDejaLasColumnasNuevasEnNull`: la segunda
  migracion lleva `target("2")`, porque sin limite ahora llega a V3 y la prueba es de V2.

## Verificacion

* Tests: 351 antes, 357 despues. Nueva `purchases.PurchaseInvoiceNumberPerSupplierTest` (5)
  y `FlywayMigrationTest.v3AplicaSobreV2ConComprasYHaceElNumeroUnicoPorProveedor`. La prueba
  de concurrencia choca de verdad con la restriccion (aviso de Hibernate en el log) y da
  201 y 409.
* Prueba real en perfil dev (8080): Flyway aplica V1, V2 y V3; la siembra sale igual; el
  mismo numero para dos proveedores (201 y 201), repetido con el primero (409) y en
  minusculas (409), otro numero (201).
* PostgreSQL 16: no se hizo. El motor de Docker no estaba corriendo y no se arranco.
