# 2026-10-06: Refactor de normalizacion del modelo de datos

Objetivo: llevar el modelo de datos de ServiBox al esquema de
[04-DATA-MODEL.md](../04-DATA-MODEL.md) (14 entidades en tercera forma normal). Se hace
en seis pasos (eran cinco; el 2026-10-17 el antiguo paso 4 se partio en dos), cada uno con
`./mvnw test` completo en verde.

## Plan

- [x] **1. Terceros.** `Customer` y `Supplier` se unifican en `Counterparty` (`TERCEROS`),
  con rol CLIENTE, PROVEEDOR o AMBOS. Hecho el 2026-10-06, rama `refactor/15-terceros`.
- [x] **2. Movimientos con categoria.** Transferencias, ingresos ocasionales y gastos
  operativos pasan a ser movimientos con `categoria_movimiento`. Hecho el 2026-10-06, rama
  `refactor/16-unify-treasury-movements`.
- [x] **3. Abonos y pagos como movimientos.** Abonos y pagos pasan a ser movimientos con FK
  a la factura (`id_venta`, `id_compra`), y anular y restaurar se redisenan. Hecho el
  2026-10-17, rama `refactor/17-collections-payments-as-movements`.
- [x] **4. Comportamiento y atributos.** Alinear comportamiento y atributos con el
  diccionario (roles, tipos de cuenta, comision de tarjeta, tercero obligatorio) y
  corregir lo que dejo la revision del paso 3. Sin renombrar tablas ni columnas ni cambiar
  tipos fisicos. Hecho el 2026-10-17, rama `refactor/18-align-attributes-with-dictionary`.
- [x] **5. Nombres fisicos, nulabilidad y longitudes.** Nombres de tablas y columnas,
  NOT NULL y longitudes del diccionario, con una prueba de conformidad contra el
  diccionario. Hecho el 2026-10-17, rama `refactor/19-align-physical-schema`.
- [x] **6. Flyway.** Migracion V1 escrita desde el diccionario, con los tipos fisicos del
  diccionario (corregido donde el codigo tenia razon). Hecho el 2026-10-18, rama
  `feat/20-flyway-database-migrations`.

## Paso 1: terceros

* Paquete nuevo `counterparties`: `Counterparty`, `CounterpartyRole`, `DocumentType`,
  `CounterpartyRepository`, `CounterpartyService`, `CounterpartyResponse` y dos excepciones
  de dominio.
* `Sale.customer` y `Purchase.supplier` apuntan a `Counterparty` por `id_tercero`. Se
  eliminaron `Customer`, `Supplier`, sus repositories y las tablas `CLIENTES` y
  `PROVEEDORES`.
* La API sigue identificando al tercero por id; la respuesta lo devuelve como objeto
  `customer` / `supplier`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Clientes y proveedores se
  unifican en terceros (Counterparty)".
* Tests: 135 antes, 146 despues. Ninguna clase perdio tests; `CounterpartyTest` es nueva
  (11).

### Anotado para el paso 6 (Flyway)

* La PK de `TERCEROS` ya se llama `id_tercero` por `@AttributeOverride`; las demas tablas
  siguen con `id`.
* El tenant sigue en `tenant_id` en todas las tablas, `TERCEROS` incluida: la condicion del
  `@Filter` de `TenantAwareEntity` lo tiene escrito. El diccionario dice `id_tenant`.
* Resuelto en el paso 5: todas las PK son `id_<entidad>` y el tenant es `id_tenant`.

## Paso 2: movimientos con categoria

* `Movement` gana `category` (`MovementCategory`, obligatoria), `notes` y
  `relatedMovement` (`id_movimiento_relacionado`). Se eliminaron `Transfer`,
  `OccasionalIncome`, `OperationalExpense`, sus repositories, sus DTOs y sus tres tablas.
* Una transferencia son dos movimientos TRANSFERENCIA; el INGRESO apunta al EGRESO.
* Gastos e ingresos ocasionales son los unicos movimientos manuales:
  `POST`, `PUT` y `DELETE /api/treasury/movements`. Desaparecen `/occasional-incomes`,
  `/operational-expenses` y el movimiento suelto.
* Ventas y compras crean RECAUDO_VENTA y PAGO_COMPRA, todavia enlazados por
  `(sourceType, sourceId)` con solo SALE y PURCHASE.
