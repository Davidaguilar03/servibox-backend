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

### Unicidad en entidades multi-tenant

Toda restriccion de unicidad de una entidad de negocio es **compuesta con `tenant_id`**,
nunca `unique = true` en la columna sola. Un valor unico globalmente impediria que dos
clientes distintos usen el mismo nombre de usuario o el mismo codigo de producto, que es
justamente lo normal entre negocios que no se conocen.

| Entidad | Restriccion | Nombre |
|-|-|-|
| `User` | `(tenant_id, username)` | `uk_usuario_tenant_username` |
| `Product` | `(tenant_id, codigo_producto)` | `uk_producto_tenant_code` |

Ademas de la restriccion de base, el service valida antes de guardar y lanza una excepcion
legible; el error crudo de constraint violation no le sirve al usuario final. Al editar,
la validacion siempre excluye el propio registro.

`minSalePrice` **no es terminologia vigente.** Existio en Autollantas y ya no; no
introducirlo en ServiBox.
