# 2026-10-21: Endpoints de edicion de inventario

Rama `feat/27-inventory-editing-endpoints`. Objetivo: editar productos y categorias, listar
impuestos y leer y guardar la configuracion financiera por API.

## Lo que habia

* `InventoryController` solo tenia GET y POST de categorias y productos y GET de producto
  por id. `taxAmount` y `suggestedPrice` se guardan en la base (`iva_producto` y
  `precio_sugerido`, NOT NULL, 2 decimales) y los rehace `recalculatePrices`.
* POST de producto: `@NotBlank` codigo y descripcion, `@NotNull` costo, cantidad y
  categoria; codigo unico por tenant (409), con distincion de mayusculas. POST de
  categoria: todo `@NotNull` salvo impuestos, sin rangos, sin unicidad de nombre (el
  nombre se guarda en mayusculas), e ignora los `taxTypeIds` que no son del tenant.
* Lineas de venta y compra: FK `id_producto`, con precio e IVA propios y sin copia del
  codigo ni de la descripcion.
* `FinancialSettings`: una fila por tenant (`uk_configuracion_tenant`), cuatro tasas
  NUMERIC(8,6) con CHECK entre 0 y 1.

## Lo que se hizo

* `PUT /products/{id}`, `PUT /categories/{id}`, `GET /tax-types`, `GET` y
  `PUT /financial-settings`. Excepciones nuevas `ProductCodeLockedException` y
  `DuplicateCategoryNameException` (409). `existsByProductId` en los repositorios de lineas.
* `recalculatePrices` se partio en la formula (`aplicarPrecios`) y el guardado, para
  recalcular muchos productos con una sola lectura de la configuracion. Sin cambio de
  comportamiento.
* Las validaciones de unicidad van antes de modificar la entidad: con la entidad ya
  modificada, la consulta hace que Hibernate escriba antes la fila (paso con la comision
  nula de la configuracion en una segunda llamada, que daba 500; corregido).
* Sin cambios de esquema.
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Edicion de productos y categorias,
  impuestos y configuracion financiera por API".

## Verificacion

* Tests: 247 antes, 276 despues. Ninguna prueba existente cambio. Nuevas
  `inventory.InventoryEditingTest` (20) e `inventory.InventorySettingsApiTest` (9).
* Prueba real en perfil dev (puerto 8081): DEMO-002 de 15000 a 18000 da IVA 3420 y
  sugerido 25714.29; margen de REPUESTOS DEMO de 0.30 a 0.40 deja 50000 / 30000 /
  416666.67 y FV-0001 conserva 5 x 45000 y 1 x 360000; configuracion 0.10 / 0.05 / 0.0082
  deja 57408.24 / 34444.94 / 478401.97. 409 al cambiar el codigo de DEMO-001, 200 con un
  producto nuevo sin facturas, 409 por codigo y por nombre de categoria repetidos, 404 y
  401.
* La respuesta del PUT (como la del POST) trae el precio sin redondear; el GET lo trae con
  los 2 decimales de la columna.
