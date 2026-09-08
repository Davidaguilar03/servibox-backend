# Arquitectura

## Stack tecnologico

## Integracion Spring Boot

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

### Verificacion de usuario activo en cada request

La firma de un JWT solo prueba que el token se emitio en algun momento; no dice nada del
estado actual del usuario. Sin la consulta del paso 6, desactivar a un empleado no surtiria
efecto hasta que su token venciera, hasta 24 horas despues. Por eso cada request protegido
paga una consulta a `USUARIOS`. Motivo completo en [03-DECISIONS.md](03-DECISIONS.md).

**Costo conocido:** es una consulta extra por request autenticado. Hoy es irrelevante, es
una lectura por clave unica `(tenant_id, username)` con indice. Si algun dia el volumen lo
justifica, la salida tipica es una cache corta en memoria del estado del usuario, con TTL
de segundos, o una lista de tokens revocados. No esta implementado ni hace falta todavia.
7. Al abrir la transaccion, `TenantAwareJpaTransactionManager` toma ese `TenantContext` y
   habilita el filtro de Hibernate, ver
   la seccion Estrategia multi-tenant de este mismo archivo.

En resumen: `login -> JWT con tenantId -> JwtAuthenticationFilter -> TenantContext ->
filtro de Hibernate`. El tenant nunca lo aporta el cliente en claro.

**`TenantContext.clear()` va en un `finally`** dentro del filtro. El pool de hilos de
Tomcat se reutiliza entre requests: un tenant que quede pegado se filtraria al siguiente
request atendido por ese hilo.

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
de tenants esta vacia crea el tenant `demo` y el usuario `admin`. Solo para probar el
login en local.

## Estructura de paquetes

Base: `com.servibox.backend`. Un paquete por modulo de negocio, mas tres transversales.

| Paquete | Contenido |
|-|-|
| `config` | `SecurityConfig`, `JpaAuditingConfiguration`, `JsonAuthenticationEntryPoint`, `DevDataInitializer` |
| `tenant` | `Tenant`, `TenantContext`, `TenantAwareJpaTransactionManager`, `TenantFilterConfiguration` |
| `shared` | `TenantAwareEntity`, `ErrorResponse`, `GlobalExceptionHandler` |
| `auth` | `JwtService`, `JwtAuthenticationFilter`, `AuthController`, mas `entity` / `repository` / `dto` |
| `inventory` | primer modulo de negocio migrado, ver abajo |
| `treasury` | segundo modulo de negocio migrado, ver abajo |
| `sales` | tercer modulo de negocio migrado, ver abajo |
| `purchases` | cuarto modulo de negocio migrado, ver abajo |
| `reporting` | vacio todavia |

Los modulos de negocio usan siempre las mismas cinco capas:
`controller`, `service`, `repository`, `entity`, `dto`.

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

**Nota sobre `recalculateMinSalePrice`.** La documentacion previa describia un metodo
`recalculateMinSalePrice` con un campo `minSalePrice` y un IVA fijo de 0.19. Ese metodo ya
no existe en Autollantas: fue reemplazado por `recalculatePrices`, y `minSalePrice` no
existe como campo en ninguna parte del repo. ServiBox porto la version vigente. No
reintroducir `minSalePrice` sin revisar antes el codigo real.

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
* `Movement` (`MOVIMIENTOS`): `ManyToOne` a `Account`, `type` (enum `MovementType`,
  `INGRESO` o `EGRESO`), `concept`, `amount`, `date` (`LocalDate`), y el par de origen
  `sourceType` / `sourceId`. Dos divergencias deliberadas respecto de Autollantas, ambas
  en [03-DECISIONS.md](03-DECISIONS.md): el tipo es enum y no `String`, y `concept` es una
  columna real y no una descripcion derivada de `(tabla_origen, id_origen)`.

  **El origen de un movimiento: `sourceType` + `sourceId`.**
  `sourceType` es el enum `MovementSourceType` (`TRANSFER`, `SALE`, `PURCHASE`,
  `OCCASIONAL_INCOME`) y `sourceId` es el id del registro que lo genero, interpretado
  segun ese tipo. Los dos van juntos: o ambos poblados, o ambos null, y ambos null
  significa movimiento suelto, registrado a mano.

  | `sourceType` | `sourceId` apunta a | Movimientos que genera |
  |-|-|-|
  | `TRANSFER` | `Transfer` | el EGRESO del origen y el INGRESO del destino |
  | `SALE` | `Sale` | el INGRESO del contado y el de cada abono |
  | `PURCHASE` | `Purchase` | el EGRESO del contado y el de cada pago |
  | `OCCASIONAL_INCOME` | `OccasionalIncome` | su INGRESO |
  | `OPERATIONAL_EXPENSE` | `OperationalExpense` | su EGRESO |
  | null | — | movimiento suelto |

  Sustituye a cuatro relaciones `ManyToOne` opcionales, una por origen, que era el diseno
  anterior. **Agregar un origen nuevo ahora es agregar un valor al enum y nada mas**: sin
  columna nueva, sin cambio de esquema y sin un parametro mas en `aplicarMovimiento`.
  `OPERATIONAL_EXPENSE` fue el primero en estrenarlo, y costo exactamente eso: una linea.
  Motivo completo y la contrapartida (no hay clave foranea) en
  [03-DECISIONS.md](03-DECISIONS.md).

  Para recuperar los movimientos de un origen concreto hay un unico metodo,
  `MovementRepository.findBySourceTypeAndSourceIdAndTenantId`, que reemplaza a los tres
  buscadores que habia antes.
