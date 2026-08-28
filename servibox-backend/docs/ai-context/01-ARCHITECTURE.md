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

**`tenant.TenantFilter`**
Servlet filter (`OncePerRequestFilter`), registrado con `Ordered.HIGHEST_PRECEDENCE` para
correr antes de Spring Security. Lee la cabecera temporal `X-Tenant-Id`, la convierte a
`Long` y la deja en `TenantContext`; si la cabecera trae basura responde 400. Siempre
llama `TenantContext.clear()` en el `finally`, porque el hilo vuelve al pool de Tomcat y
un valor pegado filtraria datos de otro tenant.

Esta cabecera es provisional. Cuando exista JWT, el tenant sale de un claim del token y
la cabecera desaparece.

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
`TenantContext` debe estar poblado antes. En un request lo garantiza `TenantFilter`, que
corre con `HIGHEST_PRECEDENCE`. Codigo que abra transacciones fuera de un request tiene
que poblar `TenantContext` explicitamente.

**`tenant.TenantFilterConfiguration`**
`@Configuration` que registra el `FilterRegistrationBean` de `TenantFilter` sobre `/*` y
el bean `PlatformTransactionManager` con `TenantAwareJpaTransactionManager`.

## Capa de seguridad JWT

## Estructura de paquetes

## Base de datos
