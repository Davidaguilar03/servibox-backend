# 2026-10-22: Registro publico de servitecas

Rama `feat/28-tenant-registration`. Objetivo: `POST /api/auth/register` publico que crea un
tenant con su administrador, aprovisiona lo minimo para operar y devuelve la sesion; primera
migracion V2.

## Lo que habia

* Solo `POST /api/auth/login` era publico. `LoginResponse` sin nombre para mostrar.
* TENANTS (V1): `nombre_tenant` VARCHAR(150) NOT NULL, `slug_tenant` VARCHAR(100) NOT NULL
  con `uk_tenant_slug` global, `activo_tenant`, `fecha_creacion_tenant`. USUARIOS:
  `username` VARCHAR(50) con `uk_usuario_tenant_username` (id_tenant, username),
  `password_hash` 255, `email` 150 NULL, `activo`, `rol` 30.
* El tenant activo sale de `TenantContext`; `TenantAwareEntity` lo toma en `@PrePersist` y
  `TenantAwareJpaTransactionManager` habilita el filtro de Hibernate al abrir la
  transaccion. `DevDataInitializer` pone `TenantContext` a mano tras crear el tenant demo.
* El Consumidor final se creaba perezosamente (listado de clientes o venta sin cliente).

## Lo que se hizo

* V2 `V2__tenant_nit_phone_user_full_name.sql`: `nit_tenant`, `telefono_tenant` (25) y
  `nombre_completo` (150), NULL. Entidades, diccionario y `FlywayMigrationTest` (V2 sobre
  V1 con datos). `SchemaConformanceTest` no cambio: solo lee el diccionario.
* `TenantRegistrationService`, `RegisterRequest`, tres excepciones (400, 409, 403),
  `existsBySlugIgnoreCase`, ruta publica, `servibox.registration.enabled`.
* `LoginResponse.user.fullName`. `InventoryService.crearIvaPorDefecto` lo usan la siembra
  dev y el registro; la siembra dev no cambia de comportamiento.
* Como la transaccion empieza sin tenant, el servicio habilita el filtro de Hibernate a
  mano despues de insertar el tenant.
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Registro publico de servitecas y
  primera migracion V2".

## Verificacion

* Tests: 276 antes, 298 despues. Nuevas `auth.TenantRegistrationTest` (19, perfiles test y
  dev, con contexto propio para no compartir base con `DevDemoDataTest`, que cuenta
  tenants), `auth.TenantRegistrationRollbackTest` (1) y `auth.TenantRegistrationDisabledTest`
  (1); `schema.FlywayMigrationTest` de 3 a 4. Ninguna prueba existente cambio.
* La prueba de registros simultaneos pasa por la violacion de `uk_tenant_slug` (comprobado
  con un log temporal en tres corridas), no solo por la consulta previa.
* Prueba real en perfil dev (puerto 8080): Flyway aplica V1 y V2; registro 201, login 200
  con `fullName`; 3 categorias, IVA 0.19, Caja General en 0, Consumidor final, configuracion
  en ceros; producto, ingreso de 1000000, compra de contado 476000, venta FV-0001 178500 al
  Consumidor final; Caja 702500 y stock 3. Slug repetido 409, tres 400, el demo no ve nada
  del nuevo (404 al pedir su producto por id). Tras reiniciar, el login del tenant nuevo da
  401 y el demo 200.
* PostgreSQL: Docker no estaba corriendo, no se probo V2 contra PostgreSQL 16.
