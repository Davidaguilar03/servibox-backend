# Color persistido de cuentas, 2026-10-27

Rama: feat/42-account-colors. Raiz Git: servibox-backend exterior; carpeta Maven:
servibox-backend/servibox-backend. Main local y origin/main remoto: 2caab1b. PR 41
integrado en ese commit. Los dos cambios ajenos de .idea quedan intactos y excluidos.

## Contrato

Rutas existentes: POST /api/treasury/accounts, PUT /api/treasury/accounts/{id} y
GET /api/treasury/accounts. Color es un string hexadecimal opaco exactamente #RRGGBB.
Se aceptan letras mayusculas o minusculas, se guarda y devuelve mayusculas. Todo valor
no nulo invalido devuelve HTTP 400 con `{ "error": "El color debe tener el formato
#RRGGBB", "status": 400 }`, sin escritura parcial.

POST admite omision o null y crea la cuenta con color null. PUT con omision o null
conserva el color actual; no hay operacion para borrarlo. Las respuestas de alta,
edicion y listado siempre incluyen color, incluso null. Los otros campos obligatorios,
tipo inmutable y propiedades desconocidas conservan la regla anterior.

Ejemplos:

```json
{"name":"Caja","initialBalance":100,"type":"EFECTIVO","color":"#7c3aed"}
{"id":1,"name":"Caja","initialBalance":100.0,"currentBalance":100.0,"type":"EFECTIVO","color":"#7C3AED"}
{"name":"Caja","initialBalance":100,"color":null}
{"id":1,"name":"Caja","initialBalance":100.0,"currentBalance":100.0,"type":"EFECTIVO","color":"#7C3AED"}
```

## Persistencia y alcance

V7__account_color.sql agrega `cuentas.color_cuenta VARCHAR(7) NULL`. V1 a V6
intactas; no hay backfill. Las cuentas antiguas siguen null. Mismo SQL para H2
en modo PostgreSQL y PostgreSQL. Color no afecta saldos, movimientos, tenant,
orden, permisos, bloqueo, transacciones, KPIs, reportes ni exportaciones.

Archivos de codigo: AccountRequest, AccountUpdateRequest, AccountResponse, Account,
TreasuryController y TreasuryService. Pruebas: AccountEditingTest y V7AccountColorTest.
Documentos: 02-CONVENTIONS, 03-DECISIONS, 04-DATA-MODEL y esta nota.

El siguiente extra frontend debe ofrecer selector en Nueva/Editar, priorizar el color
persistido y usar su fallback provisional solo si color es null. Despues sigue
Reportes. Nuevo mensaje para src/api/errorMessages.ts:

`El color debe tener el formato #RRGGBB`

## Verificacion

Suite completa: 483 pruebas, 0 fallos, 0 errores, 0 omitidas. Base conocida y
confirmada por diferencia: 466; nuevas: 17 (15 casos en AccountEditingTest, 2 en
V7AccountColorTest); bajas: 0. No se ajusto ningun caso existente: AccountEditingTest
solo agrega cobertura. Maven package con skipTests posterior: BUILD SUCCESS.

H2 real: Flyway migro de cero V1 a V7 y desde V6 a V7. La prueba de esquema confirma
VARCHAR(7) nullable; la cuenta anterior conserva color null, balance inicial 100 y
actual 80. API MockMvc real con H2: alta/edicion/listado, formato y error, tenant,
permisos, saldo y movimiento comprobados. No se uso navegador ni simulacion visual.

PostgreSQL 18.2 desechable: SQL V1 a V6 aplicado en un esquema temporal, luego una
cuenta antigua y V7. Esperado frente a obtenido: color_cuenta VARCHAR(7) nullable,
`color_cuenta|7|YES`; cuenta previa con color null, inicial 100.00 y actual 80.00,
`1|NULL|100.00|80.00`. La comprobacion fue con psql, no con Flyway ni con la API.
El cluster y su carpeta temporal exclusivos se detuvieron y eliminaron; comprobacion
`scratchRemoved=True`. No se toco una base personal ni de produccion.

Limitacion: PostgreSQL 16 de produccion no estuvo disponible; la prueba en 18.2 no
certifica esa version. No se ejecutaron exportados ni frontend, fuera del alcance.

## Cierre autorizado

2026-10-27, America/Bogota. David autorizo commit, push y PR de
feat/42-account-colors. Se revisaron 13 archivos de tarea: 10 modificados y 3
nuevos. V7 es nueva; V1 a V6 no tienen cambios. La suite de 483 pruebas y el
empaquetado se ejecutaron despues del ultimo cambio de codigo o pruebas; las
ediciones posteriores fueron solo de documentacion. `git diff --check` paso.
El proyecto Maven no declara un comando ni plugin de lint. No se incluyeron
secretos, bases, exportados, temporales ni archivos .idea. Frontend y AGENTS.md
permanecen intactos. No se hizo merge ni cambio de rama.
