# Arquitectura

## Stack tecnologico

* Java 21, Maven, Lombok.
* Spring Boot 4.1.1: `webmvc`, `data-jpa`, `security`, `validation`.
* Hibernate ORM 7.4.5 (el que trae Spring Boot).
* JWT con `jjwt` 0.12.6 (`api`, `impl`, `jackson`).
* H2 para local y tests; driver de PostgreSQL ya declarado para produccion, ver Base de
  datos al final de este archivo.

## Integracion Spring Boot

Se usa la autoconfiguracion de Spring Boot salvo en tres puntos, cada uno descrito en su
seccion: el `PlatformTransactionManager` se reemplaza por
`TenantAwareJpaTransactionManager` (Estrategia multi-tenant), la cadena de seguridad la
define `SecurityConfig` (Capa de seguridad JWT) y la auditoria JPA se activa con
`JpaAuditingConfiguration`.

## Estrategia multi-tenant (tenant_id + filtro Hibernate)

Un solo esquema de base de datos para todos los tenants. Cada fila de negocio lleva una
columna discriminadora `tenant_id`. El aislamiento no se escribe en cada query: lo aplica
Hibernate. Motivo de la eleccion en [03-DECISIONS.md](03-DECISIONS.md).

Piezas:

**`tenant.Tenant` y `tenant.TenantRepository`**
Entidad maestra del inquilino (tabla `TENANTS`): `id` (Long, IDENTITY), `name`, `slug`
unico, `active`, `createdAt` (asignado en `@PrePersist`). Esta entidad NO extiende
`TenantAwareEntity`: es el catalogo global de tenants, no pertenece a ninguno.

**`shared.TenantAwareEntity`**
`@MappedSuperclass` del que extienden todas las entidades de negocio. Aporta:

* `id`: `Long` con `@GeneratedValue(IDENTITY)`.
* `tenantId`: `Long`, columna `tenant_id`, `nullable = false`, `updatable = false`, del
  mismo tipo que `Tenant.id`.
* `createdAt` y `updatedAt`: `LocalDateTime` con `@CreatedDate` y `@LastModifiedDate`,
  poblados por `@EntityListeners(AuditingEntityListener.class)`. La auditoria se activa
  globalmente con `@EnableJpaAuditing` en `config.JpaAuditingConfiguration`.

Un metodo `@PrePersist` en la clase base asigna `tenantId` automaticamente desde
`TenantContext` antes de cada insercion. **Ninguna entidad de negocio debe fijar
`tenantId` a mano**: si el codigo lo asigna, el valor se sobreescribe con el del contexto.

Si en ese momento `TenantContext.getTenantId()` es null, el metodo lanza
`IllegalStateException` con el mensaje "No se puede persistir una entidad sin un tenant
activo en el contexto". Es deliberado que reviente ahi: una fila con `tenant_id` nulo
queda invisible para el filtro de Hibernate de todos los tenants y aparece como dato
perdido mucho despues, cuando ya no hay forma de saber a quien pertenecia. Mejor que la
insercion falle de una vez y visible.

Esto cierra el ciclo con el filtro de lectura: el `@Filter` garantiza que nadie lea filas
de otro tenant, y el `@PrePersist` garantiza que nadie escriba una fila sin tenant.
Verificado con test de integracion sobre una entidad de prueba descartable, en los dos
casos: con contexto activo el `tenantId` queda asignado sin que el codigo lo toque, y sin
contexto el `save` lanza la excepcion. La entidad de prueba y su test se borraron despues
de confirmarlo.

Todos los identificadores del modelo son `Long`. Autollantas usa `Integer` en sus
entidades y no tiene auditoria; ambas divergencias son deliberadas, ver
[03-DECISIONS.md](03-DECISIONS.md).

Declara ademas el filtro de Hibernate:

```java
@FilterDef(name = "tenantFilter", parameters = @ParamDef(name = "tenantId", type = Long.class))
@Filter(name = "tenantFilter", condition = "tenant_id = :tenantId")
```

En Hibernate 7 el atributo `type` de `@ParamDef` es `Class<?>`, no `String`.
Los nombres viven en las constantes `TenantAwareEntity.TENANT_FILTER` y
`TenantAwareEntity.TENANT_PARAM`; usarlas en vez de literales.

**Propagacion del `@Filter`: verificada, propaga solo.**
Se comprobo con una entidad de prueba descartable (`TenantProbe`) que extendia
`TenantAwareEntity` sin declarar ningun `@Filter` propio, mas un test de integracion con
dos filas de tenants distintos. Con Hibernate 7.4.5 el filtro declarado en el
`@MappedSuperclass` se aplica a la entidad hija: cada tenant vio unicamente su fila.
La entidad de prueba y su test se borraron despues de confirmarlo.

Conclusion operativa: **no hay que repetir `@Filter` en cada entidad concreta.** Basta con
extender `TenantAwareEntity`. Si alguna vez se sube de version mayor de Hibernate, vale la
pena rehacer esta verificacion antes de confiar en el comportamiento.

**`tenant.TenantContext`**
Holder con `ThreadLocal<Long>` y metodos estaticos `setTenantId` / `getTenantId` /
`clear`. Constructor privado, clase final. Es el unico punto que responde "que tenant
esta atendiendo este hilo".

**Origen del tenant: el JWT.**
`TenantContext` lo puebla `auth.JwtAuthenticationFilter` a partir del claim `tenantId` del
token. La cabecera `X-Tenant-Id` y la clase `TenantFilter` que la leia fueron eliminadas,
ver la seccion Capa de seguridad JWT de este mismo archivo y [03-DECISIONS.md](03-DECISIONS.md).

**`tenant.TenantAwareJpaTransactionManager`**
Extiende `JpaTransactionManager` y sobreescribe `doBegin`: apenas arranca la transaccion,
toma el tenant de `TenantContext` y habilita el filtro sobre la sesion ligada al hilo.

```java
session.enableFilter(TENANT_FILTER).setParameter(TENANT_PARAM, tenantId);
```

Desde ahi ninguna query necesita escribir el `where tenant_id` a mano. Se registra como
bean `PlatformTransactionManager` en `TenantFilterConfiguration`, reemplazando al que
autoconfigura Spring Boot.

Engancha en la transaccion y no en un interceptor de Spring MVC a proposito: asi el
aislamiento aplica tambien fuera de un request HTTP (tests, tareas programadas, codigo
asincrono) y no depende de que `spring.jpa.open-in-view` siga activo. Todo acceso via
repositorios de Spring Data pasa por aqui, porque `SimpleJpaRepository` es transaccional.

**Callejones sin salida ya recorridos, no repetir:**

