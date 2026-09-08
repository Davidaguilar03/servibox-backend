# Log de decisiones tecnicas

Una entrada por decision, en orden cronologico inverso (la mas reciente arriba).

Formato de cada entrada:

```
## AAAA-MM-DD: Titulo corto de la decision

* Decision: que se decidio hacer.
* Alternativas consideradas: opciones que se evaluaron y se descartaron.
* Motivo: por que se eligio esta opcion sobre las demas.
```

## 2026-09-07: Nombre de cuenta unico por tenant

* Decision: `Account` lleva la restriccion compuesta `uk_cuenta_tenant_name` sobre
  `(tenant_id, nombre_cuenta)`, y `TreasuryService.saveAccount` valida con
  `findByNameAndTenantId` antes del INSERT lanzando `DuplicateAccountNameException`, que
  el `GlobalExceptionHandler` traduce a 409 con un mensaje legible.
* Alternativas consideradas: no poner restriccion ninguna, que es lo que hace Autollantas
  (su `CUENTAS` no tiene unique sobre el nombre); o `unique = true` sobre la columna sola;
  o dejar solo la restriccion de base sin validacion en el service.
* Motivo: en Autollantas las cuentas son dos, fijas, creadas por el inicializador, y el
  unico usuario nunca crea una tercera; el problema no existia. En ServiBox el endpoint
  `POST /api/treasury/accounts` deja crear cuentas libremente, y dos "Bancolombia" en el
  mismo taller hacen que cualquier movimiento vaya a la cuenta equivocada sin que nadie lo
  note, con el agravante de que el error se descubre cuadrando plata. `unique` sobre la
  columna sola esta descartado por la misma razon que en `User` y `Product`: cada tenant
  tiene su propia "Caja General". La validacion en el service ademas de la restriccion de
  base es el mismo patron de `DuplicateProductCodeException`: la base es la red de
  seguridad, el service es el que produce un mensaje que el usuario entiende.

## 2026-09-07: El `@Filter` de Hibernate no cubre `findById`, y eso era un cruce de tenants

* Decision: `TreasuryService.findAccountById` no usa el `findById` heredado de
  `JpaRepository`, sino la consulta derivada `findByIdAndTenantId`. Queda como convencion
  para toda busqueda por id de una entidad de negocio, ver
  [02-CONVENTIONS.md](02-CONVENTIONS.md).
* Alternativas consideradas: dejar `findById` y filtrar despues en el service comparando
  `getTenantId()` contra `TenantContext`; o envolver el `EntityManager`.
