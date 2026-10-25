# 2026-10-24: NIT y telefono del tenant obligatorios

Rama `feat/39-required-tenant-contact`. Decision en [03-DECISIONS.md](../03-DECISIONS.md),
"NIT y telefono del tenant obligatorios en la API".

## Cambios

* `TenantService.actualizar` (PUT /api/tenant): `validarNit` y `validarTelefono` siempre,
  como el registro; ya no guarda null en blanco. Valores recortados.
* `POST /api/auth/register` ya los exigia: sin cambios de codigo, pruebas nuevas.
* `DevDataInitializer.sembrarBase`: tenant demo con NIT 900000000-0 y telefono 3000000000.
* Sin migracion: `nit_tenant` y `telefono_tenant` siguen NULL en la base.

## Reglas

* Ausente, null, vacio o solo espacios: 400 "El NIT es obligatorio" / "El telefono es
  obligatorio".
* NIT: hasta 25 caracteres, digitos con un guion y digito de verificacion opcionales.
* Telefono: hasta 25 caracteres, digitos, espacios, +, - y parentesis.
* Orden en PUT: 403 sin ADMIN, despues nombre, NIT y telefono.

## Limitaciones

* El frontend aun trata NIT y telefono como opcionales en el perfil: mostrara el 400 del
  backend hasta su ajuste.
* El NIT no es unico entre tenants.
* Los tenants anteriores siguen con null en la base hasta que editen su perfil.
