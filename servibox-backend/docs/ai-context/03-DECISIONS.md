# Log de decisiones tecnicas

Una entrada por decision, en orden cronologico inverso (la mas reciente arriba).

Formato de cada entrada:

```
## AAAA-MM-DD: Titulo corto de la decision

* Decision: que se decidio hacer.
* Alternativas consideradas: opciones que se evaluaron y se descartaron.
* Motivo: por que se eligio esta opcion sobre las demas.
```

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
