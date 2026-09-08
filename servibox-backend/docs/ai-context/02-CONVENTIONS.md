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
| Balance global | Suma de los `currentBalance` del tenant | Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml` |
| `CASH` / `BANK` | Tipo de cuenta | Las 2 cuentas por defecto de Autollantas son `Caja General` (CASH) y `Bancolombia` (BANK) |

Los campos de fecha de negocio en Autollantas se nombran `fecha_<entidad>`
(`fecha_movimiento`, `fecha_transferencia`, `fecha_ingreso`, `fecha_gasto`) y en Java son
siempre `date`. Los de concepto se nombran `concepto_<entidad>` y en Java son siempre
`concept`. La convencion se mantiene en ServiBox.

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

Ademas de la restriccion de base, el service valida antes de guardar y lanza una excepcion
legible; el error crudo de constraint violation no le sirve al usuario final. Al editar,
la validacion siempre excluye el propio registro.

### Busqueda por id en un service multi-tenant

**No usar el `findById` heredado de `JpaRepository`.** El `@Filter` de Hibernate no se
aplica a `EntityManager.find()`, asi que devuelve la fila aunque sea de otro tenant. Usar
una consulta derivada, por convencion `findByIdAndTenantId(id, TenantContext.getTenantId())`.
Detalle y como se detecto en [01-ARCHITECTURE.md](01-ARCHITECTURE.md), seccion Modulo
Treasury.

`minSalePrice` **no es terminologia vigente.** Existio en Autollantas y ya no; no
introducirlo en ServiBox.