* Reporting lee gastos e ingresos ocasionales de los movimientos.
* `04-DATA-MODEL.md` se movio de `docs/` a `docs/ai-context/`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Transferencias, ingresos
  ocasionales y gastos operativos son movimientos con categoria".
* Tests: 146 antes, 158 despues. Ninguna clase perdio tests; `InvoiceMovementCategoryTest`
  es nueva (4).

### Anotado para el paso 5 (nombres de columna; antes paso 4)

* Las columnas que ya existian en `MOVIMIENTOS` conservan su nombre (`concepto_movimiento`,
  `monto_movimiento`); el diccionario dice `concepto` y `monto`. Resuelto en el paso 5.

## Paso 3: abonos y pagos como movimientos

* `Movement` cambia `sourceType` / `sourceId` por `sale` (`id_venta`) y `purchase`
  (`id_compra`), claves foraneas reales sin cascada, con el `CHECK`
  `ck_movimiento_factura`. Se eliminaron `MovementSourceType`, `Collection`, `Payment`,
  sus repositories, sus DTOs de respuesta y las tablas `RECAUDOS` y `PAGOS`.
* Un abono es un `RECAUDO_VENTA` con `sale` y un pago un `PAGO_COMPRA` con `purchase`; el
  contado tambien. `/collections` y `/payments` responden con `MovementResponse`, que
  expone `saleId` y `purchaseId`.
* `Sale` y `Purchase` ya no guardan cuenta (`id_cuenta`), ni la exponen en la respuesta.
* Movimiento activo: el de una factura `ANULADA` es inactivo, derivado del estado con
  `MovementSpecifications.activo()`; todos los listados y sumas lo aplican.
  `GET /api/treasury/movements` gana `saleId` y `purchaseId`.
* Anular revierte saldos sin borrar filas; restaurar vuelve a aplicarlos (en compras, con
  saldo suficiente validado entero antes de escribir).
* Reporting: la cuenta de los renglones `VENTA` / `COMPRA` sale del ultimo movimiento de
  la factura.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Abonos y pagos son
  movimientos, y anular deja los movimientos inactivos".
* Limitacion conocida: el historial de movimientos de una factura anulada queda en la base
  pero ningun endpoint lo expone.
* Tests: 159 antes, 169 despues. `SalesRestoreTest` y `PurchasesRestoreTest` pierden el
  test de la defensa contra movimientos vivos (ya no aplica) y ganan uno cada uno, asi que
  quedan igual (9); `InvoiceMovementsApiTest` es nueva (4).

## Paso 4: comportamiento y atributos

* `Role`: `ADMIN`, `MECANICO`, `CAJERO` (sin `EMPLEADO`). `AccountType`: `EFECTIVO`,
  `BANCO`, `DIGITAL` (sin `CASH` ni `BANK`). Sin logica de permisos por rol.
* `FinancialSettings.cardCommissionRate` obligatoria con 0.0 por defecto; una sola fila
  por tenant (`uk_configuracion_tenant` y `DuplicateFinancialSettingsException`, 409).
* Venta sin `customerId`: Consumidor final del tenant (`CounterpartyService.consumidorFinal`,
  NIT 222222222222). Compra sin `supplierId`: 400.
* Transferencia: el concepto de las dos patas es el texto del usuario, o el autogenerado
  si no escribe ninguno; `notes` vacio.
* `pendingBalance` 0 en la respuesta de una factura `ANULADA`; `saldoPendiente` no cambia.
* `sumarPorCategoria` vuelve a sumar en la base (`MovementRepository.sumarMonto`, Criteria)
  con el mismo `MovementSpecifications.activo()`.
* Reporting: cuenta del renglon VENTA / COMPRA = la del ultimo movimiento activo.
* `Product.taxAmount` (`iva_producto`) guarda un monto en pesos por unidad
  (`purchaseCost * tasa de IVA`), no la tarifa del diccionario. No se cambio; sigue en
  Pendientes de 04-DATA-MODEL.md.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Comportamiento y atributos
  alineados con el diccionario".
* Tests: 169 antes, 181 despues. Ninguna clase perdio tests.

### Anotado para el paso 5

* Las columnas `id_tercero` de VENTAS y COMPRAS todavia admiten nulos aunque el service ya
  nunca los deja nulos.
* Un valor de enum invalido en el cuerpo (por ejemplo `"type":"CASH"`) responde 500, no
  400: `GlobalExceptionHandler` no trata `HttpMessageNotReadableException`. Viene de antes.
* Resueltos los dos en el paso 5.