* *Interceptor de Spring MVC* (`HandlerInterceptor` que habilita el filtro en `preHandle`).
  Funciona solo dentro de un request y depende de `open-in-view`. En un `@SpringBootTest`
  sin web el filtro nunca se habilita y no hay aislamiento ninguno.
* *Proxy del `EntityManagerFactory`* (`BeanPostProcessor` que envuelve la EMF para
  habilitar el filtro al crear cada `EntityManager`). Rompe la gestion de transacciones de
  Spring: los `save` quedan sin flush, devuelven `id` null y no se escribe ninguna fila.

**Limitacion conocida:** el tenant se resuelve al iniciar la transaccion, asi que
`TenantContext` debe estar poblado antes. En un request lo garantiza
`JwtAuthenticationFilter`, que corre dentro de la cadena de Spring Security, antes de que
el controlador abra ninguna transaccion. Codigo que abra transacciones fuera de un request tiene
que poblar `TenantContext` explicitamente.

**`tenant.TenantFilterConfiguration`**
`@Configuration` que registra el bean `PlatformTransactionManager` con
`TenantAwareJpaTransactionManager`.

## Capa de seguridad JWT

Autenticacion por token, sin sesion de servidor. El tenant no lo elige el cliente: viaja
firmado dentro del token.

### Flujo completo

1. **`POST /api/auth/login`** (unica ruta con `permitAll`). Body: `tenantSlug`, `username`,
   `password`.
2. **`auth.AuthController`** busca el tenant por `slug`, luego el usuario con
   `findByUsernameAndTenantId`, y compara la contrasena con
   `passwordEncoder.matches(...)` contra el `passwordHash` BCrypt.
3. Si algo no cuadra responde **401 con el mismo mensaje generico** en todos los casos
   (tenant inexistente, usuario inexistente, usuario inactivo, contrasena incorrecta).
   Distinguirlos le confirmaria a un atacante que usuarios existen en que tenant.
4. Si cuadra, **`auth.JwtService.generateToken(user)`** emite un JWT firmado con HMAC SHA
   con los claims `sub` (username), `tenantId` y `role`, y expiracion segun configuracion.
5. En cada request posterior el cliente manda `Authorization: Bearer <token>`.
6. **`auth.JwtAuthenticationFilter`** (registrado antes de
   `UsernamePasswordAuthenticationFilter`) valida firma y expiracion. Con el token bueno:
   pone `TenantContext.setTenantId(tenantId del token)`, **consulta el usuario en base con
   `findByUsernameAndTenantId` y confirma que siga existiendo y activo**, y recien
   entonces puebla el `SecurityContextHolder` con el username y la authority
   `ROLE_<rol>`. Si el token es invalido o vencido, o el usuario ya no existe o esta
   inactivo, no autentica y limpia todo; la cadena de seguridad responde 401.

   El tenant se pone **antes** de la consulta a proposito: asi el filtro de Hibernate ya
   esta activo y la busqueda del usuario no puede cruzar de tenant. Y la authority sale
   del rol **en base**, no del claim del token, para que un cambio de rol tampoco tenga
   que esperar a que el token expire.
7. Al abrir la transaccion, `TenantAwareJpaTransactionManager` toma ese `TenantContext` y
   habilita el filtro de Hibernate, ver
   la seccion Estrategia multi-tenant de este mismo archivo.

En resumen: `login -> JWT con tenantId -> JwtAuthenticationFilter -> TenantContext ->
filtro de Hibernate`. El tenant nunca lo aporta el cliente en claro.

**`TenantContext.clear()` va en un `finally`** dentro del filtro. El pool de hilos de
Tomcat se reutiliza entre requests: un tenant que quede pegado se filtraria al siguiente
request atendido por ese hilo.

### Verificacion de usuario activo en cada request

La firma de un JWT solo prueba que el token se emitio en algun momento; no dice nada del
estado actual del usuario. Sin la consulta del paso 6, desactivar a un empleado no surtiria
efecto hasta que su token venciera, hasta 24 horas despues. Por eso cada request protegido
paga una consulta a `USUARIOS`. Motivo completo en [03-DECISIONS.md](03-DECISIONS.md).

**Costo conocido:** es una consulta extra por request autenticado. Hoy es irrelevante, es
una lectura por clave unica `(tenant_id, username)` con indice. Si algun dia el volumen lo
justifica, la salida tipica es una cache corta en memoria del estado del usuario, con TTL
de segundos, o una lista de tokens revocados. No esta implementado ni hace falta todavia.

### Manejo centralizado de errores

`shared.GlobalExceptionHandler` es un `@RestControllerAdvice` y es el unico lugar que
construye respuestas de error. Todas usan el mismo formato, el record
`shared.ErrorResponse`:

```json
{"error": "mensaje", "status": 401}
```

| Excepcion | Codigo | Cuerpo |
|-|-|-|
| `IllegalStateException` (tipicamente el `@PrePersist` sin tenant activo) | 400 | mensaje de la excepcion |
| `IllegalArgumentException` (id del cuerpo que no resuelve, rango de fechas invalido) | 400 | mensaje de la excepcion |
| `InvalidTransferException`, `InvalidSaleOperationException`, `InvalidPurchaseOperationException` | 400 | mensaje de la excepcion |
| `DuplicateProductCodeException`, `DuplicateAccountNameException`, `DuplicateInvoiceNumberException`, `DuplicatePurchaseInvoiceNumberException` | 409 | mensaje de la excepcion |
| `InsufficientStockException`, `InsufficientBalanceException` | 409 | mensaje de la excepcion |
| `InvalidCredentialsException` y `AuthenticationException` | 401 | "Credenciales invalidas" |
| `MethodArgumentNotValidException` (fallo de `@Valid`) | 400 | "Peticion invalida" |
| `ResourceNotFoundException` | 404 | mensaje de la excepcion |
| `NoResourceFoundException` | 404 | "Recurso no encontrado" |
| `Exception` (ultimo recurso) | 500 | "Error interno del servidor" |

El fallback de 500 registra el detalle en el log del servidor y **nunca lo devuelve al
cliente**: un stacktrace en la respuesta le regala al atacante versiones de librerias y
rutas internas.

`AuthController` no arma respuestas de error: lanza `InvalidCredentialsException` en los
cuatro casos de login fallido y el advice la traduce. Asi el mensaje generico se define en
un solo sitio.

Los rechazos de la cadena de seguridad ocurren antes de llegar a un controlador, asi que el
`@RestControllerAdvice` no los ve. `config.JsonAuthenticationEntryPoint` cubre ese caso
escribiendo el mismo formato, para que el cliente no tenga que parsear dos formas distintas
de error.

### Configuracion