* `OccasionalIncome` (`INGRESOS_OCASIONALES`): `concept`, `amount`, `ManyToOne` a
  `Account`, `date`. Ingreso puntual que no viene de una venta: reintegros, venta de
  chatarra, un aporte del socio. Autollantas tiene ademas un campo `notes` que no se porto.
* `OperationalExpense` (`GASTOS_OPERATIVOS`): `concept` (`concepto_gasto`), `amount`
  (`monto_gasto`), `ManyToOne` a `Account`, `date` (`fecha_gasto`) y `notes`. Gasto puntual
  que no viene de una factura de compra: arriendo, servicios publicos, papeleria. Es la
  imagen espejo de `OccasionalIncome` con el signo invertido. **Aqui `notes` si se porto**:
  es el campo "Observaciones" del formulario de Autollantas y es **opcional** (su
  `validateFields` solo exige concepto, monto, cuenta y fecha). En la interfaz de
  Autollantas vive bajo Egresos, junto a las facturas de compra, pero el codigo esta en
  `treasury` y aqui tambien.
* `Transfer` (`TRANSFERENCIAS`): `ManyToOne` a `Account` origen y destino, `amount`,
  `concept`, `date`. Autollantas llama a esas relaciones `sourceAccount` /
  `destinationAccount`; aqui son `originAccount` / `destinationAccount`, que es como
  aparecen en la vista (columnas Origin / Destination de `tablaTransferencias`).

**`TreasuryService`**

* `registrarMovimiento(cuenta, tipo, concepto, monto)`: registra un ingreso o egreso
  suelto. Delega en `aplicarMovimiento` sin origen (`sourceType` y `sourceId` null).
* `aplicarMovimiento(cuenta, tipo, concepto, monto, sourceType, sourceId)` (privado):
  **el unico sitio del modulo donde se crea un `Movement` y se mueve el `currentBalance`
  de su cuenta.** Suma si es `INGRESO`, resta si es `EGRESO`. Las dos cosas van juntas a
  proposito: un movimiento guardado sin mover el saldo, o un saldo movido sin movimiento
  que lo explique, es un descuadre silencioso. Como no hay ninguna otra via para tocar
  `currentBalance`, las dos rutas que lo mueven no se pueden desincronizar.
* `registrarTransferencia(origen, destino, concepto, monto)`: guarda el `Transfer` y
  genera **dos movimientos** por `aplicarMovimiento`, un `EGRESO` en el origen con
  concepto "Transferencia a {destino}" y un `INGRESO` en el destino con concepto
  "Transferencia desde {origen}", los dos con `(TRANSFER, id del Transfer)`.
  Son esos movimientos los que mueven los saldos; la transferencia no toca
  `currentBalance` por su cuenta. Todo en una sola transaccion. **El dinero se conserva**:
  el balance global del tenant queda igual que antes. Rechaza con
  `InvalidTransferException` (**400**) si las dos cuentas son la misma o si el monto no es
  mayor a cero; en ese caso no se mueve ningun saldo ni queda ningun movimiento colgando.
* `registrarIngresoOcasional(cuenta, concepto, monto, fecha)`: guarda el
  `OccasionalIncome` y genera su `INGRESO` por `aplicarMovimiento`, con
  `(OCCASIONAL_INCOME, id)`. **No toca `currentBalance` por su
  cuenta**: el saldo lo mueve el movimiento, igual que la transferencia. Rechaza monto no
  positivo y cuenta ausente.