## Paso 5: nombres fisicos, nulabilidad y longitudes

* Nombres del diccionario: PK `id_<entidad>` con `@AttributeOverride`, tenant `id_tenant`
  (clase base, `@Filter`, `@UniqueConstraint`), `concepto` y `monto` en MOVIMIENTOS. FK
  `id_tenant -> TENANTS` en todas las tablas de negocio por un `ManyToOne` de solo lectura
  en `TenantAwareEntity`.
* `CATEGORIA_IMPUESTOS` con PK compuesta: `ProductCategory.taxTypes` pasa a `Set`.
* NOT NULL y `length` del diccionario en todas las entidades, tambien en los enums. CHECK
  `ck_movimiento_monto`, `ck_detalle_compra_cantidad` y `ck_detalle_venta_cantidad`.
* Caminos que violaban las reglas, corregidos sin relajarlas: `saveProduct` calcula
  precios antes del INSERT; `CategoryRequest` y `ProductRequest` exigen los campos NOT NULL;
  la semilla de desarrollo pone umbrales y margen en 0.
* `HttpMessageNotReadableException` es 400, con campo y valores permitidos si es un enum.
* 04-DATA-MODEL.md: `iva_producto` es un monto NUMERIC(14,2); `tipo_movimiento` sin
  TRANSFERENCIA; UK compuestas de `nombre_cuenta` y `numero_factura_compra`; CHECK en
  Decisiones; `medio_pago_*` en Pendientes.
* `schema.SchemaConformanceTest` compara el esquema con el diccionario.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Nombres fisicos, nulabilidad
  y longitudes del diccionario".
* Tests: 181 antes, 192 despues. Ninguna clase perdio tests; nuevas
  `schema.SchemaConformanceTest` (1), `schema.CheckConstraintsTest` (3) y
  `shared.RequestBodyErrorsTest` (7).

### Para el paso 6: desviaciones de tipo

Las 76 de `DESVIACIONES_CONOCIDAS` en `SchemaConformanceTest`. El paso 6 las resuelve y
vacia la lista; la prueba falla si una entrada deja de ocurrir.

* **BIGINT contra INTEGER (38), identificadores `Long`.** PK y FK de todas las tablas, e
  `id_tenant` en todas menos `CATEGORIA_IMPUESTOS`: TENANTS.id_tenant; USUARIOS.id_usuario,
  id_tenant; CONFIGURACION_FINANCIERA.id_configuracion, id_tenant; CUENTAS.id_cuenta,
  id_tenant; MOVIMIENTOS.id_movimiento, id_tenant, id_cuenta, id_movimiento_relacionado,
  id_compra, id_venta; TERCEROS.id_tercero, id_tenant; COMPRAS.id_compra, id_tenant,
  id_tercero; DETALLE_COMPRAS.id_detalle_compra, id_tenant, id_compra, id_producto;
  VENTAS.id_venta, id_tenant, id_tercero; DETALLE_VENTAS.id_detalle_venta, id_tenant,
  id_venta, id_producto; PRODUCTOS.id_producto, id_tenant, id_categoria_producto;
  CATEGORIA_PRODUCTOS.id_categoria_producto, id_tenant; TIPOS_IMPUESTO.id_impuesto,
  id_tenant; CATEGORIA_IMPUESTOS.id_categoria_producto, id_impuesto.
* **DOUBLE contra NUMERIC (23), montos y porcentajes `Double`.**
  CONFIGURACION_FINANCIERA.porcentaje_gastos, porcentaje_dian, porcentaje_ica,
  porcentaje_comision_tarjeta; CUENTAS.balance_inicial, saldo_actual; MOVIMIENTOS.monto;
  COMPRAS.subtotal_compra, iva_total_compra, total_compra; DETALLE_COMPRAS.precio_compra,
  iva_linea_compra; VENTAS.subtotal_venta, iva_por_pagar_venta, total_venta;
  DETALLE_VENTAS.precio_venta, iva_generado_linea, diferencia_iva_linea;
  PRODUCTOS.costo_compra, iva_producto, precio_sugerido; CATEGORIA_PRODUCTOS.margen_utilidad;
  TIPOS_IMPUESTO.tasa_impuesto. Los porcentajes ademas son fracciones y el diccionario los
  escribe en 0.00 a 100.00.