`SecurityConfig` (paquete `config`): sesion `STATELESS`, CSRF deshabilitado (es una API
pura, sin formularios ni cookies de sesion), `BCryptPasswordEncoder` como bean,
`permitAll` solo en `POST /api/auth/login` y `authenticated()` en todo lo demas.

Propiedades en `application.properties`:

```properties
servibox.jwt.secret=${JWT_SECRET:dev-secret-cambiar-en-produccion-minimo-32-caracteres}
servibox.jwt.expiration-ms=${JWT_EXPIRATION_MS:86400000}
```

El valor por defecto del secret es solo para desarrollo local. **En produccion `JWT_SECRET`
tiene que venir de una variable de entorno real**, nunca el valor de respaldo.

### Modelo de usuario

`auth.entity.User` extiende `TenantAwareEntity`: `username`, `passwordHash`, `email`,
`active`, y `role` como enum `auth.entity.Role` (`ADMIN`, `EMPLEADO`) con
`@Enumerated(STRING)`. Motivo del enum en vez de tabla en
[03-DECISIONS.md](03-DECISIONS.md).

La restriccion unica es **compuesta**, `(tenant_id, username)`, no `unique` en la columna
sola: dos negocios distintos pueden tener cada uno su usuario `admin`.

Consecuencia de eso: el login necesita saber a que tenant entrar antes de poder resolver
el username, por eso `LoginRequest` lleva `tenantSlug` ademas de usuario y contrasena.

### Datos de prueba local

`config.DevDataInitializer` es un `CommandLineRunner` con `@Profile("dev")`: si la tabla
de tenants esta vacia crea el tenant `demo` y el usuario `admin` (`admin123`), y para ese
tenant el `TaxType` IVA (0.19, `isVat` true), las 8 categorias por defecto de Autollantas
con IVA y las 2 cuentas por defecto (ver Modulo Treasury). Solo para probar en local.

## Estructura de paquetes

Base: `com.servibox.backend`. Un paquete por modulo de negocio, mas tres transversales.

| Paquete | Contenido |
|-|-|
| `config` | `SecurityConfig`, `JpaAuditingConfiguration`, `JsonAuthenticationEntryPoint`, `DevDataInitializer` |
| `tenant` | `Tenant`, `TenantRepository`, `TenantContext`, `TenantAwareJpaTransactionManager`, `TenantFilterConfiguration` |
| `shared` | `TenantAwareEntity`, `ErrorResponse`, `GlobalExceptionHandler`, `ResourceNotFoundException` |
| `auth` | `JwtService`, `JwtAuthenticationFilter`, `AuthController`, `InvalidCredentialsException`, mas `entity` / `repository` / `dto` |
| `inventory` | primer modulo de negocio migrado, ver abajo |
| `treasury` | segundo modulo de negocio migrado, ver abajo |
| `counterparties` | terceros (`Counterparty`): clientes y proveedores en una sola entidad, compartida por `sales` y `purchases`, ver abajo |
| `sales` | tercer modulo de negocio migrado, ver abajo |
| `purchases` | cuarto modulo de negocio migrado, ver abajo |
| `reporting` | quinto paquete de negocio y ultimo modulo del roadmap, solo lectura, ver abajo |

Los modulos de negocio usan siempre las mismas cinco capas:
`controller`, `service`, `repository`, `entity`, `dto`. Las excepciones de dominio de cada
modulo viven en su paquete `service`.

### Modulo Inventory

Migrado desde el modulo `inventory` de Autollantas. Todas sus entidades extienden
`TenantAwareEntity`, asi que heredan `id`, `tenantId`, auditoria, y el filtro por tenant.

**Entidades**

* `ProductCategory` (`CATEGORIA_PRODUCTOS`): `name`, `color`, `yellowStockMin`,
  `redStockMin`, `targetMargin` (fraccion, 0.30 es 30 por ciento) y `taxTypes`, un
  `ManyToMany` EAGER contra `TaxType` por la tabla `CATEGORIA_IMPUESTOS`.
* `TaxType` (`TIPOS_IMPUESTO`): `name`, `rate`, `isVat`, `appliesToTransaction`,
  `description`.
* `Product` (`PRODUCTOS`): `code`, `description`, `purchaseCost`, `quantity`, `taxAmount`,
  `suggestedPrice`, y `ManyToOne` a `ProductCategory`. Restriccion unica **compuesta**
  `uk_producto_tenant_code` sobre `(tenant_id, codigo_producto)`, mismo criterio que
  `(tenant_id, username)` en `User`: dos talleres distintos pueden usar cada uno el codigo
  `LLA-001` para productos que no tienen nada que ver.

  La unicidad se defiende en dos capas. `InventoryService.saveProduct` consulta
  `findByCodeAndTenantId` antes del INSERT y lanza `DuplicateProductCodeException`, que
  `GlobalExceptionHandler` traduce a **409 Conflict** con un mensaje legible. La
  restriccion de base queda igual como red de seguridad para cualquier escritura que no
  pase por el service. Al editar, la validacion excluye el propio registro: volver a
  guardar un producto sin cambiarle el codigo no es un duplicado.
* `FinancialSettings` (`CONFIGURACION_FINANCIERA`): `expensesRate`, `dianRate`, `icaRate`,
  `cardCommissionRate`. En Autollantas es un singleton con id fijo 1; aqui es **una fila
  por tenant**, porque cada negocio tiene sus propios porcentajes.

**Calculo de precios** (`InventoryService.recalculatePrices`, portado de Autollantas)

```java
taxAmount     = purchaseCost * suma(rates de la categoria con isVat true)
k             = (1 - expensesRate) * (1 - dianRate)
divisor       = k - icaRate - targetMargin
suggestedPrice= divisor > 0 ? purchaseCost * k / divisor : purchaseCost
```

`suggestedPrice` esta portado sin cambios: el margen es sobre el **precio de venta**, no
sobre el costo, y por eso aparece despejado en el divisor. Cuando el margen mas el ICA
superan lo que dejan Gastos y DIAN no existe precio finito que cumpla, y se usa el costo
como piso.

`taxAmount` es la **unica divergencia deliberada** respecto de Autollantas: alli suma
todas las rates de la categoria, aqui solo las marcadas `isVat`. Motivo en
[03-DECISIONS.md](03-DECISIONS.md).

`updateCategoryMargin` es el equivalente del tab Margenes de Utilidad: cambia el margen de
la categoria y rehace el precio sugerido de todos sus productos.

**Endpoints** (todos requieren JWT)

* `GET` y `POST` `/api/inventory/categories`
* `GET` y `POST` `/api/inventory/products`
* `GET /api/inventory/products/{id}`

Las respuestas van siempre por DTO (`CategoryResponse`, `ProductResponse`,
`TaxTypeResponse`), nunca la entidad JPA: exponerla arrastraria `tenantId` y las
relaciones EAGER completas al cliente.