* `anularIngresoOcasional(id)`: borra el movimiento, resta el importe del saldo y
  **elimina la fila**. Es `deleteOccasionalIncome` de Autollantas, donde la accion en la
  interfaz se llama "Eliminar". Ojo: a diferencia de una factura de venta, aqui no queda
  ningun estado `ANULADA`, el registro desaparece. Ver
  [03-DECISIONS.md](03-DECISIONS.md).
* `registrarGastoOperativo(cuenta, concepto, monto, fecha, notas)`: guarda el
  `OperationalExpense` y genera su `EGRESO` por `aplicarMovimiento`, con
  `(OPERATIONAL_EXPENSE, id)`. Espejo exacto de `registrarIngresoOcasional`. **No exige
  saldo suficiente**, a diferencia de `registrarEgresoDeCompra`: Autollantas tampoco, asi
  que una cuenta puede quedar en negativo por un gasto. Ver
  [03-DECISIONS.md](03-DECISIONS.md).
* `editarGastoOperativo(id, cuenta, concepto, monto, fecha, notas)`: **el unico registro de
  tesoreria que se puede editar.** El mecanismo es **revertir y recrear**, no ajustar la
  diferencia:

  1. Revierte el movimiento anterior con el mismo `revertir(...)` que usa la anulacion, que
     le devuelve el importe a **la cuenta de ese movimiento**, y lo borra.
  2. Actualiza los campos del gasto (misma fila, mismo id).
  3. Registra un `EGRESO` nuevo por `aplicarMovimiento` con los valores actualizados.

  Es lo que hace `saveOperationalExpense(expense, editMode = true)` de Autollantas. Que el
  paso 1 mire la cuenta del movimiento viejo y no la del gasto ya editado es lo que hace
  que **cambiar de cuenta** salga bien: el dinero vuelve de donde salio. Al terminar queda
  **un solo movimiento**, el nuevo, no un par movimiento + contra-movimiento.
* `anularGastoOperativo(id)`: borra el movimiento, devuelve el importe al saldo y **elimina
  la fila**. Es `deleteOperationalExpense` de Autollantas, donde la accion se llama
  "Eliminar". Mismo nombre enganoso y mismo criterio que `anularIngresoOcasional`: no queda
  ningun estado `ANULADA`.
* `balanceGlobal()`: suma de los `currentBalance` de todas las cuentas del tenant activo.
  Es el "Total Global" (`lblTotalGlobal`) de `Accounts.fxml`.

**Cuidado: el `@Filter` de Hibernate no cubre `findById`.**
El filtro por tenant se aplica a las consultas (HQL, criteria, consultas derivadas de
Spring Data), pero **no** a `EntityManager.find()`, que es lo que usa el `findById`
heredado de `JpaRepository`. Es decir, `accountRepository.findById(id)` devuelve la cuenta
aunque sea de otro tenant. Por eso `TreasuryService.findAccountById` usa la consulta
derivada `findByIdAndTenantId` y no el `findById` heredado.

Lo detecto el test de aislamiento: `GET /api/treasury/accounts/{id}/movements` con el token
del tenant equivocado respondia 200 con lista vacia (la lista de movimientos si estaba
filtrada, porque es una consulta derivada) en vez de rechazar la cuenta ajena. Cualquier
busqueda por id de una entidad de negocio tiene el mismo problema: **no usar `findById`
directo en un service multi-tenant.**

El barrido posterior encontro el mismo hueco en los 3 `findById` de `InventoryService`,
uno de ellos con consecuencia de escritura. Todos corregidos, ver
[03-DECISIONS.md](03-DECISIONS.md).

**Endpoints** (todos requieren JWT)

* `GET` y `POST` `/api/treasury/accounts`
* `GET /api/treasury/accounts/{id}/movements`
* `POST /api/treasury/movements` (registra un ingreso o un egreso)
* `POST /api/treasury/transfers`
* `GET` y `POST` `/api/treasury/occasional-incomes`
* `GET` y `POST` `/api/treasury/operational-expenses`
* `PUT /api/treasury/operational-expenses/{id}` — edita el gasto; 404 si no existe para el
  tenant. `PUT` y no `PATCH` porque el cuerpo trae el gasto completo, igual que el
  formulario de Autollantas, que reenvia todos los campos al guardar en modo edicion.
* `DELETE /api/treasury/operational-expenses/{id}` — **204**; 404 si no existe para el
  tenant. `DELETE` y no un `/annul` porque el registro desaparece, no queda en estado
  `ANULADA`.
* `GET /api/treasury/balance` (balance global)