* **DATE contra TIMESTAMP (5), fechas de negocio `LocalDate`.**
  MOVIMIENTOS.fecha_movimiento; COMPRAS.fecha_compra, fecha_vencimiento_compra;
  VENTAS.fecha_venta, fecha_vencimiento_venta.
* **ENUM contra VARCHAR (10), `@Enumerated(STRING)` en H2.** USUARIOS.rol;
  CUENTAS.tipo_cuenta; MOVIMIENTOS.tipo_movimiento, categoria_movimiento;
  TERCEROS.tipo_tercero, tipo_documento; COMPRAS.forma_pago_compra, estado_compra;
  VENTAS.forma_pago_venta, estado_venta. En PostgreSQL saldran VARCHAR con su `length`; la
  migracion V1 los escribe como VARCHAR.
* Resueltas las 76 en el paso 6: la lista esta vacia.

## Paso 6: Flyway

* `spring-boot-starter-flyway` y `flyway-database-postgresql`. Migracion
  `src/main/resources/db/migration/V1__init_schema.sql`, escrita a mano desde
  04-DATA-MODEL.md: 14 tablas, PK `GENERATED BY DEFAULT AS IDENTITY`, FK con nombre y
  `ON DELETE RESTRICT`, unicas `uk_...` de las entidades, los CHECK de las entidades y los de
  porcentajes de CONFIGURACION_FINANCIERA. Sin datos semilla.
* `ddl-auto=validate` y Flyway en todos los perfiles. Local: H2 en memoria en modo
  PostgreSQL (antes, la H2 embebida de la autoconfiguracion). Tests: H2 en modo PostgreSQL
  con una base por contexto (`${random.uuid}` en la URL). Perfil `prod` nuevo con
  `DB_URL`, `DB_USERNAME` y `DB_PASSWORD`.
* Tipos (el diccionario se corrigio, no el codigo): ids `BIGINT`; montos `NUMERIC(14,2)` y
  tasas como fraccion `NUMERIC(8,6)`, los dos `Double` con `@JdbcTypeCode(SqlTypes.NUMERIC)`;
  fechas de negocio `DATE`; enums `VARCHAR` con `@JdbcTypeCode(SqlTypes.VARCHAR)` (la
  propiedad `hibernate.type.preferred_enum_jdbc_type` no existe en Hibernate 7.4: se probo y
  Hibernate seguia generando `enum` en H2). Auditoria y `fecha_creacion_tenant` en UTC
  (`DateTimeProvider` de `JpaAuditingConfiguration` y `Tenant.onCreate`).
* `SchemaConformanceTest` corre contra el esquema de Flyway, sin `flyway_schema_history`;
  compara BIGINT, DATE, NUMERIC con precision y escala y VARCHAR con longitud, y exige
  `ON DELETE RESTRICT` en toda FK. Lista de desviaciones vacia. Se probo que falla con el
  diccionario alterado (monto 14,3, `id_cuenta` INTEGER, `fecha_venta` TIMESTAMP,
  `tasa_impuesto` 5,2) y con una FK sin RESTRICT en V1, y se restauraron los dos archivos.
* La relacion de solo lectura a Tenant en `TenantAwareEntity` ya era LAZY; ahora hay una
  prueba de que cargar una entidad no lee TENANTS.
* Un enum invalido en un parametro de query (`?category=X`) es 400 con el parametro y los
  valores permitidos (`MethodArgumentTypeMismatchException`); antes, 500.
* Ningun test fallo por el redondeo a dos decimales de los montos ni a seis de las tasas.
* **Pendiente:** V1 solo se ha probado en H2 en modo PostgreSQL. Docker esta instalado en la
  maquina de desarrollo pero el demonio no corria, asi que no se agrego la prueba con
  Testcontainers y PostgreSQL 16.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "Flyway gestiona el esquema y
  tipos fisicos definitivos".
* Tests: 192 antes, 196 despues. Ninguna clase perdio tests; nueva
  `schema.FlywayMigrationTest` (3) y un test mas en `shared.RequestBodyErrorsTest` (8).

## Pendientes despues del refactor

* Dinero como `Double` en el codigo (con `NUMERIC(14,2)` en la base): migrar a `BigDecimal`
  en una rama aparte.
* Validar los valores de `medio_pago_compra` y `medio_pago_venta` contra los del
  diccionario.
* El historial de movimientos de una factura anulada queda en la base, pero ningun endpoint
  lo expone (limitacion del paso 3).
* Probar V1 contra PostgreSQL 16 real (Testcontainers) cuando haya Docker disponible.