### Modulo Treasury

Migrado desde el paquete `treasury` de Autollantas (`model` / `repository` / `service`).
Todas sus entidades extienden `TenantAwareEntity`.

**Entidades**

* `Account` (`CUENTAS`): `name`, `initialBalance`, `currentBalance`, `type` (enum
  `AccountType`, `CASH` o `BANK`). Restriccion unica **compuesta** `uk_cuenta_tenant_name`
  sobre `(tenant_id, nombre_cuenta)`, mismo criterio que `(tenant_id, username)` en `User`
  y `(tenant_id, codigo_producto)` en `Product`: cada taller tiene su propia "Caja
  General".

  Igual que con el codigo de producto, la unicidad se defiende en dos capas.
  `TreasuryService.saveAccount` consulta `findByNameAndTenantId` antes del INSERT y lanza
  `DuplicateAccountNameException`, que `GlobalExceptionHandler` traduce a **409 Conflict**
  con un mensaje legible. La restriccion de base queda como red de seguridad para
  cualquier escritura que no pase por el service. Al editar, la validacion excluye el
  propio registro.

  Una cuenta nueva arranca con `currentBalance` igual a su `initialBalance`: mientras no
  haya movimientos, el saldo vivo es el saldo de apertura.
* `Movement` (`MOVIMIENTOS`): **el libro de movimientos**, y desde el paso 2 del refactor
  de normalizacion la unica entidad de tesoreria ademas de `Account`. Gastos operativos,
  ingresos ocasionales y transferencias **no tienen tabla propia**: son movimientos con su
  categoria. Ver [04-DATA-MODEL.md](04-DATA-MODEL.md), MOVIMIENTOS.

  | Campo | Columna | Notas |
  |-|-|-|
  | `account` | `id_cuenta` | `ManyToOne` a `Account` |
  | `type` | `tipo_movimiento` | Enum `MovementType`: solo `INGRESO` o `EGRESO`. Es lo que da el signo sobre el saldo |
  | `category` | `categoria_movimiento` | Enum `MovementCategory`, obligatorio (NOT NULL en la base) |
  | `concept` | `concepto_movimiento` | Columna real, divergencia deliberada respecto de Autollantas |
  | `amount` | `monto_movimiento` | |
  | `date` | `fecha_movimiento` | Fecha de negocio (`LocalDate`), no de auditoria |
  | `notes` | `notas` | Opcional, 255 |
  | `relatedMovement` | `id_movimiento_relacionado` | `ManyToOne` a `Movement`, clave foranea real. Solo en la pata INGRESO de una transferencia, apuntando a la pata EGRESO. Excluido de `toString` y `equals` |
  | `sourceType` / `sourceId` | `tipo_origen_movimiento` / `id_origen_movimiento` | Factura de origen: `SALE` o `PURCHASE`. Sin clave foranea. **Desaparece en el paso 3**, cuando se usen `id_venta` / `id_compra` |

  Las columnas que ya existian conservan su nombre en este paso; se alinean con el
  diccionario en el paso 4.

  **Categorias** (`MovementCategory`) y su tipo, validado en `TreasuryService`:

  | Categoria | Tipo | Quien la crea | Manual |
  |-|-|-|-|
  | `GASTO_OPERATIVO` | `EGRESO` | El usuario: arriendo, servicios, papeleria | Si |
  | `INGRESO_OCASIONAL` | `INGRESO` | El usuario: chatarra, reintegros, aporte del socio | Si |
  | `TRANSFERENCIA` | `EGRESO` en el origen, `INGRESO` en el destino | `registrarTransferencia` | No |
  | `PAGO_COMPRA` | `EGRESO` | La compra de contado y cada pago (`(PURCHASE, id)`) | No |
  | `RECAUDO_VENTA` | `INGRESO` | La venta de contado y cada abono (`(SALE, id)`) | No |

  Solo las dos manuales se crean, editan y eliminan directamente. Las demas se gestionan
  desde su operacion; editarlas o eliminarlas a mano es **400** y no toca nada.

**`TreasuryService`**

* `aplicarMovimiento(movimiento)` (privado): **el unico sitio donde se guarda un
  `Movement` y se aplica su efecto sobre el `currentBalance` de su cuenta.** Suma si es
  `INGRESO`, resta si es `EGRESO`, y antes valida la coherencia entre tipo y categoria.
  Sirve para un movimiento nuevo y para uno editado (misma fila, efecto nuevo). La unica
  otra escritura del saldo es `deshacerEfecto`, que revierte el efecto de un movimiento
  sobre **su** cuenta; la usan editar y eliminar un movimiento manual y anular una
  factura.
* `registrarIngresoOcasional(cuenta, concepto, monto, fecha, notas)` y
  `registrarGastoOperativo(cuenta, concepto, monto, fecha, notas)`: crean un `INGRESO`
  `INGRESO_OCASIONAL` o un `EGRESO` `GASTO_OPERATIVO`. Delegan en
  `registrarMovimientoManual(cuenta, categoria, ...)`, que es lo que usa el endpoint y
  rechaza cualquier otra categoria (`InvalidMovementOperationException`, **400**). Monto no
  positivo o cuenta ausente, 400. **No exigen saldo suficiente**, igual que Autollantas:
  un gasto puede dejar la cuenta en negativo. Ver [03-DECISIONS.md](03-DECISIONS.md).
* `editarMovimientoManual(id, cuenta, concepto, monto, fecha, notas)`: solo
  `GASTO_OPERATIVO` e `INGRESO_OCASIONAL`. Revierte el efecto anterior **sobre la cuenta
  anterior**, actualiza **la misma fila** y aplica el efecto nuevo sobre la cuenta nueva,
  asi que cambiar de cuenta devuelve el dinero de donde salio. Queda un solo movimiento,
  sin contra-movimiento. La categoria no se cambia.
* `eliminarMovimientoManual(id)`: mismas dos categorias. Revierte el saldo y **borra la
  fila**: no tienen factura ni estado, no queda nada `ANULADA`. Es "Eliminar" en
  Autollantas.
* Las dos anteriores buscan con `findByIdAndTenantId`: si el movimiento no es del tenant,
  `ResourceNotFoundException` (**404**); si es de otra categoria,
  `InvalidMovementOperationException` (**400**, "se gestiona desde su operacion").
