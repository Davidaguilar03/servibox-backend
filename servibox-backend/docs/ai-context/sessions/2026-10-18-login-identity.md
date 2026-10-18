# 2026-10-18: El login devuelve la identidad

Rama `feat/22-login-identity`. Objetivo: que `POST /api/auth/login` devuelva, ademas del
token, quien entro y a que taller.

## Lo que habia

* `AuthController.login` (no hay servicio de autenticacion) devolvia `LoginResponse(token)`,
  es decir `{"token": "..."}` y nada mas.
* `User`: `username`, `passwordHash`, `email` (opcional), `active`, `role` (enum `ADMIN`,
  `MECANICO`, `CAJERO`), mas `id` y `tenantId` heredados. No hay nombre para mostrar.
* `Tenant`: `id`, `name` (`nombre_tenant`, el nombre comercial), `slug`, `active`,
  `createdAt`.
* El rol del token sale de `User.role`, en base.

## Lo que se hizo

* `LoginResponse` pasa a `token`, `user` (`username`, `role`) y `tenant` (`name`, `slug`),
  armado con el usuario y el tenant que el login ya tenia en memoria.
* Sin cambios en el JWT, la expiracion, el 401 generico ni el esquema. Sin `/me`.
* Decisiones: [03-DECISIONS.md](../03-DECISIONS.md), entrada "El login devuelve la
  identidad del usuario y del tenant".

## Verificacion

* Tests: 207 antes, 209 despues. `auth.AuthLoginIntegrationTest` pasa de 7 a 9; el test del
  401 ahora comprueba ademas que no viajan `token`, `user` ni `tenant`.
* Prueba real en perfil dev con curl: login de `demo/admin` 200 con `user` y `tenant`;
  clave mala 401.
