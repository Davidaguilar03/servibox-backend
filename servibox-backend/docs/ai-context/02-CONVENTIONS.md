# Convenciones

## Convenciones de nombres

* Entidades, campos, DTOs y repositorios en ingles (`Sale.invoiceDate`,
  `findByIdAndTenantId`). Tablas y columnas en espanol, ver Base de datos en
  [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
* Nombres fisicos: **los de [04-DATA-MODEL.md](04-DATA-MODEL.md)**, sin inventar. La PK de
  cada entidad es `id_<entidad>` (`id_venta`, `id_detalle_venta`, `id_categoria_producto`)
  con `@AttributeOverride(name = "id")`; el campo Java sigue siendo `id`. La columna de
  tenant es `id_tenant`; la propiedad Java, `tenantId`. Nulabilidad y `length` tambien del
  diccionario, incluidas las columnas de enum. `schema.SchemaConformanceTest` compara el
  esquema con el diccionario: quien toque una entidad o el diccionario corre esa prueba.
* CHECK: `ck_<tabla>_<regla>` (`ck_movimiento_monto`, `ck_detalle_venta_cantidad`),
  declarados en `@Table(check = ...)`.
* Operaciones de negocio de los services en espanol (`crearFactura`, `registrarAbono`,
  `anularFactura`, `registrarGastoOperativo`). Inventory conserva los nombres en ingles
  portados de Autollantas (`saveProduct`, `recalculatePrices`).
* Restricciones unicas: `uk_<entidad>_tenant_<campo>`, ver Unicidad mas abajo.
* Rutas: `/api/<modulo>/<recurso-en-plural-kebab>`; acciones sobre un recurso como
  subruta `POST` (`/{id}/annul`, `/{id}/collections`, `/{id}/payments`).
* Tests: nombre en espanol que describe el comportamiento
  (`anularNoRevierteElCostoDelProducto`).

## Cambios de esquema

* Todo cambio de tablas, columnas, tipos o restricciones es una **migracion nueva**
  `src/main/resources/db/migration/V<n>__<descripcion>.sql`, con `<n>` el siguiente numero.
  Nunca se edita una migracion ya aplicada: Flyway guarda su checksum y una base que ya la
  corrio no arranca.
* En el mismo cambio: la entidad (Hibernate valida con `ddl-auto=validate`) y
  [04-DATA-MODEL.md](04-DATA-MODEL.md), que es la especificacion. SQL compatible con
  PostgreSQL 16 y con H2 en modo PostgreSQL, sin comillas en los identificadores, FK con
  nombre y `ON DELETE RESTRICT`.
* Correr `./mvnw test`: `schema.SchemaConformanceTest` compara el resultado con el
  diccionario.
* Dinero en `NUMERIC(14,2)` y tasas como fraccion en `NUMERIC(8,6)`: un campo `Double`
  nuevo de ese tipo lleva `@JdbcTypeCode(SqlTypes.NUMERIC)` con su `precision` y `scale`; un
  enum nuevo, `@Enumerated(EnumType.STRING)` con `@JdbcTypeCode(SqlTypes.VARCHAR)`.

## Estilo de codigo

* Lombok para getters, setters y constructores (`@RequiredArgsConstructor` para inyeccion).
* Todo lo que mueve dinero pasa por `TreasuryService`; ningun otro modulo toca
  `AccountRepository` ni `MovementRepository`. El stock si se ajusta directo:
  `SalesService` y `PurchasesService` usan `ProductRepository`, y los calculos de precio e
  IVA los piden a `InventoryService`.

## Manejo de errores

Excepciones de dominio en el paquete `service` de su modulo, traducidas en un solo sitio,
`GlobalExceptionHandler`. Tabla de codigos en [01-ARCHITECTURE.md](01-ARCHITECTURE.md)
(Manejo centralizado de errores). Criterio: duplicado o falta de stock / saldo es **409**;
operacion no permitida por el estado del documento es **400**; funcionalidad apagada por
configuracion (registro publico) o sin el rol necesario es **403**; recurso de ruta es **404**,
ver Busqueda por id mas abajo. Un cuerpo que no se puede leer (JSON mal formado, valor de
enum que no existe) es **400**, nunca 500; si es un enum, el mensaje nombra el campo y los
valores permitidos. Un campo NOT NULL en la base es `@NotNull` / `@NotBlank` en su
`Request`, para que falte con 400 y no con el error crudo de la base. Enlace de activacion
(4B): no valido **400**, vencido o reemplazado **410**, ya usado **409**; limite de reenvio
de activacion **429**. No confundir este 429 de dominio con el limite de tasa por IP, que
sigue pendiente. Panel de plataforma (5A): version esperada distinta o transicion de
estado del tenant no permitida es **409** (no 400 como en documentos comerciales: lo pide
el contrato de plataforma, igual que 4A).

* **Secretos de un solo uso**: tokens de activacion y similares se guardan solo como
  SHA-256; el valor crudo no va a la base, logs ni respuestas. Si un mensaje pendiente
  necesita llevarlo, va cifrado (`mail.OutboxCipher`) y se borra al cerrar el mensaje.
  Los logs del outbox llevan id y categoria, nunca destinatario, cuerpo ni credenciales.

* **Validaciones de dominio**: rangos, largos de columna, fechas y formatos se comprueban en
  el service con un mensaje especifico en espanol, compatible con el mapa frontend, y **400** (la excepcion de
  dominio del modulo o `IllegalArgumentException`), nunca el "Peticion invalida" generico
  ni un 500 por pasar el largo de una columna. Se validan antes de modificar la entidad o
  de cualquier consulta que haga flush.
* **Rol**: una regla por rol usa la comprobacion de `TenantController` (authority
  `ROLE_ADMIN`) y lanza `AdminRequiredException` (**403**, cuerpo JSON de siempre), antes de
  validar el cuerpo. Hoy la exigen `PUT /api/tenant`, `PUT /api/inventory/financial-settings`
  y `POST` y `PUT /api/inventory/categories`.
* **Montos en mensajes**: siempre con `shared.Moneda.formatear` (pesos es-CO sin decimales,
  "$ 3.250.000"), nunca concatenando un `double`.
* **Precio sugerido e IVA del costo en pesos enteros**: `shared.Moneda.aPesosEnteros`
  redondea un `BigDecimal` al peso con HALF_UP (1966,5 es 1967), sin multiplos de 500 ni de
  1.000, y solo sobre el resultado final. El IVA por linea, los totales y los saldos siguen
  con centavos.

## Formato de respuestas API (DTOs)

* Color de cuentas: `color` opcional en POST /api/treasury/accounts y PUT
  /api/treasury/accounts/{id}. Valor no nulo exactamente #RRGGBB; se normaliza a
  mayusculas. En PUT, null u omision conservan el valor. `AccountResponse` incluye
  `color` incluso si es null. Los demas campos y propiedades desconocidas mantienen
  sus reglas. Ver decision del 2026-10-27 en 03-DECISIONS.md.

* Precio unitario en lineas de venta/compra: BigDecimal en la entrada y record del servicio,
  validacion transaccional de stripTrailingZeros().scale() antes de escribir. Si el precio
  positivo supera dos decimales significativos: 400 con El precio unitario admite como
  maximo dos decimales. Conservar obligatoriedad y signo; aceptar 0.0100 como 0.01.
  No redondear silenciosamente ni cambiar los importes de documentos historicos.

* Dinero general: Moneda.aCentavos(BigDecimal), HALF_UP a dos decimales; operaciones con
  BigDecimal.valueOf, nunca new BigDecimal(double). Moneda.sumar/restar para importes
  definitivos. Normalizar montos antes de validar saldo, persistir y responder.
* Facturas nuevas: multiplicar precio por cantidad en decimal, redondear el importe final
  de la linea y su IVA a centavos, sumar los definitivos. No redondear antes la tasa ni
  valores intermedios. Mantener congelados los importes historicos y el costo de venta.
* Exportacion: enteros como antes; importes con centavos con dos decimales visibles.
  No basta redondear la presentacion para arreglar diferencias con lo persistido.
* Listados: catalogos por name e id ASC (description en productos); documentos por
  invoiceDate e id DESC. Conservar movimientos DESC en pantalla y ASC en reporte, y el
  orden de bloqueo por id ASC. Agregar desempates no cambia filtros ni parametros.
* Tildes de mensajes: comprobar primero las expresiones regulares de errorMessages.ts
  del frontend. Solo cambiar texto fuera de su parte literal reconocida; si rompe la
  traduccion, documentarlo para una tarea frontend. Los estados y {error,status} siguen.

Todos los DTOs son `record` en el paquete `dto` del modulo: `XxxRequest` de entrada
(validado con `@Valid`) y `XxxResponse` de salida. Un controlador nunca devuelve una
entidad JPA: arrastraria `tenantId` y relaciones EAGER. Los valores calculados que no son
columna (por ejemplo `pendingBalance`) se exponen en el `Response`.

Un endpoint que devuelve un archivo (por ahora solo `GET /api/reporting/export`) responde
`ResponseEntity<byte[]>` con su `Content-Type` y `Content-Disposition: attachment` con un
nombre de archivo en ASCII, y **no declara `produces`**: el cliente manda
`Accept: application/json` y con `produces` la respuesta seria un 406. Sus errores siguen
siendo el JSON `{"error", "status"}` de siempre. En un Excel, los montos van como celdas
numericas con formato, nunca como texto.

## Terminologia de negocio

Nombres que vienen de Autollantas y se mantienen por consistencia entre los dos proyectos.
Cuando un nombre parece raro, casi siempre hay una razon; antes de "corregirlo", revisar
esta tabla.

### Inventory

| Termino | Significado | Cuidado |
|-|-|-|
| `quantity` | Nivel de stock de un producto | **Se llama `quantity`, NO `stock`.** Es el nombre en Autollantas y se conserva. No renombrar. |
| `purchaseCost` | Costo de compra **sin IVA** | Es la base de todos los calculos de precio |
| `taxAmount` | IVA del producto | En ServiBox suma solo los impuestos con `isVat` true, a diferencia de Autollantas. Ver [03-DECISIONS.md](03-DECISIONS.md) |
| `suggestedPrice` | Precio de venta sugerido | El margen es sobre el **precio de venta**, no sobre el costo. En pesos enteros, igual que `taxAmount` |
| `targetMargin` | Margen objetivo de la categoria | Fraccion, no porcentaje: `0.30` es 30 por ciento |
| `isVat` | Marca que un `TaxType` es IVA recuperable | Distingue el IVA de retenciones como ReteICA |
| `yellowStockMin` / `redStockMin` | Umbrales de alerta de stock | Amarillo es advertencia, rojo es critico |

### Treasury

| Termino | Significado | Cuidado |
|-|-|-|
| `initialBalance` | Saldo de apertura de la cuenta (`balance_inicial`) | Editable por PUT de cuenta, sin historial; mueve el saldo vivo en la diferencia sin crear movimientos |
| `currentBalance` | Saldo vivo (`saldo_actual`) | Lo cambian movimientos y edicion del saldo inicial, siempre por `TreasuryService` con bloqueo. Es el que suma el balance global; no se edita desde el cuerpo JSON |
| **Movimiento** | Un ingreso o egreso sobre una cuenta (`Movement`, tabla `MOVIMIENTOS`) | Es **el libro** de tesoreria. El saldo inicial no es ingreso ni movimiento. No hay otras tablas de dinero en `treasury` |
| `INGRESO` / `EGRESO` | Tipo del movimiento: entrada o salida de dinero | **Solo esos dos valores**: el tipo es lo que da el signo sobre el saldo. En Autollantas son los literales `String` `"Ingreso"` y `"Egreso"`; aqui enum, ver [03-DECISIONS.md](03-DECISIONS.md) |
| **Categoria de movimiento** | De donde viene el movimiento (`category`, `categoria_movimiento`) | `GASTO_OPERATIVO`, `INGRESO_OCASIONAL`, `TRANSFERENCIA`, `PAGO_COMPRA`, `RECAUDO_VENTA`. Obligatoria. **TRANSFERENCIA es una categoria, no un tipo**. Cada categoria fija su tipo, salvo TRANSFERENCIA, que tiene una pata de cada uno |
| **Movimiento manual** | Movimiento que el usuario crea, edita y elimina directamente | Solo `GASTO_OPERATIVO` e `INGRESO_OCASIONAL`. Los demas **se gestionan desde su operacion** (transferencia, venta, compra): editarlos o eliminarlos a mano es 400 |
| **Ingreso ocasional** | Ingreso puntual que no viene de una venta: reintegros, venta de chatarra, aporte del socio | **Es una categoria, no una entidad**: un `Movement` `INGRESO` de categoria `INGRESO_OCASIONAL`. En la interfaz de Autollantas vive bajo Ingresos > Ingresos Ocasionales |
| **Gasto operativo** | Gasto puntual del taller que no viene de una factura de compra: arriendo, servicios publicos, papeleria | **Es una categoria, no una entidad**: un `Movement` `EGRESO` de categoria `GASTO_OPERATIVO`. En Autollantas aparece bajo **Egresos**, junto a las compras, pero vive en `treasury` |
| Editar un movimiento manual | Revertir el efecto viejo y aplicar el nuevo **en la misma fila** | No es un ajuste por la diferencia ni un contra-movimiento: queda **un solo** movimiento, con el mismo id. La reversion devuelve el dinero a la cuenta **anterior**, asi que cambiar de cuenta sale bien. La categoria no se puede cambiar |
| Eliminar un movimiento manual | Revertir su efecto y **borrar la fila** | En Autollantas la accion se llama "Eliminar". **No deja estado `ANULADA`** como una factura: un gasto o un ingreso ocasional no es un documento fiscal |
| **Transferencia** | Dinero entre dos cuentas del mismo tenant | **Son dos movimientos** de categoria `TRANSFERENCIA`: un `EGRESO` en el origen y un `INGRESO` en el destino que apunta al `EGRESO` con `relatedMovement` (`id_movimiento_relacionado`). No hay entidad `Transfer`. El concepto de las dos patas es el del usuario o, si no escribe ninguno, el autogenerado ("Transferencia a / desde"); `notes` queda vacio |
| `originAccount` / `destinationAccount` | Los dos extremos de una transferencia, en la peticion (`originAccountId`, `destinationAccountId`) | Autollantas los llama `sourceAccount` / `destinationAccount`. Se renombro el origen para que coincida con la vista |
| `concept` | Texto que explica el movimiento | Columna real en `MOVIMIENTOS`, cosa que **Autollantas no tiene**, ver [03-DECISIONS.md](03-DECISIONS.md) |
| `notes` | Observaciones libres de un movimiento (`notas`) | **Opcional**. En una transferencia guarda el concepto que escribio el usuario |
| `date` | Fecha **de negocio**, `LocalDate` | No confundir con `createdAt` / `updatedAt`, que son `LocalDateTime` y son auditoria |
| `sale` / `purchase` | Factura de un movimiento (`id_venta` / `id_compra`) | Claves foraneas reales, sin cascada. `RECAUDO_VENTA` lleva `sale`, `PAGO_COMPRA` lleva `purchase`, las demas ninguna (`ck_movimiento_factura`). En la API, `saleId` / `purchaseId`. Ya no existen `sourceType` / `sourceId`. Nunca construir un `Movement` a mano: usar los metodos de `TreasuryService` |
| **Movimiento activo** / **inactivo** | Inactivo: enlazado a una venta o compra `ANULADA`. Activo: todos los demas | **No es una columna**: se deriva del estado de la factura (`MovementSpecifications.activo()`). Un inactivo sigue en la base, pero no sale en ningun listado ni cuenta en ninguna suma ni en el saldo. Al restaurar la factura vuelve a ser activo, con el mismo id. Invariante: `currentBalance` = `initialBalance` + movimientos activos |
| Balance global | Suma de los `currentBalance` del tenant | Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml` |
| `EFECTIVO` / `BANCO` / `DIGITAL` | Tipo de cuenta | Los del diccionario. Autollantas solo tenia `CASH` y `BANK`, que ya no existen. Las 2 cuentas por defecto son `Caja General` (EFECTIVO) y `Bancolombia` (BANCO) |

Los campos de fecha de negocio en Autollantas se nombran `fecha_<entidad>`
(`fecha_movimiento`, `fecha_transferencia`, `fecha_ingreso`, `fecha_gasto`) y en Java son
siempre `date`. Los de concepto se nombran `concepto_<entidad>` y en Java son siempre
`concept`. La convencion se mantiene en ServiBox.

### Terceros

| Termino | Significado | Cuidado |
|-|-|-|
| **Tercero** | Persona o empresa con la que el taller comercia (`TERCEROS`) | En el codigo se llama **`Counterparty`**. No hay entidades `Customer` ni `Supplier`: **cliente y proveedor son roles de un tercero** (`CounterpartyRole`), no tipos distintos |
| `CLIENTE` / `PROVEEDOR` / `AMBOS` | Rol del tercero (`tipo_tercero`) | Un tercero que se usa en el otro rol pasa a `AMBOS`; nunca se duplica por documento |
| `customer` / `supplier` | El tercero de una venta / de una compra | Son nombres del **campo** en `Sale` y `Purchase` (y en sus DTOs), no de una clase |
| `documentType` / `documentNumber` | Tipo y numero de documento (`tipo_documento`, `numero_documento`) | Los dos obligatorios. El numero es unico por tenant |
| **Consumidor final** | El cliente de una venta que no identifica a nadie | Un tercero CLIENTE con NIT `222222222222` (el de la DIAN), uno por tenant, creado la primera vez. **Toda factura tiene tercero**: una venta sin `customerId` usa este; una compra sin `supplierId` es 400, porque no hay proveedor por defecto |

### Sales

| Termino | Significado | Cuidado |
|-|-|-|
| **Abono** | Pago parcial de una factura a credito | **Se dice abono, NO pago y NO cobro.** Es explicito en la documentacion de Autollantas: ventana "Registrar Abono", boton "Confirmar Abono", campo "Valor Abonado". **No es una entidad**: un abono es un `Movement` `RECAUDO_VENTA` con `sale`. Ya no hay `Collection` ni tabla `RECAUDOS`; solo la ruta `/collections` y el DTO de entrada `CollectionRequest` conservan el nombre |
| `invoiceNumber` | Numero de factura (`numero_factura_venta`) | Unico por tenant, y aqui **si** con restriccion de base, a diferencia de Autollantas. En ventas es opcional en la API: si falta, el consecutivo `FV-0001`, `FV-0002`... del tenant. Ver [03-DECISIONS.md](03-DECISIONS.md) |
| `invoiceDate` | Fecha de la factura | En Autollantas el campo se llama `saleDate` (`fecha_venta`); aqui `invoiceDate`, que es como lo llama el formulario. La columna sigue siendo `fecha_venta` |
| `ivaAmount` (linea) | IVA generado por la linea | **Congelado al facturar.** No se recalcula nunca desde el producto: editar el IVA de un producto no puede mover el IVA de una factura ya emitida |
| `ivaPorPagar` | IVA generado menos IVA descontable | No es el IVA de la factura: es la diferencia contra el IVA que ya se pago al comprar esos productos (`producto.taxAmount`). En Autollantas se llama `ivaDifference` / "IVA por Pagar" |
| `subtotal` | Suma de precio por cantidad, **sin** IVA | En Autollantas no es un campo, se calcula en la UI (`calculateSubtotalSinIva`) |
| Saldo pendiente | `total` menos la suma de los `RECAUDO_VENTA` de la factura (contado y abonos), activos o no | **No es un campo** en ServiBox, se calcula. Autollantas lo guarda en `saldo_pendiente`. Una venta de contado tiene pendiente 0. En la API (`pendingBalance`) una factura `ANULADA` muestra 0, porque no se debe; el calculo interno sigue contando lo abonado, porque restaurar lo necesita |
| `CONTADO` / `CREDITO` | Forma de pago | En Autollantas son los literales `String` `"Contado"` y `"Credito"` (con tilde); aqui enum |
| `PAGADA` / `PENDIENTE` / `ANULADA` | Estado de la factura | Mismos nombres que Autollantas, pero enum en vez de `String` |

Una factura anulada **no se borra**: queda en `ANULADA` con el stock ya devuelto, los
saldos revertidos y **sus movimientos intactos pero inactivos**. En Autollantas se sigue
viendo desde la papelera; en ServiBox se restaura con `POST /api/sales/{id}/restore` (y
`/api/purchases/{id}/restore`), el inverso exacto de anular: vuelve al estado que le dan
sus datos (`PAGADA` o `PENDIENTE`) y sus mismos movimientos vuelven a ser activos.

### Purchases

| Termino | Significado | Cuidado |
|-|-|-|
| **Pago** | Pago parcial o total de una factura de compra a credito | **En compras se dice pago; en ventas se dice abono.** No son sinonimos intercambiables: el abono es lo que el taller **recibe** de un cliente, el pago es lo que el taller **entrega** a un proveedor. Autollantas es explicito: ventana "Registrar Pago", boton "Confirmar Pago", campo "Valor a Pagar". **No es una entidad**: un pago es un `Movement` `PAGO_COMPRA` con `purchase`. Ya no hay `Payment` ni tabla `PAGOS` |
| Proveedor | El tercero de una compra (`Purchase.supplier`) | Es un `Counterparty` con rol `PROVEEDOR` o `AMBOS`. Nombre y razon social son un solo campo, `name` (`nombre_razon_social`), como en el formulario de Autollantas |
| `price` (linea de compra) | Costo de compra unitario, sin IVA | En Autollantas se llama `unitPrice` (`precio_compra`) |
| `ivaTotal` | IVA de la factura de compra | Es IVA **descontable**: se recupera contra el IVA de las ventas. No confundir con el `ivaPorPagar` de una venta, que ya es la diferencia |
| Validacion de saldo | Una compra de **contado** exige `cuenta.currentBalance >= total` | **No aplica a credito**, que no saca dinero al facturar |

Una compra suma stock; una venta lo resta. Al anular se invierte cada una, y por eso anular
una compra puede fallar: si parte de lo comprado ya se vendio, quitar el stock dejaria al
producto en negativo.

### Reporting

| Termino | Significado | Cuidado |
|-|-|-|
| KPIs globales / del periodo | Los mismos 8 indicadores sobre todo el historico o sobre `[desde, hasta]` | Global en Autollantas significa **otra cosa** (por cobrar, por pagar, saldo, alertas). Aqui global es "sin rango". Ver [03-DECISIONS.md](03-DECISIONS.md) |
| `totalSales` / `totalPurchases` | Suma de `total` de las facturas, **con IVA** | Nunca incluyen facturas `ANULADA`. No confundir con `subtotal` |
| `netSales` | Suma de `subtotal` de las ventas, **sin IVA** | Sin `ANULADA` |
| `costOfSales` | Suma de `unitCost * quantity` de las lineas de esas ventas | `unitCost` es el costo del producto congelado al facturar: no cambia despues |
| `grossProfit` | `netSales - costOfSales` | Las compras **no** entran: es la utilidad de lo vendido |
| `operatingExpenses` | Suma de los gastos operativos | Solo movimientos `GASTO_OPERATIVO`; las compras van en `totalPurchases` |
| `netProfit` | `grossProfit + otherIncome - operatingExpenses` | Puede ser negativo. `otherIncome` son los ingresos ocasionales |
| IVA Generado | Suma de `SaleDetail.ivaAmount` | El IVA cobrado al vender, congelado al facturar |
| IVA Descontable | IVA ya pagado al comprar las unidades vendidas: `ivaAmount - ivaDifference` | Tambien llamado IVA a favor. **No es** el `ivaTotal` de las compras del periodo: es el de las unidades **vendidas** |
| IVA Neto a Pagar | Generado menos Descontable, `SaleDetail.ivaDifference` | Por linea es el mismo concepto que `Sale.ivaPorPagar` por factura |
| Movimiento (en Reporting) | Un documento de negocio resumido: `VENTA`, `COMPRA`, `GASTO`, `INGRESO` | **No es un `Movement` de tesoreria.** Una venta a credito es un movimiento del dashboard aunque no haya movido ninguna cuenta |

Regla de exclusion de `ANULADA`: ver Modulo Reporting en
[01-ARCHITECTURE.md](01-ARCHITECTURE.md).

### Unicidad en entidades multi-tenant

Toda restriccion de unicidad de una entidad de negocio es **compuesta con `id_tenant`**,
nunca `unique = true` en la columna sola. Un valor unico globalmente impediria que dos
clientes distintos usen el mismo nombre de usuario o el mismo codigo de producto, que es
justamente lo normal entre negocios que no se conocen.

| Entidad | Restriccion | Nombre |
|-|-|-|
| `User` | `(id_tenant, username)` | `uk_usuario_tenant_username` |
| `Product` | `(id_tenant, codigo_producto)` | `uk_producto_tenant_code` |
| `Account` | `(id_tenant, nombre_cuenta)` | `uk_cuenta_tenant_name` |
| `Counterparty` | `(id_tenant, numero_documento)` | `uk_tercero_tenant_document` |
| `Sale` | `(id_tenant, numero_factura_venta)` | `uk_venta_tenant_invoice_number` |
| `Purchase` | `(id_tenant, id_tercero, numero_factura_compra)` | `uk_compra_tenant_supplier_invoice_number` (V3) |

`TENANTS.slug_tenant` es la excepcion: unico global (`uk_tenant_slug`), porque el login lo
usa para saber a que tenant entrar. El registro lo guarda en minusculas y lo compara sin
distinguir mayusculas.

Ademas de la restriccion de base, el service valida antes de guardar y lanza una excepcion
legible; el error crudo de constraint violation no le sirve al usuario final. Al editar,
la validacion siempre excluye el propio registro.

### Busqueda por id en un service multi-tenant

**No usar el `findById` heredado de `JpaRepository`.** El `@Filter` de Hibernate no se
aplica a `EntityManager.find()`, asi que devuelve la fila aunque sea de otro tenant. Usar
una consulta derivada, por convencion `findByIdAndTenantId(id, TenantContext.getTenantId())`.
Como se detecto en [03-DECISIONS.md](03-DECISIONS.md). Aplicado ya en `Account`,
`Product`, `ProductCategory`, `Sale`, `Counterparty`, `Purchase` y
`Movement`; no queda ningun `findById` heredado sobre una
entidad `TenantAware`.

Y el codigo de respuesta: un recurso pedido **por la ruta** que no aparece para el tenant
activo es `ResourceNotFoundException` (**404**, el mismo para "no existe" y para "es de
otro tenant"). Un id que llega **dentro del cuerpo** y no resuelve es
`IllegalArgumentException` (**400**): ahi el problema si es la peticion.

### Bloqueos

**Toda operacion que lea y escriba el saldo de una cuenta (`currentBalance`) o el stock de
un producto (`quantity`) bloquea esas filas antes de validar.** Sin bloqueo, dos peticiones
simultaneas leen el mismo valor y la segunda pisa la escritura de la primera. Como se
detecto en [03-DECISIONS.md](03-DECISIONS.md), "Bloqueo pesimista de saldos y stock".

* Cuentas con `TreasuryService.bloquearCuentas(ids)` y productos con
  `InventoryService.bloquearProductos(ids)`: `findByIdAndTenantIdForUpdate` (`SELECT ... FOR
  UPDATE`, con el tenant en el where como `findByIdAndTenantId`) en **orden ascendente de
  id**, sin repetir, y despues `refresh`. El refresh no sobra: con open-in-view el
  controlador ya cargo la entidad, y la consulta con bloqueo devuelve esa misma instancia
  sin pisar su valor viejo. Los dos metodos exigen una transaccion abierta
  (`Propagation.MANDATORY`): fuera de ella el bloqueo se soltaria enseguida.
* La operacion que decide segun el estado o el saldo pendiente de un documento (abonar,
  pagar, anular, restaurar una factura; editar o borrar un movimiento manual; crear una
  compra de un proveedor) bloquea primero ese documento con su
  `findByIdAndTenantIdForUpdate` y lo refresca.
* **Orden fijo entre tipos**: primero el documento (una sola fila: la factura, el
  movimiento manual o el proveedor de la compra; una factura nueva cuenta como documento
  porque se inserta antes), despues las cuentas y por ultimo los productos, cada grupo por
  id ascendente. Dos operaciones que toman los bloqueos en el mismo orden no se
  interbloquean.
* Se bloquea antes de validar y solo lo que la operacion modifica. El bloqueo dura hasta el
  fin de la transaccion del service; las transacciones siguen siendo las del metodo publico.
* Espera maxima `spring.jpa.properties.jakarta.persistence.lock.timeout` (5000 ms). Un
  bloqueo no obtenido (espera agotada o interbloqueo detectado por el motor) es **409**
  "El recurso esta siendo modificado por otra operacion, intenta de nuevo".

### Calculos que no se copian

La tasa de IVA de un producto sale **solo** de
`InventoryService.getIvaRateForProduct(Product)`. En Autollantas esa funcion esta copiada
en **cinco** sitios, y la quinta (la de compras) **no calcula lo mismo que las otras
cuatro**: suma todas las rates de la categoria sin filtrar por `isVat`. Aqui vive una sola
vez y con una sola semantica, la de `isVat`, para Inventory, Sales y Purchases. Antes de
escribir `getTaxTypes().stream()...` en cualquier modulo nuevo, usar ese metodo. Detalle en
[03-DECISIONS.md](03-DECISIONS.md).

`minSalePrice` **no es terminologia vigente.** Existio en Autollantas y ya no; no
introducirlo en ServiBox.

## Datos de demostracion en dev

Con el perfil `dev` (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`),
`DevDataInitializer` hace la siembra base (tenant `demo` con NIT 900000000-0 y telefono
3000000000, inventados con el formato del registro; usuario `admin`, IVA, las 3
categorias del registro y 2 cuentas; credenciales en [01-ARCHITECTURE.md](01-ARCHITECTURE.md))
y despues los datos de demostracion: un almacen de llantas, lubricantes y baterias de Tunja
con tres meses de operacion (el actual y los dos anteriores), para probar el frontend y
Reportes sin cargar nada a mano. Todo pasa por los services.