* Motivo: no es una preferencia de estilo, es un agujero real. El `@Filter` se aplica a
  las consultas (HQL, criteria, consultas derivadas de Spring Data) pero **no** a
  `EntityManager.find()`, que es lo que usa `findById`. Lo detecto el test de aislamiento
  del modulo: `GET /api/treasury/accounts/{id}/movements` con el token del tenant
  equivocado respondia 200 en vez de rechazar la cuenta ajena. Devolvia lista vacia solo
  porque la consulta de movimientos si es derivada y si estaba filtrada; la cuenta en si
  se resolvio cruzando el tenant. Filtrar a mano despues del `findById` funciona pero
  depende de que nadie se olvide en la entidad numero veinte; la consulta derivada lo hace
  imposible de olvidar porque el tenant esta en la firma del metodo. Envolver el
  `EntityManager` ya se descarto antes por romper la gestion de transacciones, ver los
  callejones sin salida en [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
* Pendiente conocido: `InventoryService.findProductById` tiene exactamente el mismo
  patron (`productRepository.findById`) y por lo tanto el mismo cruce en
  `GET /api/inventory/products/{id}`. No se toco aqui por quedar fuera del alcance de esta
  migracion, pero hay que corregirlo con su propio test.

## 2026-09-07: `Movement` con `concept` propio y `type` como enum

* Decision: `Movement` lleva una columna `concepto_movimiento` y un enum `MovementType`
  (`INGRESO`, `EGRESO`). Autollantas no tiene ninguna de las dos cosas: su
  `MOVIMIENTOS.tipo_movimiento` es un `String` con los literales `"Ingreso"` y `"Egreso"`,
  y la tabla no tiene columna de concepto.
* Alternativas consideradas: portar el esquema tal cual, con `type` como `String` y la
  descripcion derivada de `(tabla_origen_movimiento, id_origen_movimiento)` como hace
  `TreasuryService.resolveDescription`.
* Motivo: el par `(tabla_origen, id_origen)` es una referencia polimorfica a las tablas
  `VENTAS`, `COMPRAS`, `RECAUDOS`, `PAGOS`, `GASTOS_OPERATIVOS`, `INGRESOS_OCASIONALES` y
  `TRANSFERENCIAS`. En ServiBox esos modulos todavia no existen: portarla ahora seria
  copiar una clave foranea sin integridad referencial que apunta a tablas ausentes, y un
  `switch` sobre nombres de tabla en `String`. Una columna `concept` da la misma
  informacion al usuario, es lo que el `POST /api/treasury/movements` necesita hoy, y no
  bloquea agregar el origen despues cuando existan Sales y Purchases. El enum en `type`
  sigue el mismo criterio que `Role` en `User`: `"Ingreso"` como `String` libre admite
  `"ingreso"`, `"INGRESO"` y typos silenciosos en una columna de la que depende el signo
  con el que se mueve la plata.

## 2026-09-07: La transferencia no genera movimientos automaticos

* Decision: `registrarTransferencia` guarda el `Transfer` y mueve los dos saldos, y nada
  mas. Autollantas ademas crea dos `Movement`, un `"Egreso"` en el origen y un
  `"Ingreso"` en el destino, marcados con `sourceTable = "TRANSFERENCIAS"`.
* Alternativas consideradas: portar tambien la generacion de los dos movimientos.
* Motivo: se implemento el alcance pedido para esta migracion, que define la transferencia
  como Transfer mas los dos saldos. **Es una diferencia funcional real, no cosmetica:**
  hoy `GET /api/treasury/accounts/{id}/movements` no muestra las transferencias, asi que
  el extracto de una cuenta no explica todos sus cambios de saldo, mientras que en
  Autollantas si. El dato no se pierde (queda en `TRANSFERENCIAS`, y
  `findTransfersByAccountId` lo consulta), pero el ledger unificado que Autollantas arma
  con `UnifiedMovementRow` todavia no existe aqui. Cuando se necesite, la salida es
  generar los dos movimientos dentro de la misma transaccion, igual que Autollantas.

## 2026-09-07: Notion estaba al dia para Accounts, al reves que con Inventory

* Decision: se migro segun el codigo de Autollantas, y se confirmo que la pagina de Notion
  "Module - Accounts" coincide con el.
* Motivo: se dejo escrito porque con Inventory paso lo contrario y conviene no asumir la
  misma regla las dos veces. El paquete `treasury` de Autollantas no se toca desde el
  commit `8b29f7e` del 2026-08-04 (`fix/61-complete-module-reports-test`), y la pagina de
  Notion se edito el 2026-08-12, o sea que **la documentacion es mas nueva que el codigo**,
  al reves que en Inventory. Se contrastaron igual las dos fuentes campo por campo y no hay
  conflicto: Notion describe correctamente `Account` (los cinco campos y sus columnas), las
  dos cuentas semilla con saldo 0, el `type` de `MOVIMIENTOS` como `"Ingreso"` / `"Egreso"`
  y el `lblTotalGlobal` como suma de saldos. El unico punto que se presta a confusion es
  que Notion describe una columna "Concept" en la tabla de movimientos: esa columna de la
  vista **no** es un campo de `MOVIMIENTOS`, es el resultado de
  `TreasuryService.resolveDescription` sobre `(tabla_origen, id_origen)`. Verificado contra
  el codigo, ver la decision sobre `Movement` en este mismo archivo.

## 2026-08-28: taxAmount solo suma los impuestos marcados como IVA

* Decision: en `InventoryService.recalculatePrices`, `taxAmount` es
  `purchaseCost * suma(rates de la categoria con isVat true)`. Si ningun impuesto de la
  categoria tiene `isVat`, `taxAmount` es 0. El resto del metodo, incluido todo el calculo
  de `suggestedPrice`, se porto sin tocar.
* Alternativas consideradas: copiar Autollantas tal cual, donde `taxAmount` suma **todas**
  las rates de la categoria sin mirar `isVat`.
* Motivo: `taxAmount` representa el IVA del producto, que es un impuesto **recuperable**:
  se descuenta contra el IVA cobrado en ventas. Las retenciones tipo ReteICA no son
  recuperables, son un costo. Sumarlas dentro de `taxAmount` mezcla dos cosas que
  contablemente van a cuentas distintas, e infla el IVA declarable. En una categoria con
  IVA 19 por ciento mas ReteICA 3 por ciento sobre un costo de 25000, Autollantas reporta
  5500 de IVA cuando el IVA real es 4750. El campo `isVat` ya existe en `TaxType`
  justamente para hacer esa distincion, solo que el calculo no lo estaba usando.
* Nota sobre el estado real del codigo: la documentacion previa describia este calculo
  como un `0.19` hardcodeado dentro de un metodo `recalculateMinSalePrice`. Eso ya no es
  cierto. Autollantas hoy suma las rates de la categoria (el hardcode desaparecio), el
  metodo se llama `recalculatePrices`, y `minSalePrice` no existe. La divergencia que
  introduce ServiBox es unicamente el filtro por `isVat`.

## 2026-08-28: Verificar el estado del usuario en cada request

* Decision: `JwtAuthenticationFilter` consulta `USUARIOS` en cada request autenticado y
  solo autentica si el usuario todavia existe y esta activo. La authority tambien sale del
  rol en base, no del claim del token.
* Alternativas consideradas: confiar solo en la firma y la expiracion del token, que es lo
  que hace un JWT puro sin estado; o bajar la expiracion a minutos con refresh tokens; o
  mantener una lista de tokens revocados.
* Motivo: revocar un acceso tiene que ser inmediato. Con solo la expiracion, despedir a un
  empleado o detectar una cuenta comprometida deja la puerta abierta hasta 24 horas, y en
  un sistema multi-tenant hosteado ese es exactamente el momento en que el acceso importa.
  Bajar la expiracion con refresh tokens reduce la ventana pero no la cierra, y agrega
  toda la maquinaria de refresh. Una lista de revocados es estado igual, con la desventaja
  de que hay que acordarse de poblarla. Consultar el usuario es estado que ya existe y no
  puede desincronizarse: si la fila dice inactivo, el acceso se corta en el siguiente
  request. El costo es una consulta por request protegido, ver la nota de costo en
  [01-ARCHITECTURE.md](01-ARCHITECTURE.md).

## 2026-08-28: El tenant viaja en el JWT, no en una cabecera

* Decision: `TenantContext` se puebla desde el claim `tenantId` de un JWT firmado, en
  `JwtAuthenticationFilter`. Se elimino la cabecera `X-Tenant-Id` y la clase
  `TenantFilter` que la leia.
* Alternativas consideradas: mantener la cabecera junto al JWT, por comodidad al probar
  con curl o Postman; o resolver el tenant por subdominio.
* Motivo: la cabecera era andamiaje explicito mientras no habia login, y como valor en
  claro que manda el cliente, cualquiera con un token valido podia cambiar el numero y
  leer datos de otro tenant. El claim va dentro de la firma: alterarlo invalida el token.
  Mantener las dos vias en paralelo habria dejado justamente el agujero que el JWT viene a
  cerrar, porque bastaria con usar la mas debil. Subdominio queda para mas adelante, si
  hace falta, pero no cambia de donde sale la autoridad del dato.

## 2026-08-28: Rol como enum en la entidad, no como tabla Role

* Decision: `User.role` es un enum `Role` (`ADMIN`, `EMPLEADO`) persistido con
  `@Enumerated(STRING)`, no una tabla de roles con su relacion.
* Alternativas consideradas: tabla `ROLES` con relacion muchos a muchos y permisos por
  rol, el esquema clasico de Spring Security.
* Motivo: hoy son dos roles y no hay ningun caso de negocio que pida un tercero ni
  permisos por rol configurables por cliente. Una tabla agregaria dos joins, una pantalla
  de administracion y datos semilla por tenant, todo para representar dos valores fijos
  que ademas viven en el codigo de las autoridades de Spring. Si aparece la necesidad real
  de roles por tenant, migrar de enum a tabla es una migracion acotada; empezar por la
  tabla es complejidad que se paga desde el primer dia. `STRING` y no `ORDINAL` para que
  reordenar el enum no corrompa las filas existentes.

## 2026-08-28: Auditoria e identificadores propios, divergiendo de Autollantas

* Decision: `TenantAwareEntity` lleva `createdAt` y `updatedAt` (`@CreatedDate` /
  `@LastModifiedDate` con `AuditingEntityListener`, activados por `@EnableJpaAuditing`), y
  todos los identificadores del modelo son `Long`. Autollantas no tiene ningun campo de
  auditoria y usa `Integer` en sus entidades.
* Alternativas consideradas: copiar tal cual la convencion de Autollantas (`Integer`, sin
  auditoria) para que ambos proyectos se lean igual; o agregar las fechas a mano en cada
  entidad con `@PrePersist` y `@PreUpdate`.
* Motivo: Autollantas es una aplicacion de escritorio de un solo negocio, donde un dato
  raro se le pregunta al unico usuario que estuvo en la maquina. ServiBox es multi-tenant
  y hosteado: varios clientes sobre la misma base, sin acceso fisico al equipo. Ante un
  reclamo de "esto se cambio solo" o una fila con el `tenant_id` equivocado, `createdAt` y
  `updatedAt` son la unica forma de reconstruir que paso y cuando. Que sea automatico por
  listener y no manual evita el olvido en la entidad numero veinte. `Long` porque el
  volumen agregado de todos los tenants ya no es el de un solo negocio, y porque unifica
  con `Tenant.id`, que es `Long` por ser la referencia de `tenant_id`.

## 2026-08-28: Aislamiento multi-tenant por columna discriminadora

* Decision: un solo esquema con columna `tenant_id` en cada tabla de negocio, y filtro de
  Hibernate (`@FilterDef` / `@Filter` en `TenantAwareEntity`) habilitado por sesion a
  partir de `TenantContext`. Implementacion en
  [01-ARCHITECTURE.md](01-ARCHITECTURE.md).
* Alternativas consideradas: schema-per-tenant, es decir un esquema de base de datos
  independiente por cada inquilino, con enrutamiento del `DataSource` por request.
* Motivo: simplicidad operativa. Con schema-per-tenant cada migracion hay que correrla N
  veces, dar de alta un cliente implica crear y versionar un esquema nuevo, y los backups
  y el monitoreo se multiplican. La columna discriminadora deja una sola migracion, un
  solo pool de conexiones y un alta de tenant que es un INSERT. Ya venia definido asi
  desde la fase de formulacion academica del proyecto.