* `registrarTransferencia(origen, destino, concepto, monto, fecha)`: **dos movimientos de
  categoria `TRANSFERENCIA`**, un `EGRESO` en el origen ("Transferencia a {destino}") y un
  `INGRESO` en el destino ("Transferencia desde {origen}") cuyo `relatedMovement` es el
  `EGRESO`. El concepto que manda el usuario va a `notes` en las dos patas. Devuelve el
  record `Transferencia(egreso, ingreso)`. Todo en una transaccion; **el dinero se
  conserva**. Rechaza con `InvalidTransferException` (**400**) cuentas iguales o monto no
  positivo, sin mover ningun saldo ni dejar movimientos.
* `registrarIngresoDeVenta` y `registrarEgresoDeCompra`: crean los movimientos
  `RECAUDO_VENTA` y `PAGO_COMPRA` de las facturas, con `(SALE, id)` o `(PURCHASE, id)`.
  El de compra exige saldo suficiente. `revertirMovimientosDeVenta` / `...DeCompra` los
  revierten y borran al anular (se redisena en el paso 3).
* Consultas: `findMovementById`, `findMovementsByAccountId`,
  `buscarMovimientos(accountId, categoria, desde, hasta)` con los cuatro filtros
  opcionales, y `sumarPorCategoria(categoria)` / `sumarPorCategoria(categoria, desde,
  hasta)`, que usa Reporting.
