# Log de decisiones tecnicas

Una entrada por decision, en orden cronologico inverso (la mas reciente arriba).

Formato de cada entrada:

```
## AAAA-MM-DD: Titulo corto de la decision

* Decision: que se decidio hacer.
* Alternativas consideradas: opciones que se evaluaron y se descartaron.
* Motivo: por que se eligio esta opcion sobre las demas.
```

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