Las respuestas van siempre por DTO (`AccountResponse`, `MovementResponse`,
`TransferResponse`, `OccasionalIncomeResponse`, `OperationalExpenseResponse`,
`BalanceResponse`), nunca la entidad JPA.
`MovementResponse` incluye `sourceType` (como `String`) y `sourceId`, los dos null en los
movimientos sueltos, para que el cliente distinga en el listado de que viene cada renglon.

**Semilla de desarrollo.** `DevDataInitializer` crea ademas las 2 cuentas por defecto de
Autollantas para el tenant `demo`: `Caja General` (`CASH`) y `Bancolombia` (`BANK`), las
dos con saldo inicial 0.

### Modulo Sales

Migrado desde el paquete `sales` de Autollantas. Es el primer modulo que **cruza los otros
dos**: una factura descuenta inventario y mueve tesoreria.

**Entidades**

* `Customer` (`CLIENTES`): `name`, `document`, `email`, `phone`. Es una entidad propia, no
  campos sueltos dentro de la venta, igual que en Autollantas. Restriccion unica
  `uk_cliente_tenant_document` sobre `(tenant_id, numero_documento_cliente)`: el mismo NIT
  puede ser cliente de dos talleres distintos. `document` es nullable porque Autollantas
  permite facturar sin documento, y en SQL varios NULL no chocan entre si.
* `Sale` (`VENTAS`): `invoiceNumber`, `ManyToOne` a `Customer`, `invoiceDate`, `dueDate`,
  `paymentType` (enum `CONTADO` / `CREDITO`), `ManyToOne` **nullable** a `Account` (una
  factura a credito no tiene cuenta hasta que se le abona), `paymentMethod`, `status`
  (enum `PAGADA` / `PENDIENTE` / `ANULADA`), `subtotal`, `ivaPorPagar`, `total`.
  Restriccion unica `uk_venta_tenant_invoice_number` sobre
  `(tenant_id, numero_factura_venta)` — **divergencia deliberada**: Autollantas valida el
  numero solo en la aplicacion y no tiene indice unico. Ver
  [03-DECISIONS.md](03-DECISIONS.md).
* `SaleDetail` (`DETALLE_VENTAS`): `ManyToOne` a `Sale` y a `Product`, `quantity`, `price`
  (unitario sin IVA) e `ivaAmount`, **congelado al facturar**. Cambiarle el IVA a un
  producto no puede mover el IVA de una factura ya emitida, porque esa factura ya se
  entrego y se declaro. Es el mismo campo `iva_generado_linea` de Autollantas.
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
   `INGRESO` por el total en la cuenta. Si es `CREDITO`: estado `PENDIENTE` y tesoreria no
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

**Endpoints** (todos requieren JWT)

* `POST /api/sales` (emitir factura)
* `GET /api/sales`
* `GET /api/sales/{id}` — 404 si no existe para el tenant
* `POST /api/sales/{id}/collections` (abono)
* `POST /api/sales/{id}/annul`

Las respuestas van por DTO (`SaleResponse`, `SaleDetailResponse`, `CollectionResponse`),
nunca la entidad JPA. `SaleResponse` incluye `pendingBalance` calculado.

**Tasa de IVA: una sola implementacion.** `InventoryService.getIvaRateForProduct(Product)`
es publico y es de donde la toman tanto el calculo de precios de Inventory como Sales. En
Autollantas ese `getIvaRate(Product)` esta copiado identico en cuatro sitios
(`SaleFormController`, `SaleDetailsController`, `ProductsController` y
`SaleDetailRow.ivaRate()`) y la propia documentacion del proyecto lo marca como candidato
a centralizar. **No volver a copiarlo.**

### Modulo Purchases

Migrado desde el paquete `purchases` de Autollantas. Es el espejo de Sales con el signo
invertido: una compra **suma** stock y **saca** dinero.

**Entidades**

* `Supplier` (`PROVEEDORES`): `name`, `businessName`, `document` (NIT), `email`, `phone`.
  Restriccion unica `uk_proveedor_tenant_document` sobre `(tenant_id, numero_nit_proveedor)`.
* `Purchase` (`COMPRAS`): `invoiceNumber`, `ManyToOne` a `Supplier`, `invoiceDate`,
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

**Endpoints** (todos requieren JWT)

* `POST /api/purchases`
* `GET /api/purchases`
* `GET /api/purchases/{id}` — 404 si no existe para el tenant
* `POST /api/purchases/{id}/payments`
* `POST /api/purchases/{id}/annul`

## Base de datos
