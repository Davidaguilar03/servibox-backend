# Convenciones

## Convenciones de nombres

## Estilo de codigo

## Manejo de errores

## Formato de respuestas API (DTOs)

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
| `suggestedPrice` | Precio de venta sugerido | El margen es sobre el **precio de venta**, no sobre el costo |
| `targetMargin` | Margen objetivo de la categoria | Fraccion, no porcentaje: `0.30` es 30 por ciento |
| `isVat` | Marca que un `TaxType` es IVA recuperable | Distingue el IVA de retenciones como ReteICA |
| `yellowStockMin` / `redStockMin` | Umbrales de alerta de stock | Amarillo es advertencia, rojo es critico |

### Treasury

| Termino | Significado | Cuidado |
|-|-|-|
| `initialBalance` | Saldo de apertura de la cuenta (`balance_inicial`) | No se mueve despues de creada la cuenta |
| `currentBalance` | Saldo vivo (`saldo_actual`) | Es el que mueven movimientos y transferencias, y el que suma el balance global |
| `concept` | El "concepto" del negocio, texto libre que explica el movimiento | Es el nombre real del campo en Autollantas: `concepto_transferencia`, `concepto_ingreso`, `concepto_gasto`. **`MOVIMIENTOS` en Autollantas no tiene columna de concepto**, ver [03-DECISIONS.md](03-DECISIONS.md) |
| `date` | Fecha **de negocio**, `LocalDate` | No confundir con `createdAt` / `updatedAt`, que son `LocalDateTime` y son auditoria. Autollantas usa `LocalDate` en `fecha_movimiento` y `fecha_transferencia`; se conserva |
| `INGRESO` / `EGRESO` | Entrada y salida de dinero de una cuenta | En Autollantas son los literales `String` `"Ingreso"` y `"Egreso"`; aqui son un enum, ver [03-DECISIONS.md](03-DECISIONS.md) |
| `originAccount` / `destinationAccount` | Los dos extremos de una transferencia | Autollantas los llama `sourceAccount` / `destinationAccount`. Se renombro el origen para que coincida con la vista (columnas Origin / Destination) |
| **Ingreso ocasional** | Ingreso puntual que no viene de una venta: reintegros, venta de chatarra, aporte del socio | Es una entidad propia (`OccasionalIncome`, tabla `INGRESOS_OCASIONALES`), no un movimiento suelto. En la interfaz de Autollantas vive bajo Ingresos > Ingresos Ocasionales |
| Eliminar un ingreso ocasional | Deshacer el ingreso: se borra su movimiento, se resta el saldo y **desaparece la fila** | En Autollantas la accion se llama "Eliminar", no "Anular". **No deja estado `ANULADA` como una factura de venta**: un ingreso ocasional no es un documento fiscal, no hay nada que conservar |
| **Gasto operativo** | Gasto puntual del taller que no viene de una factura de compra: arriendo, servicios publicos, papeleria | Es una entidad propia (`OperationalExpense`, tabla `GASTOS_OPERATIVOS`), imagen espejo del ingreso ocasional. En la interfaz de Autollantas aparece bajo **Egresos**, junto a las facturas de compra, pero el codigo vive en `treasury`, no en `purchases` |
| Editar un gasto operativo | **Revertir y recrear**: se revierte y borra el movimiento anterior, se actualizan los campos de la misma fila y se registra un `EGRESO` nuevo | Es el **unico** registro de tesoreria con edicion. No es un ajuste por la diferencia ni un contra-movimiento: al terminar hay **un solo** movimiento. La reversion devuelve el dinero a la cuenta **del movimiento viejo**, no a la del gasto ya editado |
| Eliminar un gasto operativo | Deshacer el gasto: se borra su movimiento, se devuelve el saldo y **desaparece la fila** | Mismo criterio y mismo nombre enganoso que el ingreso ocasional: el metodo se llama `anularGastoOperativo` por consistencia con el resto de la API, pero **elimina**, no marca `ANULADA` |
| `notes` | Observaciones libres de un gasto operativo (columna `notes`) | **Opcional**: el formulario de Autollantas no lo valida. Es el unico registro de tesoreria migrado que lo trae; `OccasionalIncome` tiene el campo en Autollantas pero aqui no se porto |
| `sourceType` + `sourceId` | El origen automatico de un movimiento | Van **siempre juntos**: o los dos poblados o los dos null (movimiento suelto). `sourceType` es `MovementSourceType`; `sourceId` se interpreta segun el tipo y **no es clave foranea**. Nunca construir un `Movement` a mano con un id suelto: usar los metodos de `TreasuryService`, que reciben la entidad tipada. Ver [03-DECISIONS.md](03-DECISIONS.md) |
| Balance global | Suma de los `currentBalance` del tenant | Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml` |
| `CASH` / `BANK` | Tipo de cuenta | Las 2 cuentas por defecto de Autollantas son `Caja General` (CASH) y `Bancolombia` (BANK) |

Los campos de fecha de negocio en Autollantas se nombran `fecha_<entidad>`
(`fecha_movimiento`, `fecha_transferencia`, `fecha_ingreso`, `fecha_gasto`) y en Java son
siempre `date`. Los de concepto se nombran `concepto_<entidad>` y en Java son siempre
`concept`. La convencion se mantiene en ServiBox.

### Sales

| Termino | Significado | Cuidado |
|-|-|-|
| **Abono** | Pago parcial de una factura a credito | **Se dice abono, NO pago y NO cobro.** Es explicito en la documentacion de Autollantas: ventana "Registrar Abono", boton "Confirmar Abono", campo "Valor Abonado". En codigo la clase se llama `Collection` (tabla `RECAUDOS`), heredado de Autollantas; el termino de negocio de cara al usuario es abono |
| `invoiceNumber` | Numero de factura (`numero_factura_venta`) | Unico por tenant, y aqui **si** con restriccion de base, a diferencia de Autollantas. Ver [03-DECISIONS.md](03-DECISIONS.md) |
| `invoiceDate` | Fecha de la factura | En Autollantas el campo se llama `saleDate` (`fecha_venta`); aqui `invoiceDate`, que es como lo llama el formulario. La columna sigue siendo `fecha_venta` |
| `ivaAmount` (linea) | IVA generado por la linea | **Congelado al facturar.** No se recalcula nunca desde el producto: editar el IVA de un producto no puede mover el IVA de una factura ya emitida |
| `ivaPorPagar` | IVA generado menos IVA descontable | No es el IVA de la factura: es la diferencia contra el IVA que ya se pago al comprar esos productos (`producto.taxAmount`). En Autollantas se llama `ivaDifference` / "IVA por Pagar" |
| `subtotal` | Suma de precio por cantidad, **sin** IVA | En Autollantas no es un campo, se calcula en la UI (`calculateSubtotalSinIva`) |
| Saldo pendiente | `total` menos la suma de abonos | **No es un campo** en ServiBox, se calcula. Autollantas lo guarda en `saldo_pendiente` |
| `CONTADO` / `CREDITO` | Forma de pago | En Autollantas son los literales `String` `"Contado"` y `"Credito"` (con tilde); aqui enum |
| `PAGADA` / `PENDIENTE` / `ANULADA` | Estado de la factura | Mismos nombres que Autollantas, pero enum en vez de `String` |

Una factura anulada **no se borra**: queda en `ANULADA` con el stock ya devuelto. En
Autollantas se sigue viendo desde la papelera y se puede restaurar; ServiBox todavia no
tiene la restauracion.

### Purchases

| Termino | Significado | Cuidado |
|-|-|-|
| **Pago** | Pago parcial o total de una factura de compra a credito | **En compras se dice pago; en ventas se dice abono.** No son sinonimos intercambiables: el abono es lo que el taller **recibe** de un cliente, el pago es lo que el taller **entrega** a un proveedor. Autollantas es explicito: ventana "Registrar Pago", boton "Confirmar Pago", campo "Valor a Pagar". La clase es `Payment` (tabla `PAGOS`) |
| `Supplier` | Proveedor (`PROVEEDORES`) | Su `document` es el **NIT** (`numero_nit_proveedor`) |
| `businessName` | Razon social | En Autollantas **no existe como campo aparte**: el formulario tiene un unico campo "Nombre/razon social" que va a `nombre_proveedor`. Ver [03-DECISIONS.md](03-DECISIONS.md) |
| `price` (linea de compra) | Costo de compra unitario, sin IVA | En Autollantas se llama `unitPrice` (`precio_compra`) |
| `ivaTotal` | IVA de la factura de compra | Es IVA **descontable**: se recupera contra el IVA de las ventas. No confundir con el `ivaPorPagar` de una venta, que ya es la diferencia |
| Validacion de saldo | Una compra de **contado** exige `cuenta.currentBalance >= total` | **No aplica a credito**, que no saca dinero al facturar |

Una compra suma stock; una venta lo resta. Al anular se invierte cada una, y por eso anular
una compra puede fallar: si parte de lo comprado ya se vendio, quitar el stock dejaria al
producto en negativo.

### Unicidad en entidades multi-tenant

Toda restriccion de unicidad de una entidad de negocio es **compuesta con `tenant_id`**,
nunca `unique = true` en la columna sola. Un valor unico globalmente impediria que dos
clientes distintos usen el mismo nombre de usuario o el mismo codigo de producto, que es
justamente lo normal entre negocios que no se conocen.

| Entidad | Restriccion | Nombre |
|-|-|-|
| `User` | `(tenant_id, username)` | `uk_usuario_tenant_username` |
| `Product` | `(tenant_id, codigo_producto)` | `uk_producto_tenant_code` |
| `Account` | `(tenant_id, nombre_cuenta)` | `uk_cuenta_tenant_name` |
| `Customer` | `(tenant_id, numero_documento_cliente)` | `uk_cliente_tenant_document` |
| `Sale` | `(tenant_id, numero_factura_venta)` | `uk_venta_tenant_invoice_number` |
| `Supplier` | `(tenant_id, numero_nit_proveedor)` | `uk_proveedor_tenant_document` |
| `Purchase` | `(tenant_id, numero_factura_compra)` | `uk_compra_tenant_invoice_number` |

Ademas de la restriccion de base, el service valida antes de guardar y lanza una excepcion
legible; el error crudo de constraint violation no le sirve al usuario final. Al editar,
la validacion siempre excluye el propio registro.

### Busqueda por id en un service multi-tenant

**No usar el `findById` heredado de `JpaRepository`.** El `@Filter` de Hibernate no se
aplica a `EntityManager.find()`, asi que devuelve la fila aunque sea de otro tenant. Usar
una consulta derivada, por convencion `findByIdAndTenantId(id, TenantContext.getTenantId())`.
Detalle y como se detecto en [01-ARCHITECTURE.md](01-ARCHITECTURE.md), seccion Modulo
Treasury. Aplicado ya en `Account`, `Product`, `ProductCategory`, `Sale`, `Customer`,
`Purchase`, `Supplier` y `OccasionalIncome`; no queda ningun `findById` heredado sobre una
entidad `TenantAware`.

Y el codigo de respuesta: un recurso pedido **por la ruta** que no aparece para el tenant
activo es `ResourceNotFoundException` (**404**, el mismo para "no existe" y para "es de
otro tenant"). Un id que llega **dentro del cuerpo** y no resuelve es
`IllegalArgumentException` (**400**): ahi el problema si es la peticion.

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