* Categorias (IVA 19): LLANTAS amarillo 8, rojo 3, margen 0.22; LUBRICANTES 12, 4 y 0.30;
  BATERIAS 5, 2 y 0.25.
* 27 productos con referencias reales: `LL-001` a `LL-010` (rin 13 a 17: Michelin, Pirelli,
  Goodyear, Hankook, Bridgestone, Continental), `LU-001` a `LU-010` (20W-50, 15W-40, 10W-30
  y 5W-30: Terpel, Mobil, Castrol, Shell, Chevron) y `BA-001` a `BA-007` (MAC, Willard,
  Tudor, Bosch, con su grupo). Stock final: `BA-007` en 0, `LL-010` (2) y `BA-004` (1)
  criticos, `LL-007` (6) y `LU-010` (9) bajos, el resto normal. Valor del inventario a costo
  49285000.
* Cuentas con saldo inicial fijado en la cuenta (no como ingreso ocasional): Caja General
  2500000, Bancolombia 15000000 y Nequi (DIGITAL) 600000. Saldos finales 4406852,45,
  16963933,02 y 6807530,08 (los centavos vienen del IVA por linea).
* Terceros inventados: 8 clientes (3 personas, una empresa de taxis, dos flotas, dos
  talleres), 5 proveedores y el Consumidor final. NIT con digito de verificacion valido.
