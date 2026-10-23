# 2026-10-22: Perfil del tenant y NIT en los reportes

Rama `feat/29-tenant-profile-endpoints`. Objetivo: `GET` y `PUT /api/tenant` para leer y
editar el perfil de la serviteca, solo ADMIN edita, y el NIT bajo el nombre en el
encabezado de los reportes.

## Lo que habia

* El tenant llega al controlador solo por `TenantContext` (claim `tenantId` del JWT que pone
  `JwtAuthenticationFilter`). El principal es el username (String) y la authority
  `ROLE_<rol>` sale del rol en base. Ninguna regla usaba el rol: sin `@PreAuthorize`, sin
  `@EnableMethodSecurity`, y `SecurityConfig` solo distingue publico de autenticado.
* `GlobalExceptionHandler` tiene un `@ExceptionHandler(Exception.class)`: un
  `AccessDeniedException` lanzado en el controlador saldria como 500.
* Las reglas de nombre, NIT y telefono estaban en el metodo privado `validar` de
  `TenantRegistrationService`.
* TENANTS no extiende `TenantAwareEntity`: el filtro de Hibernate no aplica y se lee por id
  (como ya hacia `ReportExportService.nombreTenant`). Columnas `nit_tenant` y
  `telefono_tenant` VARCHAR(25) NULL desde V2.

## Lo que se hizo

* `tenant.TenantController`, `TenantService`, `AdminRequiredException` (403) y los DTOs
  `TenantProfileRequest` (sin slug) y `TenantProfileResponse`.
* `TenantRegistrationService.validarNombreComercial`, `validarNit` y `validarTelefono`,
  publicos y estaticos; el registro los usa igual que antes.
* `ReportModel.tenantNit`; `ReportExportService` lee nombre y NIT del mismo `Tenant`; PDF y
  Excel agregan "NIT <valor>" bajo el nombre solo si hay NIT.
* Sin migracion, sin usuarios ni roles nuevos.
* Decision: [03-DECISIONS.md](../03-DECISIONS.md), "Perfil del tenant (GET y PUT
  /api/tenant) y NIT en los reportes".

## Verificacion

* Tests: 298 antes, 311 despues. Nueva `tenant.TenantProfileTest` (13). Ninguna prueba
  existente cambio: las de reportes crean sus tenants sin NIT, asi que su encabezado no
  cambia.
* Prueba real en perfil dev (puerto 8080) con curl: GET del demo con nit y phone null; PUT
  con NIT 900123456-7 y GET; slug en el cuerpo ignorado; tres 400 con el mensaje del
  registro; registro de `la-ochenta` con su NIT y telefono en el GET y un PUT; Estado de
  resultados en PDF y XLSX con la linea NIT (demo editado y la-ochenta) y sin ella (demo
  antes de editar); 401 sin token en GET y PUT.