* `balanceGlobal()`: suma de los `currentBalance` de todas las cuentas del tenant activo.
  Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml`.

**Cuidado: el `@Filter` de Hibernate no cubre `findById`.** Aqui se detecto el hueco;
la regla vigente esta en [02-CONVENTIONS.md](02-CONVENTIONS.md) (Busqueda por id) y la
historia en [03-DECISIONS.md](03-DECISIONS.md).

**Endpoints** (todos requieren JWT)

* `GET` y `POST` `/api/treasury/accounts`
* `GET /api/treasury/accounts/{id}/movements`: 404 si la cuenta no es del tenant
* `GET /api/treasury/movements?accountId=&category=&desde=&hasta=`: el libro del tenant,
  todos los filtros opcionales, del mas reciente al mas antiguo
* `POST /api/treasury/movements`: movimiento manual. Cuerpo `MovementRequest`:
  `accountId`, `category` (solo `GASTO_OPERATIVO` o `INGRESO_OCASIONAL`, otra es 400),
  `concept`, `amount`, `date` (opcional, hoy por defecto) y `notes` (opcional)
* `PUT /api/treasury/movements/{id}`: mismo cuerpo; la `category` tiene que ser la que ya
  tenia (si no, 400). 404 si no es del tenant. `PUT` y no `PATCH` porque el cuerpo trae
  el movimiento completo, como el formulario de Autollantas
* `DELETE /api/treasury/movements/{id}`: **204**; 404 si no es del tenant. `DELETE` y no
  un `/annul` porque el registro desaparece
* `POST /api/treasury/transfers`: mismo cuerpo que antes (`originAccountId`,
  `destinationAccountId`, `concept`, `amount`); la respuesta lleva `outgoingMovementId` e
  `incomingMovementId`
* `GET /api/treasury/balance` (balance global)

Desaparecieron `/api/treasury/occasional-incomes` y `/api/treasury/operational-expenses`.

Las respuestas van siempre por DTO (`AccountResponse`, `MovementResponse`,
`TransferResponse`, `BalanceResponse`), nunca la entidad JPA. `MovementResponse` expone
`id`, `accountId`, `accountName`, `type`, `category`, `concept`, `amount`, `date`,
`notes`, `sourceType`, `sourceId` y `relatedMovementId`.

**Semilla de desarrollo.** `DevDataInitializer` crea ademas las 2 cuentas por defecto de
Autollantas para el tenant `demo`: `Caja General` (`CASH`) y `Bancolombia` (`BANK`), las
dos con saldo inicial 0.

### Modulo Counterparties

Terceros: la persona o empresa con la que el taller comercia. Unifica el cliente de una
venta y el proveedor de una compra (paso 1 del refactor de normalizacion, ver
[04-DATA-MODEL.md](04-DATA-MODEL.md), TERCEROS). No tiene controller propio todavia.

**Entidad** `Counterparty` (`TERCEROS`):

| Campo | Columna | Notas |
|-|-|-|
| `id` | `id_tercero` | `@AttributeOverride` sobre el `id` de `TenantAwareEntity` |
| `tenantId` | `tenant_id` | Heredado. No se renombra a `id_tenant`: el `@Filter` de la clase base lleva la columna escrita en su condicion |
| `role` | `tipo_tercero` | Enum `CounterpartyRole`: `CLIENTE`, `PROVEEDOR`, `AMBOS`. Obligatorio |
| `name` | `nombre_razon_social` | 150. Obligatorio |
| `documentType` | `tipo_documento` | Enum `DocumentType`: `CC`, `NIT`, `CE`, `RUT`, `PASAPORTE`. Obligatorio |
| `documentNumber` | `numero_documento` | 25. Obligatorio. Unico por tenant: `uk_tercero_tenant_document` |
| `email` | `correo` | 150, opcional |
| `phone` | `celular` | 25, opcional |

**`CounterpartyService`**

* `crear(tercero)`: valida rol, nombre, tipo y numero de documento
  (`InvalidCounterpartyException`, **400**) y que el documento no exista en el tenant
  (`DuplicateCounterpartyDocumentException`, **409**).
* `obtenerOCrear(rolRequerido, tipoDocumento, numeroDocumento, nombre, correo, celular)`:
  busca por `(tenant, numero_documento)`. Si no existe lo crea con el rol pedido; si existe
  con el rol contrario lo pasa a `AMBOS`; si ya tiene ese rol o `AMBOS` lo devuelve. Correo
  y celular solo completan los vacios; nombre y tipo de documento de uno existente no se
  tocan.
* `exigirRol(tercero, rolRequerido)`: una venta exige `CLIENTE` o `AMBOS`; una compra,
  `PROVEEDOR` o `AMBOS`. Si no, `InvalidCounterpartyException` (**400**). Lo llaman
  `crearFactura` de ventas y de compras cuando reciben un tercero.
* `findById` usa `findByIdAndTenantId`; `findByDocumentNumber`, `findByDocumentNumberAndTenantId`.

### Modulo Sales

Migrado desde el paquete `sales` de Autollantas. Es el primer modulo que **cruza los otros
dos**: una factura descuenta inventario y mueve tesoreria.

**Entidades**

* El cliente es un `Counterparty` (ver Modulo Counterparties); `sales` ya no tiene entidad
  propia de cliente.
* `Sale` (`VENTAS`): `invoiceNumber`, `ManyToOne` **nullable** `customer` a `Counterparty`
  (columna `id_tercero`), `invoiceDate`, `dueDate`,
  `paymentType` (enum `CONTADO` / `CREDITO`), `ManyToOne` **nullable** a `Account` (una
  factura a credito no tiene cuenta hasta que se le abona), `paymentMethod`, `status`
  (enum `PAGADA` / `PENDIENTE` / `ANULADA`), `subtotal`, `ivaPorPagar`, `total`.
  Restriccion unica `uk_venta_tenant_invoice_number` sobre
  `(tenant_id, numero_factura_venta)`, **divergencia deliberada**: Autollantas valida el
  numero solo en la aplicacion y no tiene indice unico. Ver
  [03-DECISIONS.md](03-DECISIONS.md).
* `SaleDetail` (`DETALLE_VENTAS`): `ManyToOne` a `Sale` y a `Product`, `quantity`, `price`
  (unitario sin IVA) e `ivaAmount`, **congelado al facturar**. Cambiarle el IVA a un
  producto no puede mover el IVA de una factura ya emitida, porque esa factura ya se
  entrego y se declaro. Es el mismo campo `iva_generado_linea` de Autollantas.
  Lleva ademas `ivaDifference` (`diferencia_iva_linea`), el IVA **neto** de la linea
  (`ivaAmount` menos `producto.taxAmount * quantity`), congelado igual. Lo agrego la
  migracion de Reporting: el Reporte de IVA reparte el descontable por categoria y
  necesita el de cada linea, no solo el agregado de la factura.
* `Collection` (`RECAUDOS`): `ManyToOne` a `Sale` y a `Account`, `amount`, `date`. Es un
  **abono**, ver [02-CONVENTIONS.md](02-CONVENTIONS.md). En Autollantas esta clase vive en
  `treasury`; aqui vive en `sales`, junto a la factura a la que pertenece.

**Flujo factura -> inventario -> tesoreria**

`SalesService.crearFactura` hace todo esto en **una sola transaccion**:

1. Valida que el `invoiceNumber` no exista ya en el tenant y lanza
   `DuplicateInvoiceNumberException` (**409**) si existe. La restriccion de base queda como
   red de seguridad.
2. Por cada linea: resuelve el producto, comprueba que haya stock suficiente
   (`InsufficientStockException`, **409**, si no alcanza) y **descuenta**
   `Product.quantity`.
3. Calcula el IVA de la linea como `price * quantity * InventoryService.getIvaRateForProduct(producto)`
   y lo **congela** en `SaleDetail.ivaAmount`.
4. Totaliza la factura, con las mismas formulas de Autollantas:

   ```
   subtotal    = suma(price * quantity)                 // sin IVA
   ivaGenerado = suma(ivaAmount de cada linea)
   ivaFavor    = suma(producto.taxAmount * quantity)    // IVA ya pagado al comprar
   ivaPorPagar = ivaGenerado - ivaFavor
   total       = subtotal + ivaGenerado
   ```

5. Si es `CONTADO`: estado `PAGADA` y `TreasuryService.registrarIngresoDeVenta` mete un
   `INGRESO` por el total en la cuenta, con concepto `Venta <numero>` y **fecha de la
   factura** (no la de hoy). Si es `CREDITO`: estado `PENDIENTE` y tesoreria no
   se toca, porque todavia no entro dinero.

**Abonos.** `registrarAbono(saleId, accountId, monto)` crea el `Collection`, registra el
`INGRESO` en tesoreria y, si con eso el saldo pendiente queda cubierto, pasa la factura a
`PAGADA`. Rechaza (`InvalidSaleOperationException`, **400**) abonar a una factura que no
este `PENDIENTE` y abonos que excedan el saldo pendiente.

El saldo pendiente **no es un campo**: se calcula como `total` menos la suma de abonos.
Autollantas lo guarda denormalizado en `saldo_pendiente` y lo recalcula a mano en cinco
sitios distintos. La tolerancia de un peso de Autollantas si se porto: un pendiente por
debajo de 1 peso cuenta como saldado, porque los centavos del IVA no pueden dejar una
factura eternamente `PENDIENTE` por 0,4 pesos.

**Anulacion.** `anularFactura(saleId)`:

* Revierte en tesoreria **todos** los movimientos ligados a la venta y deshace su efecto
  sobre el saldo de la cuenta (`TreasuryService.revertirMovimientosDeVenta`).
* Devuelve el stock de cada linea.
* Deja la factura en `ANULADA`.

Una factura ya `ANULADA` no se puede volver a anular: devolveria el stock por segunda vez.
Una `PENDIENTE` sin abonos no genero ningun movimiento, asi que el primer paso no hace
nada y en la practica solo se devuelve el stock, que es el comportamiento de Autollantas.

**Restauracion.** `restaurarFactura(saleId)` es el inverso exacto de la anulacion: lo que
anular saco, restaurar lo devuelve. Motivo y comparacion con Autollantas en
[03-DECISIONS.md](03-DECISIONS.md).

* Solo una `ANULADA`; en cualquier otro estado rechaza con `InvalidSaleOperationException`
  (**400**). Tambien rechaza, sin tocar nada, si queda algun `Movement` vivo con origen
  `(SALE, id)`: la factura no esta anulada de verdad en tesoreria y recrear duplicaria el
  dinero.
* Vuelve a descontar el stock de cada linea con el mismo `descontarStock` de
  `crearFactura`: si no alcanza, `InsufficientStockException` (**409**) y la transaccion
  deshace todo.
* Recrea por `TreasuryService.registrarIngresoDeVenta` los movimientos que la anulacion
  borro: el `INGRESO` del contado (total, cuenta de la factura, `Venta <numero>`, fecha de
  la factura) y uno por cada `Collection` (su monto, su cuenta, `Abono factura <numero>`,
  fecha del abono). Las filas `Collection` sobreviven a la anulacion; de ahi salen. Los
  conceptos viven en `conceptoVenta` / `conceptoAbono`, compartidos con crear y abonar.
* El estado se deriva, no se guarda: `CONTADO` queda `PAGADA`; `CREDITO` queda `PAGADA` si
  el saldo pendiente cae dentro de la tolerancia de un peso (la misma comparacion de
  `registrarAbono`) y `PENDIENTE` si no.

**Endpoints** (todos requieren JWT)

* `POST /api/sales` (emitir factura)
* `GET /api/sales`
* `GET /api/sales/{id}`: 404 si no existe para el tenant
* `POST /api/sales/{id}/collections` (abono)
* `POST /api/sales/{id}/annul`
* `POST /api/sales/{id}/restore`

Las respuestas van por DTO (`SaleResponse`, `SaleDetailResponse`, `CollectionResponse`),
nunca la entidad JPA. `SaleResponse` incluye `pendingBalance` calculado.

El cliente se identifica **por id** (`customerId` en `SaleRequest`, opcional). Un id que
no resuelve en el tenant es 400 `Cliente no encontrado`; uno que resuelve a un tercero
`PROVEEDOR` es 400 por rol. En la respuesta sale como `customer`, un
`CounterpartyResponse` (`id`, `role`, `name`, `documentType`, `documentNumber`, `email`,
`phone`), o `null`. Compras hace lo mismo con `supplierId` y `supplier`.

**Tasa de IVA:** sale de `InventoryService.getIvaRateForProduct(Product)`, la misma que
usan Inventory y Purchases. Regla en [02-CONVENTIONS.md](02-CONVENTIONS.md) (Calculos que
no se copian).

### Modulo Purchases

Migrado desde el paquete `purchases` de Autollantas. Es el espejo de Sales con el signo
invertido: una compra **suma** stock y **saca** dinero.

**Entidades**

* El proveedor es un `Counterparty` (ver Modulo Counterparties); `purchases` ya no tiene
  entidad propia de proveedor.
* `Purchase` (`COMPRAS`): `invoiceNumber`, `ManyToOne` **nullable** `supplier` a
  `Counterparty` (columna `id_tercero`), `invoiceDate`,
  `dueDate`, `paymentType` (enum `CONTADO` / `CREDITO`), `ManyToOne` **nullable** a
  `Account`, `paymentMethod`, `status` (enum `PAGADA` / `PENDIENTE` / `ANULADA`),
  `subtotal`, `ivaTotal`, `total`. Restriccion unica
  `uk_compra_tenant_invoice_number`, misma divergencia deliberada que en `Sale`:
  Autollantas valida el numero solo en la aplicacion.
* `PurchaseDetail` (`DETALLE_COMPRAS`): `ManyToOne` a `Purchase` y a `Product`,
  `quantity`, `price` (costo de compra unitario sin IVA) e `ivaAmount` de la linea.
* `Payment` (`PAGOS`): `ManyToOne` a `Purchase` y a `Account`, `amount`, `date`. Es un
  **pago**, no un abono, ver [02-CONVENTIONS.md](02-CONVENTIONS.md). En Autollantas esta
  clase vive en `treasury`; aqui vive en `purchases`, junto a la factura.

**Flujo factura -> inventario (suma) -> tesoreria (egreso)**

`PurchasesService.crearFactura`, todo en **una transaccion**:

1. Valida que el `invoiceNumber` no exista en el tenant
   (`DuplicatePurchaseInvoiceNumberException`, **409**).
2. Calcula las lineas y los totales **antes de tocar nada**:

   ```
   subtotal = suma(price * quantity)
   ivaTotal = suma(price * quantity * InventoryService.getIvaRateForProduct(producto))
   total    = subtotal + ivaTotal
   ```

3. Si es `CONTADO`, exige que la cuenta tenga saldo suficiente para el total
   (`InsufficientBalanceException`, **409**). **No aplica a `CREDITO`**: una compra a
   credito no saca dinero hoy, asi que no hay nada que validar. Es exactamente lo que hace
   Autollantas, solo que alli la comprobacion vive en el formulario y aqui en el service.
4. **Suma** `Product.quantity` por cada linea, al reves de una venta.
5. **Sincroniza el costo del producto** por cada linea: `purchaseCost = price` de la linea
   y `InventoryService.recalculatePrices(producto)`, el mismo metodo que usa
   `saveProduct`. Comprar a un costo nuevo reescribe el costo del producto y rehace su
   `taxAmount` y su `suggestedPrice`. Ver mas abajo.
6. Si es `CONTADO`: estado `PAGADA` y un `EGRESO` por el total. Si es `CREDITO`: estado
   `PENDIENTE`, sin movimiento.

**Sincronizacion del costo al comprar.** Portado de `savePurchaseWithDetails` de
Autollantas, que por cada linea hace `realProduct.setPurchaseCost(unitPrice)` seguido de
`inventoryService.recalculatePrices(realProduct)`. Detalles del comportamiento, todos
verificados contra el codigo real de Autollantas:

* Aplica a **todas** las compras, `CONTADO` y `CREDITO`. Alli el bloque que recorre los
  detalles corre antes y fuera del `if ("Contado".equals(...))` que mueve la caja, asi que
  el tipo de pago no lo condiciona.
* Es el `price` de la linea (costo unitario sin IVA), no el total de la linea.
* `recalculatePrices` es el **metodo completo**, no un subconjunto: recalcula `taxAmount` y
  `suggestedPrice` y persiste. Es un no-op si el costo queda en cero.
* Si dos lineas de la misma factura traen el **mismo producto** a precios distintos, gana
  el de la **ultima linea procesada**: cada iteracion pisa el costo de la anterior. El
  stock si acumula las dos. Cubierto por `conDosLineasDelMismoProductoGanaElPrecioDeLaUltima`.
* **Anular no lo revierte**, ver mas abajo.

Las lineas y el stock se tocan **despues** de la validacion de saldo a proposito: asi el
saldo se compara contra el total definitivo y el rechazo ocurre sin haber escrito nada,
aunque la transaccion lo desharia igual.

**Pagos.** `registrarPago(purchaseId, accountId, monto)` crea el `Payment`, registra el
`EGRESO` (que a su vez exige saldo suficiente) y, si con eso queda cubierto el pendiente,
pasa la compra a `PAGADA`. Rechaza pagar una compra que no este `PENDIENTE` y pagos que
excedan el saldo pendiente. El pendiente se calcula como `total` menos la suma de pagos,
igual que en Sales.

**Anulacion.** `anularFactura(purchaseId)`:

* **Comprueba primero todo el stock**: si a algun producto le quedan menos unidades de las
  que habria que quitar, rechaza con `InvalidPurchaseOperationException` (**400**)
  nombrando el producto. Pasa cuando parte de lo comprado ya se vendio.
* Revierte **todos** los movimientos ligados a la compra, pagos incluidos.
* Quita el stock que la compra habia sumado y deja la compra en `ANULADA`.
* **No revierte el `purchaseCost` ni el `suggestedPrice` del producto**: se quedan como los
  dejo la compra. Es lo que hace `cancelPurchase` de Autollantas y se hereda a proposito,
  ver [03-DECISIONS.md](03-DECISIONS.md). Cubierto por
  `anularNoRevierteElCostoDelProducto`.

Una compra ya `ANULADA` no se puede volver a anular.

**Restauracion.** `restaurarCompra(purchaseId)`: espejo exacto de `restaurarFactura`.

* Solo una `ANULADA` y sin movimientos vivos con origen `(PURCHASE, id)`
  (`InvalidPurchaseOperationException`, **400**).
* Vuelve a sumar el stock (sin validacion: sumar siempre se puede).
* Recrea por `registrarEgresoDeCompra` el `EGRESO` del contado (`Compra <numero>`, fecha de
  la factura) y uno por cada `Payment` (`Pago compra <numero>`, fecha del pago). Cada uno
  pasa por `exigirSaldoSuficiente`, la misma regla de crear y pagar: si alguno no alcanza,
  `InsufficientBalanceException` (**409**) y la transaccion deshace stock, egresos ya
  recreados y estado.
* Estado derivado igual que en ventas: contado `PAGADA`, credito por saldo pendiente.
* **No toca `purchaseCost` ni `suggestedPrice`**, igual que la anulacion; cubierto por
  `restaurarNoCambiaElCostoDelProducto`.

El egreso del contado al crear la compra tambien lleva la fecha de la factura.

**Endpoints** (todos requieren JWT)

* `POST /api/purchases`
* `GET /api/purchases`
* `GET /api/purchases/{id}`: 404 si no existe para el tenant
* `POST /api/purchases/{id}/payments`
* `POST /api/purchases/{id}/annul`
* `POST /api/purchases/{id}/restore`

### Modulo Reporting

Ultimo de los 7 bloques del roadmap original (infraestructura multi-tenant, auth,
Inventory, Treasury, Sales, Purchases, Reporting); ingresos ocasionales y gastos
operativos entraron como parte de Treasury. Migrado desde
`reporting/service/DashboardService` y `ReportService.buildReporteIva` de Autollantas.

**No tiene entidades propias ni escribe nada.** Agrega los datos de todos los modulos
anteriores: `Sale` y `SaleDetail` (Sales), `Purchase` (Purchases), los `Movement` de
categoria `GASTO_OPERATIVO` e `INGRESO_OCASIONAL` (Treasury) y la categoria de `Product`
(Inventory). Las consultas viven en el modulo dueno de cada entidad
(`sumTotalByStatusNot...`, `findBy...DateBetween...`; en Treasury, `TreasuryService.
sumarPorCategoria` y `buscarMovimientos`), no en `reporting/repository`, que sigue vacio:
la consulta pertenece a la entidad que lee.
El aislamiento por tenant lo da el filtro de Hibernate, porque todas son consultas JPQL o
derivadas; ninguna pasa por `findById`.

**Regla de exclusion.** Las facturas de venta y de compra en `ANULADA` no cuentan en
nada: ni KPIs, ni movimientos, ni Reporte de IVA. El filtro es por **estado**
(`status <> ANULADA`), no por historia, asi que una factura que vuelva a `PENDIENTE`
vuelve a contar sin mas. Gastos operativos e ingresos ocasionales no tienen estado: se
eliminan, y al eliminarse desaparecen solos.

**`ReportingService`**

* `getGlobalKpis()`: los 5 KPIs sobre todo el historico del tenant.
* `getPeriodKpis(desde, hasta)`: los mismos 5, por `invoiceDate` de la factura o `date`
  del movimiento de gasto dentro de `[desde, hasta]`, ambos inclusive. Dos metodos y dos consultas por
  cifra, no uno con fechas opcionales: un `(:desde IS NULL OR ...)` en JPQL da problemas de
  tipado en PostgreSQL.

  ```
  totalSales        = suma(Sale.total)            sin ANULADA
  totalPurchases    = suma(Purchase.total)        sin ANULADA
  grossProfit       = totalSales - totalPurchases
  operatingExpenses = suma(Movement.amount)       categoria GASTO_OPERATIVO
  netProfit         = grossProfit - operatingExpenses
  ```

  Los totales son **con IVA**, que es lo que guarda `total`. Los ingresos ocasionales no
  entran en ningun KPI.
* `getMovements(desde, hasta)`: ventas, compras, gastos e ingresos ocasionales del rango
  como renglones (`VENTA`, `COMPRA`, `GASTO`, `INGRESO`), del mas reciente al mas antiguo y
  cortado en `MAX_MOVEMENTS` (100), igual que Autollantas. `VENTA` y `COMPRA` salen de las
  facturas, no de sus movimientos: una venta a credito aparece aunque no haya movido caja.
  `GASTO` e `INGRESO` salen de los movimientos `GASTO_OPERATIVO` e `INGRESO_OCASIONAL`, y
  su `sourceId` es el id del movimiento. Las transferencias no aparecen.
* `buildReporteIva(desde, hasta)`: por cada linea de venta no anulada del rango, agrupa
  por nombre de categoria del producto:

  ```
  ivaGenerado    = suma(SaleDetail.ivaAmount)
  ivaNeto        = suma(SaleDetail.ivaDifference)
  ivaDescontable = ivaGenerado - ivaNeto
  ```

  Ordena por IVA generado descendente y agrega el resumen: facturas con IVA, y los tres
  totales. Las lineas con IVA generado cero se ignoran, como en Autollantas. Los dos campos
  estan congelados al facturar, asi que una compra posterior que cambie el `taxAmount` del
  producto no mueve el reporte de un periodo ya facturado.

Rangos: `desde` y `hasta` son obligatorios en periodo, movimientos e IVA; si falta uno, o
`desde` es posterior a `hasta`, el service lanza `IllegalArgumentException` (**400**).

**Endpoints** (todos requieren JWT, todos `GET`, todos JSON)

* `/api/reporting/kpis`: sin parametros, KPIs globales. Con `desde` y `hasta` (ISO,
  `2026-09-01`), KPIs del periodo. Una sola ruta porque la respuesta es el mismo DTO;
  `desde` / `hasta` vuelven null en el global.
* `/api/reporting/movements?desde=...&hasta=...`
* `/api/reporting/iva?desde=...&hasta=...`

DTOs: `KpisResponse`, `MovementSummaryResponse`, `IvaReportResponse` con su lista de
`IvaCategoryResponse`. **No hay PDF ni Excel**, decision deliberada en
[03-DECISIONS.md](03-DECISIONS.md).

## Base de datos

* Nombres fisicos en espanol: tablas en mayusculas y plural (`VENTAS`, `DETALLE_VENTAS`,
  `GASTOS_OPERATIVOS`), columnas en `snake_case` (`numero_factura_venta`, `fecha_gasto`).
  Todas las tablas de negocio llevan `tenant_id`, ver Estrategia multi-tenant.
* Tests: H2 en memoria con `ddl-auto=create-drop` (`src/test/resources/application-test.properties`).
* Local: `application.properties` no declara datasource ni `ddl-auto`; manda la
  autoconfiguracion de Spring Boot sobre la H2 embebida.
* Sin Flyway ni Liquibase todavia: el esquema lo genera Hibernate desde las entidades.
  Pendiente antes de produccion con PostgreSQL, ver *Estado de la gestion del esquema* en
  [03-DECISIONS.md](03-DECISIONS.md).