* 45 ventas (32 de contado con EFECTIVO en Caja y TRANSFERENCIA, TARJETA_DEBITO o
  TARJETA_CREDITO en Bancolombia o Nequi; 13 a credito a 30 dias, 6 pagadas con abonos y 7
  PENDIENTE, 3 de ellas con abono parcial; 2 ANULADA) al precio sugerido con un recargo del
  12 por ciento (`RECARGO_VENTA`), redondeado al peso entero. Las 5 del 20 al 24 del mes
  actual (fix/38) ponen ese mes en utilidad neta positiva. 9 compras con numero del proveedor (6 de contado, 3 a credito con pagos parciales,
  1 ANULADA por duplicada). 22 gastos operativos (arriendo, energia, acueducto, internet,
  nomina por quincena, aseo, publicidad), el ingreso ocasional "Reintegro de Distrillantas
  del Oriente por garantia" de 310000 en el mes anterior y 2 transferencias de Caja a
  Bancolombia ("Consignacion de efectivo").
* `/kpis` por mes (hace dos meses / mes anterior / mes actual): ventas con IVA 23990595,16 /
  27488878,62 / 24730469,33; compras 14518000 / 10595760 / 7097160; ventas sin IVA 20160164 /
  23099898 / 20781907; costo de lo vendido 13572000 / 15636000 / 14055000; utilidad bruta
  6588164 / 7463898 / 6726907 (margen 32,7 / 32,3 / 32,4 por ciento); gastos 7294000 /
  7221800 / 5192200; otros ingresos 0 / 310000 / 0; utilidad neta -705836 / 552098 /
  1534707 (7,4 por ciento de las ventas sin IVA del mes actual). Cada venta congela el costo
  vigente de su momento: las operaciones van en orden de fecha.

Las fechas salen del reloj de negocio (`servibox.business.time-zone`): un dia que el mes no
tiene pasa al ultimo y una fecha posterior a hoy pasa a hoy, asi que las cifras por mes son
las mismas cualquier dia que se arranque (vencimientos de credito pueden quedar despues de
hoy). Se desactiva con `servibox.dev.demo-data=false` (variable `DEV_DEMO_DATA`, en
`application-dev.properties`); la siembra base sigue. La base H2 es en memoria y se reinicia
en cada arranque, asi que cada arranque vuelve a sembrar lo mismo. Si el tenant `demo` ya
tiene productos no se siembra nada, y si un paso falla queda un aviso en el log y la app
arranca igual. `config.DevDemoDataTest` (Clock fijo en 2026-10-24) y
`config.DevDemoDataFirstDayTest` (Clock en un primer dia de mes) comprueban estas cifras.
